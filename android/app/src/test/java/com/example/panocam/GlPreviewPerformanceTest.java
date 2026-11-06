package com.example.panocam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, shadows = {
        GlPreviewPerformanceTest.Egl.class, GlPreviewPerformanceTest.Gl.class,
        GlPreviewPerformanceTest.Texture.class})
public class GlPreviewPerformanceTest {
    private static final List<String> events = new ArrayList<>();
    private static final List<Float> rolls = new ArrayList<>();
    private static final Map<Integer, String> uniformNames = new HashMap<>();
    private static int queries;
    private static int id;
    private GlPreview view;

    @Before public void setup() throws Exception {
        events.clear(); rolls.clear(); uniformNames.clear(); queries = 0; id = 1;
        view = new GlPreview(RuntimeEnvironment.getApplication());
        set(view, "windowSt", new SurfaceTexture(1));
        assertEquals(true, call(view, "initEgl"));
        set(view, "stitchOk", true);
        view.setCameraSize(256, 128);
        events.clear(); queries = 0;
    }

    @Test public void steadyPreviewDoesNotResolveProgramLocationsAgain() throws Exception {
        view.setPreviewEnabled(true);
        render();
        queries = 0;
        events.clear();
        render();
        assertEquals(0, queries);
    }

    @Test public void synchronousReadbackDoesNotIssueRedundantGlFinish() throws Exception {
        view.requestPhoto(128);
        render();
        assertTrue(events.contains("read"));
        assertFalse(events.contains("finish"));
    }

    @Test public void hiddenRawViewAndBackpressuredPreviewSkipDrawAndReadback() throws Exception {
        invokeApi("setRawDisplayEnabled", new Class<?>[]{boolean.class}, false);
        invokeApi("setPreviewEligibility", new Class<?>[]{BooleanSupplier.class},
                (BooleanSupplier) () -> false);
        view.setPreviewEnabled(true);
        render();
        assertFalse(events.contains("draw"));
        assertFalse(events.contains("read"));
        assertFalse(events.contains("swap"));
    }

    @Test public void photoUsesRollCapturedAtShutterTime() throws Exception {
        float[] applied = {Float.NaN};
        view.setListener(new GlPreview.Listener() {
            public void onCameraTexture(SurfaceTexture texture) { }
            public void onCameraLost() { }
            public void onPreviewFrame(int[] eq, int w, int h) { }
            public void onPhotoFrame(int[] eq, int w, int h) { }
            public void onPhotoFrame(int[] eq, int w, int h, float roll) { applied[0] = roll; }
            public void onGlError(String message) { }
        });
        invokeApi("requestPhoto", new Class<?>[]{int.class, float.class}, 128, 23f);
        view.setRoll(-50f);
        render();
        assertEquals(23f, applied[0], 0f);
        assertTrue(rolls.contains((float) Math.toRadians(23f)));
    }

    private void render() throws Exception {
        set(view, "cameraFrameReady", true);
        call(view, "renderFrame");
    }

    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try { return method.invoke(target); }
        catch (InvocationTargetException failure) { throw new AssertionError(failure.getCause()); }
    }

    private Object invokeApi(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = GlPreview.class.getMethod(name, parameterTypes);
        return method.invoke(view, args);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Implements(EGL14.class) public static class Egl {
        @Implementation protected static EGLDisplay eglGetDisplay(int id) { return EglObjects.display(); }
        @Implementation protected static boolean eglInitialize(EGLDisplay d, int[] a, int o, int[] b, int p) { return true; }
        @Implementation protected static boolean eglChooseConfig(EGLDisplay d, int[] a, int o, EGLConfig[] c, int p, int n, int[] count, int q) {
            c[p] = EglObjects.config(); count[q] = 1; return true;
        }
        @Implementation protected static EGLContext eglCreateContext(EGLDisplay d, EGLConfig c, EGLContext s, int[] a, int o) { return EglObjects.context(); }
        @Implementation protected static EGLSurface eglCreateWindowSurface(EGLDisplay d, EGLConfig c, Object w, int[] a, int o) { return EglObjects.surface(); }
        @Implementation protected static boolean eglMakeCurrent(EGLDisplay d, EGLSurface a, EGLSurface b, EGLContext c) { return true; }
        @Implementation protected static boolean eglSwapBuffers(EGLDisplay d, EGLSurface s) { events.add("swap"); return true; }
    }

    static final class EglObjects {
        static <T> T create(Class<T> type) {
            try {
                java.lang.reflect.Constructor<T> ctor = type.getDeclaredConstructor(long.class);
                ctor.setAccessible(true);
                return ctor.newInstance(42L);
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
        static EGLDisplay display() { return create(EGLDisplay.class); }
        static EGLConfig config() { return create(EGLConfig.class); }
        static EGLContext context() { return create(EGLContext.class); }
        static EGLSurface surface() { return create(EGLSurface.class); }
    }

    @Implements(GLES20.class) public static class Gl {
        @Implementation protected static int glCreateProgram() { return id++; }
        @Implementation protected static int glCreateShader(int type) { return id++; }
        @Implementation protected static void glGetProgramiv(int p, int what, int[] result, int o) { result[o] = 1; }
        @Implementation protected static void glGetShaderiv(int p, int what, int[] result, int o) { result[o] = 1; }
        @Implementation protected static int glGetUniformLocation(int p, String n) { queries++; int location = id++; uniformNames.put(location, n); return location; }
        @Implementation protected static int glGetAttribLocation(int p, String n) { queries++; return id++; }
        @Implementation protected static void glUniform1f(int location, float value) {
            if ("uRoll".equals(uniformNames.get(location))) rolls.add(value);
        }
        @Implementation protected static void glGenTextures(int n, int[] ids, int o) { for (int i = 0; i < n; i++) ids[o+i] = id++; }
        @Implementation protected static void glGenFramebuffers(int n, int[] ids, int o) { for (int i = 0; i < n; i++) ids[o+i] = id++; }
        @Implementation protected static int glCheckFramebufferStatus(int target) { return GLES20.GL_FRAMEBUFFER_COMPLETE; }
        @Implementation protected static void glDrawArrays(int mode, int first, int count) { events.add("draw"); }
        @Implementation protected static void glReadPixels(int x, int y, int w, int h, int f, int t, Buffer b) { events.add("read"); }
        @Implementation protected static void glFinish() { events.add("finish"); }
    }

    @Implements(SurfaceTexture.class) public static class Texture {
        @Implementation protected void __constructor__(int tex) { }
        @Implementation protected void setOnFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener l) { }
        @Implementation protected void updateTexImage() { }
        @Implementation protected void getTransformMatrix(float[] matrix) { matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1; }
        @Implementation protected long getTimestamp() { return 5_000_000_000L; }
    }
}
