package com.example.panocam;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 双鱼眼 -> 等距柱状的 GPU 拼接（EGL pbuffer + 片元着色器）。
 * 采样公式与 PanoStitcher 的 LUT 一致；首次使用时用合成图与 CPU/NEON 结果逐像素自检，
 * 不一致就放弃 GPU 路径，保证不会把画面拼坏。
 */
public final class GpuStitcher {

    private static final String TAG = "PanoPerf";

    private static final String VS =
            "attribute vec2 aPos; varying vec2 vTex;" +
            "void main(){ vTex = (aPos + 1.0) * 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }";

    private static final String FS =
            "precision highp float;" +
            "varying vec2 vTex;" +
            "uniform sampler2D uTex;" +
            "uniform vec2 uOutSize;" +
            "uniform vec2 uSrcSize;" +
            "uniform float uHalfFov;" +
            "uniform float uBlend;" +
            "uniform vec4 uLens0;" +
            "uniform vec4 uLens1;" +
            "const float PI = 3.14159265359;" +
            // 与 CPU sample() 一致：越界返回黑；坐标加 0.5 对齐纹理像素中心
            "vec3 samp(vec2 uv, float x0, float x1) {" +
            "  if (uv.x < x0 || uv.y < 0.0 || uv.x > x1 || uv.y > uSrcSize.y - 1.0) return vec3(0.0);" +
            "  return texture2D(uTex, (uv + vec2(0.5)) / uSrcSize).bgr;" +
            "}" +
            "void main() {" +
            "  float px = vTex.x * uOutSize.x - 0.5;" +
            "  float py = vTex.y * uOutSize.y - 0.5;" +
            "  float lon = -PI + 2.0 * PI * px / uOutSize.x;" +
            "  float lat = PI * 0.5 - PI * py / (uOutSize.y - 1.0);" +
            "  float cl = cos(lat);" +
            "  float rx = cl * sin(lon);" +
            "  float ry = sin(lat);" +
            "  float rz = cl * cos(lon);" +
            "  float halfW = uSrcSize.x * 0.5;" +
            "  float maxY = uSrcSize.y - 1.0;" +
            "  float th = acos(clamp(-rz, -1.0, 1.0));" +
            "  float phi = atan(ry, rx) + uLens0.w;" +
            "  float rr = uLens0.z * th / uHalfFov;" +
            "  vec2 uvL = vec2(uLens0.x + rr * cos(phi), uLens0.y + rr * sin(phi));" +
            "  if (uvL.x < 0.0 || uvL.y < 0.0 || uvL.x > halfW - 1.0 || uvL.y > maxY) {" +
            "    float rr2 = min(rr, uLens0.z * 0.99);" +
            "    uvL = vec2(uLens0.x + rr2 * cos(phi), uLens0.y + rr2 * sin(phi));" +
            "  }" +
            "  th = acos(clamp(rz, -1.0, 1.0));" +
            "  phi = atan(-ry, rx) + uLens1.w;" +
            "  rr = uLens1.z * th / uHalfFov;" +
            "  vec2 uvR = vec2(halfW + uLens1.x + rr * cos(phi), uLens1.y + rr * sin(phi));" +
            "  if (uvR.x < halfW || uvR.y < 0.0 || uvR.x > uSrcSize.x - 1.0 || uvR.y > maxY) {" +
            "    float rr2 = min(rr, uLens1.z * 0.99);" +
            "    uvR = vec2(halfW + uLens1.x + rr2 * cos(phi), uLens1.y + rr2 * sin(phi));" +
            "  }" +
            "  float w = clamp((-rz + uBlend) / (2.0 * uBlend), 0.0, 1.0);" +
            "  vec3 cL = samp(uvL, 0.0, halfW - 1.0);" +
            "  vec3 cR = samp(uvR, halfW, uSrcSize.x - 1.0);" +
            "  vec3 c = cL * w + cR * (1.0 - w);" +
            "  gl_FragColor = vec4(c.b, c.g, c.r, 1.0);" +
            "}";

    private static final String COPY_FS =
            "precision highp float; varying vec2 vTex; uniform sampler2D uTex;" +
            "void main(){ gl_FragColor = vec4(texture2D(uTex, vTex).bgr, 1.0); }";

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int aPos;
    private int uTex, uOutSize, uSrcSize, uHalfFov, uBlend, uLens0, uLens1;
    private int srcTex;
    private int fbo;
    private int fboTex;
    private int fw, fh, ow, oh;
    private ByteBuffer uploadBuf;
    private IntBuffer upload;
    private ByteBuffer readback;
    private FloatBuffer quad;

    private GpuStitcher() {
    }

