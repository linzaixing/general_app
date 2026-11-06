package com.example.panocam;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 自带 EGL 的预览 + 拼接渲染器（替代「TextureView 显示相机帧 + getBitmap 回读」的老链路）。
 *
 * 相机帧直接进本类创建的 SurfaceTexture，全程在 GL 线程里：
 *   1. updateTexImage；
 *   2. 原始双鱼眼用着色器画到屏幕（不再有 Bitmap 回读）；
 *   3. 拼接后的 2:1 等距全景按需渲染：
 *      - 给网页预览：渲染进 FBO，只回读预览尺寸（默认 1536×768）；
 *      - 录制：直接画到 MediaCodec 的输入 Surface（零回读、CPU 零参与）。
 *
 * 拼接公式与 PanoStitcher 的 LUT / GpuStitcher 的着色器完全一致，并在首次初始化时
 * 用合成图与 CPU 结果逐像素自检，不一致就关掉 GPU 拼接（调用方回退 NEON）。
 */
public final class GlPreview extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "PanoPerf";
    /** EGL_RECORDABLE_ANDROID：录制必须，否则 MediaCodec 的输入 Surface 可能建不起来。 */
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final float HALF_FOV_RAD = (float) Math.toRadians(PanoStitcher.FOV_DEG / 2.0);
    private static final float BLEND_RAD = PanoStitcher.blendRad();

    public interface Listener {
        /** 相机可用的 SurfaceTexture（必须在相机线程里 setPreviewTexture）。 */
        void onCameraTexture(SurfaceTexture st);

        /** 预览表面失效，相机需要关掉。 */
        void onCameraLost();

        /** 拼接好的一帧等距全景（ARGB int[]，GL 线程回调）。消费完必须 releasePreview()。 */
        void onPreviewFrame(int[] eq, int ow, int oh);

        /** 拍照/手动拼接请求的结果（ARGB int[]，GL 线程回调，数组归调用方所有）。 */
        void onPhotoFrame(int[] eq, int ow, int oh);

        /** Pixels already include this shutter-time roll; callers must not rotate them again. */
        default void onPhotoFrame(int[] eq, int ow, int oh, float appliedRollDegrees) {
            onPhotoFrame(eq, ow, oh);
        }

        void onGlError(String msg);
    }

    // ---------------- 着色器 ----------------

    private static final String VS =
            "attribute vec2 aPos; attribute vec2 aTex; varying vec2 vTex;" +
            "void main(){ vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }";

    /** 原图显示/回读：uST 把 SurfaceTexture 坐标换算成图像坐标（含生产方的旋转/翻转）。 */
    private static final String FS_RAW =
            "__EXT__"
            + "precision highp float; varying vec2 vTex;"
            + "uniform __SAMPLER__ uSrc; uniform mat4 uST;"
            + "void main(){"
            + "  vec2 uv = (uST * vec4(vTex, 0.0, 1.0)).xy;"
            + "  vec4 c = texture2D(uSrc, uv);"
            + "  __RAWOUT__"
            + "}";

    /** 双鱼眼 -> 等距柱状；与 PanoStitcher LUT / GpuStitcher 同一套公式。 */
    private static final String FS_ERP =
            "__EXT__"
            + "precision highp float; varying vec2 vTex;"
            + "uniform __SAMPLER__ uSrc; uniform mat4 uST;"
            + "uniform vec2 uOutSize; uniform vec2 uSrcSize;"
            + "uniform float uHalfFov; uniform float uBlend;"
            + "uniform vec4 uLens0; uniform vec4 uLens1;"
            + "uniform float uRoll; uniform float uFlipY;"
            + "const float PI = 3.14159265359;"
            + "vec3 samp(vec2 uv, float x0, float x1) {"
            + "  if (uv.x < x0 || uv.y < 0.0 || uv.x > x1 || uv.y > uSrcSize.y - 1.0) return vec3(0.0);"
            + "  vec2 n = (uv + vec2(0.5)) / uSrcSize;"
            + "  n = (uST * vec4(n, 0.0, 1.0)).xy;"
            + "  return __FETCH__;"
            + "}"
            + "void main() {"
            + "  float px = vTex.x * uOutSize.x - 0.5;"
            + "  float ty = vTex.y;"
            + "  float py = (uFlipY > 0.5 ? ty : (1.0 - ty)) * uOutSize.y - 0.5;"
            + "  float lon = -PI + 2.0 * PI * px / uOutSize.x;"
            + "  float lat = PI * 0.5 - PI * py / (uOutSize.y - 1.0);"
            + "  float cl = cos(lat);"
            + "  float dx = cl * sin(lon);"
            + "  float dy = sin(lat);"
            + "  float dz = cl * cos(lon);"
            // 防抖：绕前方轴（z）旋转 uRoll，与 EquirectRecorder 的着色器同一语义
            + "  float cr = cos(uRoll), sr = sin(uRoll);"
            + "  float rx = dx * cr - dy * sr;"
            + "  float ry = dx * sr + dy * cr;"
            + "  float rz = dz;"
            + "  float halfW = uSrcSize.x * 0.5;"
            + "  float maxY = uSrcSize.y - 1.0;"
            + "  float th = acos(clamp(-rz, -1.0, 1.0));"
            + "  float phi = atan(ry, rx) + uLens0.w;"
            + "  float rr = uLens0.z * th / uHalfFov;"
            + "  vec2 uvL = vec2(uLens0.x + rr * cos(phi), uLens0.y + rr * sin(phi));"
            + "  if (uvL.x < 0.0 || uvL.y < 0.0 || uvL.x > halfW - 1.0 || uvL.y > maxY) {"
            + "    float rr2 = min(rr, uLens0.z * 0.99);"
            + "    uvL = vec2(uLens0.x + rr2 * cos(phi), uLens0.y + rr2 * sin(phi));"
            + "  }"
            + "  th = acos(clamp(rz, -1.0, 1.0));"
            + "  phi = atan(-ry, rx) + uLens1.w;"
            + "  rr = uLens1.z * th / uHalfFov;"
            + "  vec2 uvR = vec2(halfW + uLens1.x + rr * cos(phi), uLens1.y + rr * sin(phi));"
            + "  if (uvR.x < halfW || uvR.y < 0.0 || uvR.x > uSrcSize.x - 1.0 || uvR.y > maxY) {"
            + "    float rr2 = min(rr, uLens1.z * 0.99);"
            + "    uvR = vec2(halfW + uLens1.x + rr2 * cos(phi), uLens1.y + rr2 * sin(phi));"
            + "  }"
            + "  float w = clamp((-rz + uBlend) / (2.0 * uBlend), 0.0, 1.0);"
            + "  vec3 cL = samp(uvL, 0.0, halfW - 1.0);"
            + "  vec3 cR = samp(uvR, halfW, uSrcSize.x - 1.0);"
            + "  vec3 cc = cL * w + cR * (1.0 - w);"
            + "  __ERPOUT__"
            + "}";

    // ---------------- 状态 ----------------

    private Listener listener;

    /** 屏幕预览纹理（窗口表面，TextureView 自带的 SurfaceTexture）。 */
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface windowSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture windowSt;

    /** 相机纹理（GL_TEXTURE_EXTERNAL_OES）。 */
    private int cameraTex;
    private SurfaceTexture cameraSt;
    private volatile int camW, camH;

    private EGLSurface recSurface = EGL14.EGL_NO_SURFACE;
    private volatile EquirectRecorder recorder;
    private int recW, recH;

    private int fbo, fboTex, fboW, fboH;
    private ByteBuffer readback;
    private FloatBuffer fullPos, fullTex, fitPos, fitTex, flipTex;
    private float[] stMat = new float[16];

    private final Object frameLock = new Object();
    private boolean framePending;
    private volatile boolean cameraFrameReady;
    private final Object taskLock = new Object();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private volatile boolean running;
    private Thread glThread;

    private volatile int viewW = 1, viewH = 1;
    private volatile boolean previewEnabled;
    private volatile boolean rawDisplayEnabled = true;
    private volatile BooleanSupplier previewEligibility = () -> true;
    private volatile int previewIntervalMs;
    private volatile long lastPreviewAtNs;
    private volatile boolean previewBusy;
    private final Object photoLock = new Object();
    private int pendingPhotoW;
    private float pendingPhotoRoll;
    private volatile int previewW = 1536;
    private volatile boolean stitchOk;
    private volatile float rollDeg;
    private volatile long lastFrameAt;
    private int previewTick;
    private int recSwaps;
    private boolean stLogged;
    private int ratioW = 2, ratioH = 1;

    private int progRawShow, progRawRead, progErpRec, progErpRead, progSelf;
    private final Map<Integer, Locations> locations = new HashMap<>();
    private PanoStitcher.Lenses lenses;
    private int lensSourceW;
    private int[] previewBuf;
    private FloatBuffer showPos;
    private int showPosW, showPosH;

    private static final class Locations {
        final int pos, tex, src, st, srcSize, outSize, fov, blend, lens0, lens1, roll, flip;
        Locations(int program, boolean erp) {
            pos = GLES20.glGetAttribLocation(program, "aPos");
            tex = GLES20.glGetAttribLocation(program, "aTex");
            src = GLES20.glGetUniformLocation(program, "uSrc");
            st = GLES20.glGetUniformLocation(program, "uST");
            srcSize = erp ? GLES20.glGetUniformLocation(program, "uSrcSize") : -1;
            outSize = erp ? GLES20.glGetUniformLocation(program, "uOutSize") : -1;
            fov = erp ? GLES20.glGetUniformLocation(program, "uHalfFov") : -1;
            blend = erp ? GLES20.glGetUniformLocation(program, "uBlend") : -1;
            lens0 = erp ? GLES20.glGetUniformLocation(program, "uLens0") : -1;
            lens1 = erp ? GLES20.glGetUniformLocation(program, "uLens1") : -1;
            roll = erp ? GLES20.glGetUniformLocation(program, "uRoll") : -1;
            flip = erp ? GLES20.glGetUniformLocation(program, "uFlipY") : -1;
        }
    }

    public GlPreview(Context context) {
        super(context);
        setSurfaceTextureListener(this);
        setOpaque(true);
    }

    public void setListener(Listener l) {
        listener = l;
        SurfaceTexture st = cameraSt;
        if (st != null) {
            // 预览表面比 listener 先就绪时补一次回调
            l.onCameraTexture(st);
        }
    }

    /** 相机打开后告知实际采集分辨率（拼接公式按帧宽缩放镜头参数）。 */
    public void setCameraSize(int w, int h) {
        if (w > 0 && h > 0) {
            camW = w;
            camH = h;
        }
    }

    /** 页面「画质」下发的预览拼接宽度。 */
    public void setSizes(int previewW) {
        this.previewW = Math.max(512, Math.min(1536, previewW)) & ~1;
    }

    public void setRoll(float deg) {
        rollDeg = deg;
    }

    /** 全景页可见时才需要往网页推帧。 */
    public void setPreviewEnabled(boolean on) {
        previewEnabled = on;
        if (on) {
            requestFrame();
        }
    }

    /** Keep latching camera frames but skip the covered raw-view draw and window swap. */
    public void setRawDisplayEnabled(boolean on) {
        rawDisplayEnabled = on;
        requestFrame();
    }

    /** Checked before preview stitching/readback so backpressure avoids the expensive GPU work. */
    public void setPreviewEligibility(BooleanSupplier eligibility) {
        previewEligibility = eligibility == null ? () -> true : eligibility;
    }

    /** Rate-limit only page previews; recording and camera texture latching remain uncapped. */
    public void setPreviewIntervalMs(int intervalMs) {
        previewIntervalMs = Math.max(0, Math.min(1000, intervalMs));
        requestFrame();
    }

    /** 网页消费完一帧后调用，允许渲染下一帧预览。 */
    public void releasePreview() {
        previewBusy = false;
        requestFrame();
    }

    /** 录制开关：传入 recorder 后，每一帧都会直出到编码器 Surface。 */
    public void setRecorder(EquirectRecorder rec) {
        recorder = rec;
        requestFrame();
    }

    /** 请求拍一帧保存尺寸的等距全景。 */
    public void requestPhoto(int outW) {
        requestPhoto(outW, 0f);
    }

    /** Snapshot stabilization at the shutter so GPU output avoids a later CPU rotation. */
    public void requestPhoto(int outW, float rollDegrees) {
        synchronized (photoLock) {
            pendingPhotoW = outW;
            pendingPhotoRoll = rollDegrees;
        }
        requestFrame();
    }

    public boolean isStitchOk() {
        return stitchOk;
    }

    /** 等 GL 线程把投递任务做完（录制收尾前保证编码器 Surface 已解绑）。 */
    public void flush() {
        postSync(() -> {
        });
    }

    /** 按固定宽高比显示（UVC 输出是 2:1 的双鱼眼）。 */
    public void setAspect(int w, int h) {
        if (w > 0 && h > 0 && (w != ratioW || h != ratioH)) {
            ratioW = w;
            ratioH = h;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        float ratio = (float) ratioW / ratioH;
        if (width > 0 && height > 0) {
            if ((float) width / height > ratio) {
                width = Math.round(height * ratio);
            } else {
                height = Math.round(width / ratio);
            }
        }
        setMeasuredDimension(width, height);
    }

    public long lastFrameAt() {
        return lastFrameAt;
    }

    // ---------------- TextureView 生命周期 ----------------

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        windowSt = st;
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
        running = true;
        framePending = true;
        glThread = new Thread(this::loop, "pano-gl");
        glThread.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
        requestFrame();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        running = false;
        requestFrame();
        Thread t = glThread;
        glThread = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (Throwable ignored) {
            }
        }
        // 相机纹理与上下文一起销毁，这里只释放显示用的 SurfaceTexture（返回 false 表示自己释放）
        try {
            st.release();
        } catch (Throwable ignored) {
        }
        windowSt = null;
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    // ---------------- 线程调度 ----------------

    private void requestFrame() {
        synchronized (frameLock) {
            framePending = true;
            frameLock.notifyAll();
        }
    }

    /** 把一个任务投到 GL 线程同步执行（只能在非 GL 线程调用）。 */
    private void postSync(Runnable r) {
        if (Thread.currentThread() == glThread) {
            r.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (taskLock) {
            tasks.add(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "gl task failed", t);
                } finally {
                    latch.countDown();
                }
            });
            taskLock.notifyAll();
        }
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    private void loop() {
        boolean ok = false;
        try {
            ok = initEgl();
        } catch (Throwable t) {
            Log.w(TAG, "gl preview init failed", t);
        }
        if (!ok) {
            final String msg = "GPU 预览初始化失败";
            reportError(msg);
        }
        while (running) {
            synchronized (frameLock) {
                if (!framePending) {
                    try {
                        frameLock.wait(50);
                    } catch (Throwable ignored) {
                    }
                }
                framePending = false;
            }
            drainTasks();
            if (!running) {
                break;
            }
            if (!ok) {
                sleep(50);
                continue;
            }
            try {
                renderFrame();
            } catch (Throwable t) {
                Log.w(TAG, "render frame failed", t);
                reportError("渲染异常: " + t);
                sleep(50);
            }
        }
        drainTasks();
        releaseGl();
    }

    private void drainTasks() {
        for (; ; ) {
            Runnable r;
            synchronized (taskLock) {
                r = tasks.poll();
            }
            if (r == null) {
                return;
            }
            try {
                r.run();
            } catch (Throwable t) {
                Log.w(TAG, "gl task failed", t);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }

    private void reportError(String msg) {
        Log.w(TAG, msg);
        Listener l = listener;
        if (l != null) {
            l.onGlError(msg);
        }
    }

    // ---------------- EGL ----------------

    private boolean initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            return false;
        }
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
            return false;
        }
        int[] attrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        int[] recordable = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] n = new int[1];
        // 先试普通配置：实测 Adreno 上用 RECORDABLE 配置给 MediaCodec 输入 Surface 建窗口表面时，
        // 只有第一帧能送进编码器；普通配置在这台机器上是老的 push() 路径验证过可用的
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] == 0) {
            if (!EGL14.eglChooseConfig(display, recordable, 0, configs, 0, 1, n, 0) || n[0] == 0) {
                return false;
            }
        }
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            return false;
        }
        SurfaceTexture ws = windowSt;
        if (ws == null) {
            return false;
        }
        windowSurface = EGL14.eglCreateWindowSurface(display, config, ws,
                new int[]{EGL14.EGL_NONE}, 0);
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "gl window surface failed");
            return false;
        }
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            return false;
        }

        float[] q = {-1, -1, 1, -1, -1, 1, 1, 1};
        fullPos = toBuffer(q);
        fullTex = toBuffer(new float[]{0, 0, 1, 0, 0, 1, 1, 1});
        flipTex = toBuffer(new float[]{0, 1, 1, 1, 0, 0, 1, 0});
        fitTex = toBuffer(new float[]{0, 1, 1, 1, 0, 0, 1, 0});
        fitPos = toBuffer(q);

        progRawShow = buildProgram(FS_RAW
                .replace("__EXT__", "#extension GL_OES_EGL_image_external : require\n")
                .replace("__SAMPLER__", "samplerExternalOES")
                .replace("__RAWOUT__", "gl_FragColor = c;"));
        progRawRead = buildProgram(FS_RAW
                .replace("__EXT__", "#extension GL_OES_EGL_image_external : require\n")
                .replace("__SAMPLER__", "samplerExternalOES")
                .replace("__RAWOUT__", "gl_FragColor = vec4(c.b, c.g, c.r, c.a);"));
        progErpRec = buildProgram(erpSource(true, false, false));
        progErpRead = buildProgram(erpSource(true, false, true));
        if (progRawShow == 0 || progRawRead == 0 || progErpRec == 0 || progErpRead == 0) {
            return false;
        }
        locations.put(progRawShow, new Locations(progRawShow, false));
        locations.put(progRawRead, new Locations(progRawRead, false));
        locations.put(progErpRec, new Locations(progErpRec, true));
        locations.put(progErpRead, new Locations(progErpRead, true));

        int[] ids = new int[1];
        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];

        // 相机纹理 + SurfaceTexture（必须在当前上下文里创建）
        GLES20.glGenTextures(1, ids, 0);
        cameraTex = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        cameraSt = new SurfaceTexture(cameraTex);
        cameraSt.setOnFrameAvailableListener(st -> {
            cameraFrameReady = true;
            requestFrame();
        });

        stitchOk = selfTest();
        Log.i(TAG, "gl ready stitch=" + (stitchOk ? "gpu" : "cpu"));
        Listener l = listener;
        if (l != null) {
            l.onCameraTexture(cameraSt);
        }
        return true;
    }

    private static String erpSource(boolean external, boolean test, boolean readback) {
        String src = FS_ERP
                .replace("__EXT__", external
                        ? "#extension GL_OES_EGL_image_external : require\n" : "")
                .replace("__SAMPLER__", external ? "samplerExternalOES" : "sampler2D")
                // 实测上传的第 2 个鱼眼是 ARGB int[]（内存 BGR），外部纹理直接就是 RGB
                .replace("__FETCH__", external ? "vec3(texture2D(uSrc, n))"
                        : "vec3(texture2D(uSrc, n).bgr)")
                .replace("__ERPOUT__", readback
                        ? "gl_FragColor = vec4(cc.b, cc.g, cc.r, 1.0);"
                        : "gl_FragColor = vec4(cc, 1.0);");
        return src;
    }

    private static FloatBuffer toBuffer(float[] values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0);
        return b;
    }

    private static int buildProgram(String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, VS);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.w(TAG, "gl link failed: " + GLES20.glGetProgramInfoLog(p));
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
            Log.w(TAG, "gl compile failed: " + GLES20.glGetShaderInfoLog(s)
                    + " SRC: " + src.replace("\n", "|"));
        }
        return s;
    }

    // ---------------- 自检 ----------------

    /** 用合成图把 GPU 拼接和 CPU/NEON 结果逐像素对齐，避免公式写错把画面拼坏。 */
    private boolean selfTest() {
        try {
            progSelf = buildProgram(erpSource(false, true, true));
            if (progSelf == 0) {
                return false;
            }
            locations.put(progSelf, new Locations(progSelf, true));
            int fw = 256, fh = 128, ow = 128, oh = 64;
            int[] frame = new int[fw * fh];
            for (int i = 0; i < frame.length; i++) {
                int y = i / fw;
                int x = i % fw;
                frame[i] = 0xFF000000 | (((x * 7) & 0xFF) << 16)
                        | (((y * 13) & 0xFF) << 8) | ((x + y) & 0xFF);
            }
            ByteBuffer buf = ByteBuffer.allocateDirect(fw * fh * 4)
                    .order(ByteOrder.nativeOrder());
            buf.asIntBuffer().put(frame);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            int testTex = t[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, testTex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fw, fh, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);

            if (!ensureFbo(ow, oh)) {
                return false;
            }
            float[] saved = stMat.clone();
            stMat = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            try {
                drawErp(progSelf, fw, fh, 0f, ow, oh, 1f, testTex, true);
            } finally {
                stMat = saved;
            }
            int[] gpu = readFbo(ow, oh, null);
            GLES20.glDeleteTextures(1, t, 0);
            int[] cpu = PanoStitcher.stitchEquirectFast(frame, fw, fh, ow, oh, null);
            if (gpu == null || cpu == null) {
                return false;
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
            return maxErr <= 3 && bad <= cpu.length / 50;
        } catch (Throwable t) {
            Log.w(TAG, "gpu selfTest failed", t);
            return false;
        } finally {
            if (progSelf != 0) {
                locations.remove(progSelf);
                GLES20.glDeleteProgram(progSelf);
                progSelf = 0;
            }
        }
    }

    // ---------------- 渲染 ----------------

    private void renderFrame() {
        // 只有真正来了新相机帧才渲染/编码，否则重复渲染同一帧会白占 GPU 和编码器
        if (!cameraFrameReady) {
            return;
        }
        cameraFrameReady = false;
        if (!previewing()) {
            return;
        }
        SurfaceTexture cs = cameraSt;
        if (cs == null) {
            return;
        }
        long t0 = Perf.t();
        cs.updateTexImage();
        cs.getTransformMatrix(stMat);
        Perf.end(Perf.GRAB, t0);
        lastFrameAt = System.nanoTime();
        if (!stLogged) {
            stLogged = true;
            Log.i(TAG, String.format("st matrix %.2f %.2f %.2f %.2f %.2f %.2f src=%dx%d",
                    stMat[0], stMat[1], stMat[4], stMat[5], stMat[12], stMat[13], camW, camH));
        }

        if (recorder == null && recSurface != EGL14.EGL_NO_SURFACE) {
            releaseRecSurface();
        }

        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) {
            return;
        }
        if (rawDisplayEnabled) {
            drawRawShow();
            EGL14.eglSwapBuffers(display, windowSurface);
        }

        EquirectRecorder rec = recorder;
        if (rec != null && rec.isActive()) {
            if (recSurface == EGL14.EGL_NO_SURFACE || recW != rec.width()) {
                createRecSurface(rec);
            }
            if (recSurface != EGL14.EGL_NO_SURFACE) {
                // 关键：每帧都要把编码器 Surface 设为 current，否则绘制还在往屏幕表面画
                if (!EGL14.eglMakeCurrent(display, recSurface, recSurface, context)) {
                    Log.w(TAG, "rec makeCurrent failed err=" + EGL14.eglGetError());
                    releaseRecSurface();
                    EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context);
                    return;
                }
                long g = Perf.t();
                drawErp(progErpRec, camW, camH, (float) Math.toRadians(rollDeg),
                        recW, recH, 0f, cameraTex, false);
                Perf.end(Perf.GPU, g);
                EGLExt.eglPresentationTimeANDROID(display, recSurface, System.nanoTime());
                boolean swapped = EGL14.eglSwapBuffers(display, recSurface);
                Perf.recFrameTick();
                if (recSwaps < 5) {
                    recSwaps++;
                    Log.i(TAG, "rec swap " + recSwaps + " ok=" + swapped + " err="
                            + EGL14.eglGetError() + " " + recW + "x" + recH);
                }
                EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context);
            }
        }

        int photoW;
        float photoRoll;
        synchronized (photoLock) {
            photoW = pendingPhotoW;
            photoRoll = pendingPhotoRoll;
            pendingPhotoW = 0;
        }
        if (photoW > 0) {
            takePhoto(photoW, photoRoll);
        }

        // 录制时预览每 4 帧推一次，剩下的算力留给编码直出
        int intervalMs = previewIntervalMs;
        long previewNow = System.nanoTime();
        boolean previewDue = (recorder == null || (previewTick++ & 3) == 0)
                && (intervalMs == 0 || previewNow - lastPreviewAtNs >= intervalMs * 1_000_000L);
        if (previewEnabled && !previewBusy && stitchOk && camW > 0 && previewDue
                && previewEligibility.getAsBoolean()) {
            int ow = previewW & ~1;
            int oh = ow / 2;
            if (ensureFbo(ow, oh)) {
                long g = Perf.t();
                drawErp(progErpRead, camW, camH, 0f, ow, oh, 1f, cameraTex, false);
                Perf.end(Perf.GPU, g);
                long r = Perf.t();
                int[] eq = readFbo(ow, oh, previewBuf);
                Perf.end(Perf.READ, r);
                if (eq != null) {
                    previewBuf = eq;
                    previewBusy = true;
                    lastPreviewAtNs = System.nanoTime();
                    Listener l = listener;
                    if (l != null) {
                        try {
                            l.onPreviewFrame(eq, ow, oh);
                        } catch (Throwable t) {
                            Log.w(TAG, "preview listener failed", t);
                            previewBusy = false;
                        }
                    } else {
                        previewBusy = false;
                    }
                }
            }
        }
    }

    private boolean previewing() {
        return camW > 0 && camH > 0;
    }

    private void drawRawShow() {
        int vw = viewW, vh = viewH;
        if (vw <= 0 || vh <= 0) {
            return;
        }
        float imgA = camW > 0 && camH > 0 ? (float) camW / camH : 2f;
        float viewA = (float) vw / vh;
        float hw = 1f, hh = 1f;
        if (viewA > imgA) {
            hw = imgA / viewA;
        } else {
            hh = viewA / imgA;
        }
        float[] q = {-hw, -hh, hw, -hh, -hw, hh, hw, hh};
        if (showPos == null || showPosW != vw || showPosH != vh) {
            showPos = ByteBuffer.allocateDirect(q.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            showPos.put(q).position(0);
            showPosW = vw;
            showPosH = vh;
        }

        GLES20.glViewport(0, 0, vw, vh);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progRawShow);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex);
        Locations loc = locations.get(progRawShow);
        GLES20.glUniform1i(loc.src, 0);
        GLES20.glUniformMatrix4fv(loc.st, 1, false, stMat, 0);
        bindQuad(progRawShow, showPos, fitTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void bindQuad(int program, FloatBuffer pos, FloatBuffer tex) {
        Locations loc = locations.get(program);
        int aPos = loc.pos;
        int aTex = loc.tex;
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex);
    }

    /**
     * 渲染一帧等距全景。
     * flipY=1 用于 FBO 回读（帧缓冲第 0 行是图顶部）；flipY=0 用于编码器（NDC 顶部是视频顶部）。
     */
    private void drawErp(int program, int srcW, int srcH, float rollRad,
                         int ow, int oh, float flipY, int srcTex, boolean is2d) {
        if (program == 0 || srcW <= 0 || srcH <= 0) {
            return;
        }
        if (lenses == null || lensSourceW != srcW) {
            lenses = PanoStitcher.cachedLenses(srcW);
            lensSourceW = srcW;
        }
        PanoStitcher.Lenses lens = lenses;
        Locations loc = locations.get(program);

        GLES20.glUseProgram(program);
        GLES20.glViewport(0, 0, ow, oh);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(is2d ? GLES20.GL_TEXTURE_2D
                : GLES11Ext.GL_TEXTURE_EXTERNAL_OES, srcTex);
        GLES20.glUniform1i(loc.src, 0);
        GLES20.glUniformMatrix4fv(loc.st, 1, false, stMat, 0);
        GLES20.glUniform2f(loc.outSize, ow, oh);
        GLES20.glUniform2f(loc.srcSize, srcW, srcH);
        GLES20.glUniform1f(loc.fov, HALF_FOV_RAD);
        GLES20.glUniform1f(loc.blend, BLEND_RAD);
        GLES20.glUniform4f(loc.lens0, lens.cx0, lens.cy0, lens.r0, lens.roll0);
        GLES20.glUniform4f(loc.lens1, lens.cx1, lens.cy1, lens.r1, lens.roll1);
        GLES20.glUniform1f(loc.roll, rollRad);
        GLES20.glUniform1f(loc.flip, flipY);
        bindQuad(program, fullPos, fullTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private boolean ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) {
            return false;
        }
        if (fboTex != 0 && w == fboW && h == fboH) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            return true;
        }
        if (fboTex != 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTex}, 0);
            fboTex = 0;
        }
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        fboTex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
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
            Log.w(TAG, "fbo incomplete " + w + "x" + h);
            fboW = fboH = 0;
            return false;
        }
        fboW = w;
        fboH = h;
        readback = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        return true;
    }

    /** 从当前 FBO 回读成 ARGB int[]（第 0 行是画面顶部）；尺寸匹配时复用 reuse。 */
    private int[] readFbo(int w, int h, int[] reuse) {
        if (readback == null || readback.capacity() < w * h * 4) {
            readback = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        }
        // GLES2 glReadPixels is synchronous and waits for the pixels it reads.
        readback.clear();
        readback.position(0);
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback);
        int[] out = (reuse != null && reuse.length == w * h) ? reuse : new int[w * h];
        readback.position(0);
        readback.asIntBuffer().get(out);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return out;
    }

    private void takePhoto(int outW, float rollDegrees) {
        int ow = outW & ~1;
        int oh = ow / 2;
        if (!stitchOk || camW <= 0 || !ensureFbo(ow, oh)) {
            return;
        }
        long g = Perf.t();
        drawErp(progErpRead, camW, camH, (float) Math.toRadians(rollDegrees),
                ow, oh, 1f, cameraTex, false);
        Perf.end(Perf.GPU, g);
        long r = Perf.t();
        int[] eq = readFbo(ow, oh, null);
        Perf.end(Perf.READ, r);
        if (eq != null) {
            Listener l = listener;
            if (l != null) {
                l.onPhotoFrame(eq, ow, oh, rollDegrees);
            }
        }
    }

    /** CPU 回退用：把原始相机帧回读到 int[]（预览尺寸），只能在非 GL 线程调用。 */
    public int[] grabRaw(int w, int h) {
        final int[] res = new int[w * h];
        final boolean[] ok = new boolean[1];
        postSync(() -> {
            if (!ensureFbo(w, h)) {
                return;
            }
            drawRawRead(w, h);
            int[] px = readFbo(w, h, null);
            if (px != null) {
                System.arraycopy(px, 0, res, 0, px.length);
                ok[0] = true;
            }
        });
        return ok[0] ? res : null;
    }

    private void drawRawRead(int w, int h) {
        GLES20.glViewport(0, 0, w, h);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progRawRead);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex);
        Locations loc = locations.get(progRawRead);
        GLES20.glUniform1i(loc.src, 0);
        GLES20.glUniformMatrix4fv(loc.st, 1, false, stMat, 0);
        bindQuad(progRawRead, fullPos, fullTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void createRecSurface(EquirectRecorder rec) {
        releaseRecSurface();
        Surface s = rec.inputSurface();
        if (s == null) {
            return;
        }
        EGLSurface es = EGL14.eglCreateWindowSurface(display, config, s,
                new int[]{EGL14.EGL_NONE}, 0);
        if (es == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "rec surface failed");
            return;
        }
        if (!EGL14.eglMakeCurrent(display, es, es, context)) {
            Log.w(TAG, "rec surface makeCurrent failed");
            EGL14.eglDestroySurface(display, es);
            return;
        }
        recSurface = es;
        recW = rec.width();
        recH = recW / 2;
    }

    private void releaseRecSurface() {
        if (recSurface != EGL14.EGL_NO_SURFACE) {
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context);
            }
            EGL14.eglDestroySurface(display, recSurface);
        }
        recSurface = EGL14.EGL_NO_SURFACE;
        recW = recH = 0;
    }

    private void releaseGl() {
        try {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                releaseRecSurface();
                // 相机纹理由相机线程自己停；这里只把引用清空，避免相机还在写时释放 ST 导致崩溃
                cameraSt = null;
                if (windowSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, windowSurface);
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglTerminate(display);
            }
        } catch (Throwable t) {
            Log.w(TAG, "release gl failed", t);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        windowSurface = EGL14.EGL_NO_SURFACE;
        recSurface = EGL14.EGL_NO_SURFACE;
        fbo = fboTex = 0;
        fboW = fboH = 0;
        readback = null;
        stitchOk = false;
        locations.clear();
        lenses = null;
        lensSourceW = 0;
        Listener l = listener;
        if (l != null) {
            l.onCameraLost();
        }
    }
}
