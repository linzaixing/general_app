package com.example.panocam;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把拼接好的 2:1 等距全景帧（ARGB int[]）编码成 H.264 MP4。
 * 每帧通过 EGL 画到 MediaCodec 的输入 Surface，成品直接写进相册 Movies/PanoCam。
 */
public class EquirectRecorder {

    private static final String TAG = "PanoCam";
    private static final String VS =
            "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;" +
            "void main(){ gl_Position=vec4(aPos,0.0,1.0); vTex=aTex; }";
    private static final String FS =
            "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; uniform float uRoll;" +
            "void main(){" +
            "  float lon = (vTex.x - 0.5) * 6.28318530718;" +
            "  float lat = (0.5 - vTex.y) * 3.14159265359;" +
            "  float cl = cos(lat);" +
            "  vec3 d = vec3(cl * sin(lon), sin(lat), cl * cos(lon));" +
            // 防抖：绕前方轴（z）旋转 uRoll，把地平线摆正（与网页端着色器同一语义）
            "  float c = cos(uRoll), s = sin(uRoll);" +
            "  vec3 e = vec3(d.x * c - d.y * s, d.x * s + d.y * c, d.z);" +
            "  float lon2 = atan(e.x, e.z);" +
            "  float lat2 = asin(clamp(e.y, -1.0, 1.0));" +
            "  vec2 uv = vec2(lon2 / 6.28318530718 + 0.5, 0.5 - lat2 / 3.14159265359);" +
            "  gl_FragColor = texture2D(uTex, uv);" +
            "}";

    private final Context context;
    private final int width, height;

    private MediaCodec codec;
    private Surface inputSurface;
    private MediaMuxer muxer;
    private ParcelFileDescriptor muxerFd;
    private Uri outputUri;
    private File outputFile;
    private Bitmap bitmap;
    private boolean textureReady;
    private int trackIndex = -1;
    private boolean muxerStarted;
    private Thread drainThread;
    private volatile boolean active;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program, texId, aPos, aTex, uTex, uRoll;
    private FloatBuffer posQuad, texQuad;

    public EquirectRecorder(Context context, int width, int height) {
        this.context = context.getApplicationContext();
        this.width = width;
        this.height = height;
    }

    public synchronized void start() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();

        createMuxer();
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        setupEgl();
        active = true;
        startDrain();
        Log.i(TAG, "equirect recorder started " + width + "x" + height);
    }

    private int bitrate() {
        return Math.max(2_500_000, Math.min(8_000_000, width * height * 6));
    }

    private void createMuxer() throws IOException {
        String name = "pano-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/PanoCam");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            outputUri = context.getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (outputUri == null) {
                throw new IOException("无法创建相册视频条目");
            }
            muxerFd = context.getContentResolver().openFileDescriptor(outputUri, "w");
            if (muxerFd == null) {
                throw new IOException("无法写入相册");
            }
            muxer = new MediaMuxer(muxerFd.getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } else {
            File dir = new File(context.getExternalFilesDir(null), "videos");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("无法创建视频目录");
            }
            outputFile = new File(dir, name);
            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }
    }

    private void setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int[] configAttrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, count, 0);
        if (count[0] == 0) {
            throw new IllegalStateException("找不到可用的 EGL 配置");
        }
        int[] contextAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
        int[] surfaceAttrs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
                inputSurface, surfaceAttrs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("无法创建 EGL 窗口表面");
        }
        // 只在 push() 里绑定到当前线程，其它线程不占用 context
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        program = buildProgram(VS, FS);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uRoll = GLES20.glGetUniformLocation(program, "uRoll");

        float[] p = {-1, -1, 1, -1, -1, 1, 1, 1};
        float[] t = {0, 1, 1, 1, 0, 0, 1, 0};   // 图片上下翻转
        posQuad = toBuffer(p);
        texQuad = toBuffer(t);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        texId = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // 旋转后的采样坐标始终落在 0..1（经度由 atan2 归一化），用 CLAMP_TO_EDGE 即可；
        // 采集源宽度可能不是 2 的幂，GLES2 下 NPOT 纹理不能配 REPEAT，否则整张纹理会失效
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
    }

    private static FloatBuffer toBuffer(float[] values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0);
        return b;
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    /** 编码宽高（录制期间固定，页面改画质也不会影响正在写入的视频）。 */
    public int width() {
        return width;
    }

    /** 送一帧拼接好的 2:1 等距原图；rollRad != 0 时在编码前把地平线摆正。必须拷贝到自己的 Bitmap。 */
    public synchronized void push(int[] argb, float rollRad) {
        if (!active || codec == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        try {
            bitmap.setPixels(argb, 0, width, 0, 0, width, height);
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                return;
            }
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            if (!textureReady) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                textureReady = true;
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
            }
            GLES20.glUniform1i(uTex, 0);
            GLES20.glUniform1f(uRoll, rollRad);

            GLES20.glEnableVertexAttribArray(aPos);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posQuad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texQuad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPos);
            GLES20.glDisableVertexAttribArray(aTex);

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime());
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
        } catch (Throwable t) {
            Log.w(TAG, "push frame failed", t);
        }
    }

    private void startDrain() {
        drainThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (true) {
                    int id = codec.dequeueOutputBuffer(info, 10000);
                    if (id == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    }
                    if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.getOutputFormat());
                            muxer.start();
                            muxerStarted = true;
                        }
                        continue;
                    }
                    if (id < 0) {
                        continue;
                    }
                    ByteBuffer buf = codec.getOutputBuffer(id);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size > 0 && muxerStarted && buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, buf, info);
                    }
                    codec.releaseOutputBuffer(id, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "record drain stopped", t);
            }
        }, "rec-drain");
        drainThread.start();
    }

    /** 结束录制，返回保存路径（相册 Uri 或文件路径）；没有文件时返回 null。 */
    public String stop() {
        synchronized (this) {
            if (!active) {
                return null;
            }
            active = false;
            try {
                if (codec != null) {
                    codec.signalEndOfInputStream();
                }
            } catch (Throwable ignored) {
            }
        }
        Thread t = drainThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (Throwable ignored) {
            }
        }
        synchronized (this) {
            String path = outputUri != null ? outputUri.toString()
                    : (outputFile != null ? outputFile.getAbsolutePath() : null);
            releaseEgl();
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Throwable ignored) {
            }
            codec = null;
            try {
                if (inputSurface != null) {
                    inputSurface.release();
                }
            } catch (Throwable ignored) {
            }
            inputSurface = null;
            try {
                if (muxer != null) {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
            muxer = null;
            muxerStarted = false;
            trackIndex = -1;
            try {
                if (muxerFd != null) {
                    muxerFd.close();
                }
            } catch (Throwable ignored) {
            }
            muxerFd = null;
            if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, done, null, null);
                } catch (Throwable ignored) {
                }
            }
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
            textureReady = false;
            return path;
        }
    }

    private void releaseEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable t) {
            Log.w(TAG, "release egl failed", t);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglDisplay = EGL14.EGL_NO_DISPLAY;
    }
}