    /** 创建并自检；失败返回 null，调用方回退 CPU/NEON。 */
    public static GpuStitcher createVerified() {
        GpuStitcher g = new GpuStitcher();
        try {
            if (!g.init()) {
                g.release();
                return null;
            }
            int fw = 256, fh = 128, ow = 128, oh = 64;
            int[] frame = new int[fw * fh];
            for (int i = 0; i < frame.length; i++) {
                int y = i / fw;
                int x = i % fw;
                frame[i] = 0xFF000000 | (((x * 7) & 0xFF) << 16)
                        | (((y * 13) & 0xFF) << 8) | ((x + y) & 0xFF);
            }
            int[] gpu = g.stitch(frame, fw, fh, ow, oh, null);
            int[] cpu = PanoStitcher.stitchEquirectFast(frame, fw, fh, ow, oh, null);
            if (gpu == null || cpu == null) {
                g.release();
                return null;
            }
            int bad = 0;
            int maxErr = 0;
            for (int i = 0; i < cpu.length; i++) {
                if ((cpu[i] >>> 24) != (gpu[i] >>> 24)) {
                    bad++;
                    continue;
                }
                for (int sh = 0; sh < 24; sh += 8) {
                    int d = Math.abs(((cpu[i] >> sh) & 0xFF) - ((gpu[i] >> sh) & 0xFF));
                    if (d > maxErr) {
                        maxErr = d;
                    }
                    if (d > 3) {
                        bad++;
                    }
                }
            }
            Log.i(TAG, "gpu selfTest maxErr=" + maxErr + " bad=" + bad + "/" + cpu.length);
            int mid = (oh / 2) * ow + ow / 2;
            int bottom = (oh - 1) * ow;
            long sum = 0;
            for (int i = 0; i < cpu.length; i++) {
                for (int sh = 0; sh < 24; sh += 8) {
                    sum += Math.abs(((cpu[i] >> sh) & 0xFF) - ((gpu[i] >> sh) & 0xFF));
                }
            }
            Log.i(TAG, String.format("gpu dbg p0 cpu=%08x gpu=%08x | mid cpu=%08x gpu=%08x"
                            + " | flip cpu=%08x gpu0=%08x | meanDiff=%.1f",
                    cpu[0], gpu[0], cpu[mid], gpu[mid], cpu[bottom], gpu[0],
                    sum / (double) (cpu.length * 3)));
            if (maxErr > 3 || bad > cpu.length / 50) {
                g.release();
                return null;
            }
            return g;
        } catch (Throwable t) {
            Log.w(TAG, "gpu stitch unavailable", t);
            g.release();
            return null;
        }
    }

    private boolean init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            return false;
        }
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            return false;
        }
        int[] cfgAttr = {
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, count, 0)
                || count[0] == 0) {
            return false;
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) {
            return false;
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            return false;
        }

        program = buildProgram(VS, FS);
        if (program == 0) {
            return false;
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uOutSize = GLES20.glGetUniformLocation(program, "uOutSize");
        uSrcSize = GLES20.glGetUniformLocation(program, "uSrcSize");
        uHalfFov = GLES20.glGetUniformLocation(program, "uHalfFov");
        uBlend = GLES20.glGetUniformLocation(program, "uBlend");
        uLens0 = GLES20.glGetUniformLocation(program, "uLens0");
        uLens1 = GLES20.glGetUniformLocation(program, "uLens1");

        float[] p = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(p.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(p).position(0);

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        srcTex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];
        return true;
    }

    /** 拼接一帧：frame 为整帧 ARGB（左右两个鱼眼），输出 ow×oh 的 ARGB。 */
    public int[] stitch(int[] frame, int width, int height, int outW, int outH, int[] reuse) {
        if (program == 0 || frame == null || frame.length < width * height) {
            return null;
        }
        if (width != fw || height != fh) {
            fw = width;
            fh = height;
            uploadBuf = ByteBuffer.allocateDirect(fw * fh * 4).order(ByteOrder.nativeOrder());
            upload = uploadBuf.asIntBuffer();
            upload.put(frame, 0, fw * fh).position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, uploadBuf);
        } else {
            upload.clear();
            upload.put(frame, 0, fw * fh).position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, fw, fh,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, uploadBuf);
        }

        if (outW != ow || outH != oh) {
            ow = outW;
            oh = outH;
            if (fboTex != 0) {
                GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
                fboTex = 0;
            }
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            fboTex = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, ow, oh, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                    != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "gpu fbo incomplete " + ow + "x" + oh);
                return null;
            }
            readback = ByteBuffer.allocateDirect(ow * oh * 4).order(ByteOrder.nativeOrder());
        }

        int[] out = (reuse != null && reuse.length == ow * oh) ? reuse : new int[ow * oh];
        float[][] lens = PanoStitcher.lensParams(fw);
        float halfFov = (float) Math.toRadians(PanoStitcher.FOV_DEG / 2.0);
        float blend = PanoStitcher.blendRad();

        GLES20.glViewport(0, 0, ow, oh);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform2f(uOutSize, ow, oh);
        GLES20.glUniform2f(uSrcSize, fw, fh);
        GLES20.glUniform1f(uHalfFov, halfFov);
        GLES20.glUniform1f(uBlend, blend);
        GLES20.glUniform4f(uLens0, lens[0][0], lens[0][1], lens[0][2],
                (float) Math.toRadians(lens[0][3]));
        GLES20.glUniform4f(uLens1, lens[1][0], lens[1][1], lens[1][2],
                (float) Math.toRadians(lens[1][3]));

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);

        GLES20.glFinish();
        readback.clear();
        GLES20.glReadPixels(0, 0, ow, oh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback);
        readback.position(0);
        readback.asIntBuffer().get(out);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return out;
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
        int[] linked = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            Log.w(TAG, "gpu program link failed: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "gpu shader compile failed: " + GLES20.glGetShaderInfoLog(s));
        }
        return s;
    }

    public void release() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (fboTex != 0) {
                    GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
                }
                if (srcTex != 0) {
                    GLES20.glDeleteTextures(1, new int[]{srcTex}, 0);
                }
                if (fbo != 0) {
                    GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                }
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglTerminate(display);
            }
        } catch (Throwable ignored) {
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
        program = 0;
        fboTex = 0;
        srcTex = 0;
        fbo = 0;
        ow = oh = fw = fh = 0;
    }
}
