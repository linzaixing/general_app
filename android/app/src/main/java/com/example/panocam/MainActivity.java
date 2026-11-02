package com.example.panocam;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

/**
 * 直接使用 libuvc(UVCCamera) 打开全景相机，不经过 AUSBC 的相机状态机。
 * AUSBC 的 ICameraStrategy 在“USB 授权后才拿到 control block”这个时序下会静默失败，
 * 并在重复 startPreview 时 stop/start 互相踩踏，因此这里自己管理 USBMonitor + UVCCamera。
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    /** 常见 UVC 输出尺寸；优先挑最高分辨率，预览拼接会降采样，录制/拍照用原生分辨率。 */
    private static final int[][] CANDIDATES = {
            {2560, 1280}, {1920, 960}, {1280, 640}, {1280, 720}, {640, 480}, {3008, 1504}
    };
    /** 预览拼接分辨率（网页端显示用，可以低一些）。 */
    private static final int EQ_W = 1024;
    private static final int EQ_H = 512;
    /** 预览回读尺寸（2:1，与双鱼眼画面一致）。 */
    private static final int CAPTURE_W = 1280;
    private static final int CAPTURE_H = 640;
    /** 录制/拍照的原图上限：源不超过 2560 宽，输出不超过 2048x1024（2:1）。 */
    private static final int HI_SRC_MAX_W = 2560;
    private static final int HI_OUT_MAX_W = 2048;
    private static final int VIEW_W = 640;
    private static final int VIEW_H = 360;
    private static final int PLANET_SIZE = 512;
    /** 与网页端共用同一份页面（构建时由 Gradle 从仓库根目录同步进 assets）。 */
    private static final String WEB_PAGE = "file:///android_asset/pano_demo.html";
    /** 自己申请 USB 权限用的 action（libuvc 自带的 PendingIntent 是 IMMUTABLE，系统写不进结果）。 */
    private static final String ACTION_USB_PERMISSION = "com.example.panocam.action.USB_PERMISSION";
    private static final int REQ_CAMERA = 1001;

    /** 手势阈值：绕重力轴转过 90° 以上再回正 = 录制开关；快速上仰超过 35° 再下俯到 -25° = 拍照 */
    private static final float SPIN_ARM_DEG = 95f;
    private static final float SPIN_BACK_DEG = 14f;
    private static final long SPIN_TIMEOUT_MS = 12000;
    private static final long SPIN_MIN_MS = 350;
    private static final long SPIN_COOLDOWN_MS = 1500;
    private static final float PHOTO_UP_DEG = 35f;
    private static final float PHOTO_DOWN_DEG = -25f;
    private static final long PHOTO_WINDOW_MS = 1200;
    private static final long PHOTO_COOLDOWN_MS = 2000;

    private FrameLayout root;
    private FrameLayout cameraContainer;
    private AspectTextureView cameraView;
    private TextView status;
    private TextView hint;
    private LinearLayout controls;
    private WebView web;
    private Button stitchBtn;
    private Button closeBtn;
    private final PanoHostBridge bridge = new PanoHostBridge();
    private boolean webLoaded;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService camExec = Executors.newSingleThreadExecutor();
    private final ExecutorService renderExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean renderBusy = new AtomicBoolean(false);
    private final AtomicBoolean stitchBusy = new AtomicBoolean(false);
    private volatile boolean renderAgain = false;

    private USBMonitor usbMonitor;
    private UsbManager usbManager;
    private PendingIntent permIntent;
    private boolean permReceiverRegistered;
    private long lastPermRequestAt;
    private long lastPermDeniedAt;
    private volatile UVCCamera uvcCamera;
    private volatile USBMonitor.UsbControlBlock ctrlBlock;
    private volatile SurfaceTexture surfaceTexture;
    private volatile boolean previewing;
    private volatile int previewW;
    private volatile int previewH;
    private long lastOpenAt;
    private long lastConnectAt;
    private String sizeInfo = "";

    private SensorManager sensorManager;
    private long lastGyroTs;
    private float gx, gy, gz, ax, ay, az;
    private float yaw, pitch;              // 全景视角（度）
    private float rollDeg;                 // 重力水平补偿（度）
    private long lastStatusAt;
    /** 实时拼接帧率（平滑后的估计），用于状态栏显示是否还有余量。 */
    private volatile double liveFps;
    private volatile double liveFrameMs;
    private long lastLiveFrameAt;
    private long lastFpsPushAt;
    private boolean gyroOn = false;

    // 手势：绕重力轴的累积朝向（连续值，可跨越 ±180°）
    private volatile boolean viewerVisible;
    private final float[] rotMat = new float[9];
    private float spinYaw = Float.NaN;
    private float spinBase;
    private boolean spinArmed;
    private long spinArmedAt;
    private long spinStillSince;
    private long lastSpinFireAt;
    private boolean photoArmed;
    private long photoArmedAt;
    private long lastPhotoAt;
    private volatile EquirectRecorder recorder;      // 录制中的 2:1 拼接原图编码器
    private Runnable pendingGesture;                 // 手势触发的延时动作

    private volatile int[] equirectPixels;
    private int[] captureBuf;
    private int[] captureBufHi;
    private int[] stitchBuf;
    private int[] stitchBufHi;
    private int eqW, eqH;
    private int viewMode = PanoStitcher.MODE_PERSPECTIVE;
    private float viewFov = 110f;
    private Bitmap renderBitmap;
    private boolean stitching;
    private long lastRenderAt;

    private String usbText = "USB: 未检测到";
    private String camText = "相机: 初始化";
    private String errText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            writeCrashLog(e);
            Log.e("PanoCam", "uncaught", e);
            android.os.Process.killProcess(android.os.Process.myPid());
        });

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        setContentView(buildRoot());
        ensureCameraPermission();

        cameraView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surfaceTexture = st;
                tryOpenCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceTexture = null;
                previewing = false;
                camExec.execute(MainActivity.this::closeCameraSync);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        permIntent = buildPermissionIntent();
        // 先注册授权结果接收器，再启动 USB 监听，避免 attach 后立刻申请权限时结果广播丢失
        try {
            registerPermReceiver();
        } catch (Throwable t) {
            errText = "权限监听失败: " + t;
        }

        try {
            usbMonitor = new USBMonitor(new UvcContext(this), usbListener);
            usbMonitor.register();
            Log.i("PanoCam", "USBMonitor registered");
        } catch (Throwable t) {
            Log.e("PanoCam", "usb monitor init failed", t);
            errText = "USB 初始化失败: " + t;
        }

        ui.postDelayed(watchdog, 1500);
        updateStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            registerPermReceiver();
        } catch (Throwable t) {
            errText = "权限监听失败: " + t;
        }
        try {
            if (usbMonitor != null && !usbMonitor.isRegistered()) {
                usbMonitor.register();
            }
        } catch (Throwable t) {
            errText = "USB 监听失败: " + t;
        }
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(watchdog);
        previewing = false;
        camExec.execute(this::closeCameraSync);
        try {
            if (permReceiverRegistered) {
                unregisterReceiver(permReceiver);
            }
        } catch (Throwable ignored) {
        }
        permReceiverRegistered = false;
        try {
            if (usbMonitor != null && usbMonitor.isRegistered()) {
                usbMonitor.unregister();
            }
        } catch (Throwable ignored) {
        }
        super.onStop();
    }

    // ---------------- USB 权限（自己申请，绕开 libuvc 的 IMMUTABLE PendingIntent） ----------------

    private PendingIntent buildPermissionIntent() {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setPackage(getPackageName());
        // Android 12+ 必须显式声明可变性；USB 权限结果需要系统把 extras 写进这个 Intent，
        // 所以必须用 FLAG_MUTABLE，否则回调里 granted 永远是 false（表现为"授权被拒绝"）。
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags);
    }

    private void registerPermReceiver() {
        if (permReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(permReceiver, filter);
        }
        permReceiverRegistered = true;
    }

    private final BroadcastReceiver permReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            if (device == null) {
                device = findUvcDevice();
            }
            final UsbDevice d = device;
            ui.post(() -> onPermissionResult(d, granted));
        }
    };

    private UsbDevice findUvcDevice() {
        if (usbManager == null) {
            return null;
        }
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isUvcDevice(d)) {
                return d;
            }
        }
        return null;
    }

    private void requestUsbPermission(UsbDevice device) {
        if (device == null || usbManager == null) {
            return;
        }
        if (usbManager.hasPermission(device)) {
            connectAuthorizedDevice(device);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPermRequestAt < 4000) {
            return;
        }
        // 用户刚拒绝过，先缓 15s，避免系统弹窗反复刷屏
        if (now - lastPermDeniedAt < 15000) {
            return;
        }
        lastPermRequestAt = now;
        usbText = "USB: 申请授权中…";
        camText = "相机: 等待 USB 授权";
        updateStatus();
        Log.i("PanoCam", "requestUsbPermission vid=" + hex(device.getVendorId())
                + " pid=" + hex(device.getProductId()));
        try {
            usbManager.requestPermission(device, permIntent);
        } catch (Throwable t) {
            errText = "请求 USB 权限失败: " + t;
            updateStatus();
        }
    }

    private void onPermissionResult(UsbDevice device, boolean granted) {
        Log.i("PanoCam", "onPermissionResult granted=" + granted + " device=" + device);
        if (granted) {
            usbText = "USB: 授权已通过";
            camText = "相机: 连接中";
            errText = "";
            updateStatus();
            connectAuthorizedDevice(device);
        } else {
            lastPermDeniedAt = System.currentTimeMillis();
            usbText = "USB: 授权被拒绝";
            camText = "相机: 无 USB 权限";
            errText = "请在弹出的系统窗口点“允许”；若没弹窗请拔插一次相机";
            updateStatus();
        }
    }

    /**
     * 已经拿到 USB 权限后，直接用 USBMonitor.openDevice 建立控制块。
     * 不走 USBMonitor.requestPermission：它内部用的是 IMMUTABLE PendingIntent，
     * Android 12+ 上系统写不进授权结果，会出现「已授权但仍卡在连接中」。
     */
    private void connectAuthorizedDevice(final UsbDevice device) {
        if (device == null || ctrlBlock != null || previewing || usbMonitor == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastConnectAt < 1500) {
            return;
        }
        lastConnectAt = now;
        camText = "相机: 打开 USB 连接";
        updateStatus();
        camExec.execute(() -> {
            try {
                USBMonitor.UsbControlBlock cb = usbMonitor.openDevice(device);
                if (cb == null) {
                    Log.e("PanoCam", "openDevice returned null");
                    ui.post(() -> {
                        camText = "相机: USB 连接失败";
                        errText = "openDevice 返回 null";
                        updateStatus();
                    });
                    return;
                }
                ctrlBlock = cb;
                Log.i("PanoCam", "openDevice ok: " + device.getDeviceName());
                ui.post(() -> {
                    camText = "相机: USB 已连接";
                    errText = "";
                    updateStatus();
                    tryOpenCamera();
                });
            } catch (Throwable t) {
                Log.e("PanoCam", "openDevice failed", t);
                ui.post(() -> {
                    camText = "相机: USB 打开失败";
                    errText = String.valueOf(t);
                    updateStatus();
                });
            }
        });
    }

    // ---------------- 相机权限（新安装首次启动必须主动申请） ----------------

    private boolean hasCameraPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureCameraPermission() {
        if (hasCameraPermission()) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        errText = "尚未授予相机权限，正在申请…";
        updateStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            errText = "";
            updateStatus();
            showCameraPermissionTip();
        } else {
            errText = "缺少相机权限，点上方状态栏可重新授权";
            updateStatus();
            showCameraPermissionDialog();
        }
    }

    /** 权限被拒时的引导：可直接再申请一次，或跳到系统设置里手动打开。 */
    private void showCameraPermissionDialog() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("PanoCam 需要「相机」权限才能读取 USB 全景相机的画面。\n"
                        + "请点击「去授权」，在弹窗中选择“允许”；若系统不再弹窗，请用「去设置」手动打开。")
                .setPositiveButton("去授权", (d, w) -> {
                    if (hasCameraPermission()) {
                        return;
                    }
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                })
                .setNeutralButton("去设置", (d, w) -> openAppSettings())
                .setNegativeButton("稍后", null)
                .show();
    }

    private void showCameraPermissionTip() {
        Toast.makeText(this, "相机权限已开启", Toast.LENGTH_SHORT).show();
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void writeCrashLog(Throwable e) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File f = new File(dir, "crash.log");
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(("---- " + System.currentTimeMillis() + " ----\n").getBytes());
            java.io.PrintWriter pw = new java.io.PrintWriter(fos);
            e.printStackTrace(pw);
            pw.flush();
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 相机 ----------------

    private final USBMonitor.OnDeviceConnectListener usbListener =
            new USBMonitor.OnDeviceConnectListener() {
                @Override
                public void onAttach(UsbDevice device) {
                    if (!isUvcDevice(device)) {
                        return;
                    }
                    ui.post(() -> {
                        usbText = "USB: 已识别 " + hex(device.getVendorId()) + ":" + hex(device.getProductId());
                        updateStatus();
                        requestUsbPermission(device);
                    });
                }

                @Override
                public void onDetach(UsbDevice device) {
                    ui.post(() -> handleUsbLost("USB: 已拔出"));
                }

                @Override
                public void onConnect(UsbDevice device, USBMonitor.UsbControlBlock cb, boolean hasPermission) {
                    Log.i("PanoCam", "onConnect perm=" + hasPermission + " cb=" + (cb != null));
                    if (!hasPermission || cb == null) {
                        ui.post(() -> {
                            camText = "相机: 无 USB 权限";
                            updateStatus();
                            requestUsbPermission(device);
                        });
                        return;
                    }
                    if (ctrlBlock == null) {
                        ctrlBlock = cb;
                    }
                    ui.post(() -> {
                        usbText = "USB: 已授权 " + hex(device.getVendorId()) + ":" + hex(device.getProductId());
                        camText = "相机: USB 已连接";
                        updateStatus();
                        tryOpenCamera();
                    });
                }

                @Override
                public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock cb) {
                    ui.post(() -> handleUsbLost("USB: 连接断开"));
                }

                @Override
                public void onCancel(UsbDevice device) {
                    ui.post(() -> {
                        camText = "相机: USB 授权被拒绝";
                        updateStatus();
                    });
                }
            };

    private static boolean isUvcDevice(UsbDevice d) {
        if (d.getDeviceClass() == 239) {
            return true;
        }
        int vid = d.getVendorId();
        if (vid == 0x2E1A || vid == 0x2E0A) {
            return true;
        }
        // 有些 UVC 相机的设备类为 0，只在接口上声明 Video Class
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    private void handleUsbLost(String text) {
        usbText = text;
        camText = "相机: 已断开";
        errText = "";
        ctrlBlock = null;
        previewing = false;
        updateStatus();
        camExec.execute(this::closeCameraSync);
    }

    /** 只有设备已授权 + 有可用预览表面时才会真正打开，避免无意义的重开。 */
    private void tryOpenCamera() {
        final USBMonitor.UsbControlBlock cb = ctrlBlock;
        final SurfaceTexture st = surfaceTexture;
        if (cb == null) {
            return;
        }
        if (st == null) {
            if (!"相机: 等待预览界面".equals(camText)) {
                camText = "相机: 等待预览界面";
                updateStatus();
            }
            return;
        }
        if (previewing) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastOpenAt < 1500) {
            return;
        }
        lastOpenAt = now;
        camText = "相机: 正在打开";
        updateStatus();
        camExec.execute(() -> openCameraSync(cb, st));
    }

    private void openCameraSync(USBMonitor.UsbControlBlock cb, SurfaceTexture st) {
        closeCameraSync();
        UVCCamera cam = new UVCCamera();
        try {
            cam.open(cb);
            List<Size> sizes = cam.getSupportedSizeList();
            StringBuilder sb = new StringBuilder("可选尺寸:");
            if (sizes != null) {
                for (Size s : sizes) {
                    sb.append(' ').append(s.width).append('x').append(s.height);
                }
            }
            sizeInfo = sb.toString();

            int[] wh = pickSize(sizes);
            int w = wh[0];
            int h = wh[1];
            try {
                cam.setPreviewSize(w, h, UVCCamera.FRAME_FORMAT_MJPEG);
            } catch (Throwable t) {
                cam.setPreviewSize(w, h);
            }
            cam.setPreviewTexture(st);
            cam.startPreview();

            uvcCamera = cam;
            previewing = true;
            previewW = w;
            previewH = h;
            ui.post(() -> {
                if (cameraView != null) {
                    cameraView.setAspect(w, h);
                }
                camText = "相机: 预览中 " + w + "x" + h;
                errText = "";
                updateStatus();
            });
        } catch (Throwable t) {
            try {
                cam.destroy();
            } catch (Throwable ignored) {
            }
            Log.e("PanoCam", "open camera failed", t);
            ui.post(() -> {
                camText = "相机: 打开失败";
                errText = String.valueOf(t);
                updateStatus();
            });
        }
    }

    private int[] pickSize(List<Size> sizes) {
        if (sizes != null && !sizes.isEmpty()) {
            for (int[] c : CANDIDATES) {
                for (Size s : sizes) {
                    if (s.width == c[0] && s.height == c[1]) {
                        return c;
                    }
                }
            }
            Size best = null;
            for (Size s : sizes) {
                if (s.width > s.height && s.width <= 2560
                        && Math.abs((float) s.width / s.height - 2f) < 0.03f) {
                    if (best == null || s.width > best.width) {
                        best = s;
                    }
                }
            }
            if (best != null) {
                return new int[]{best.width, best.height};
            }
            Size first = sizes.get(0);
            if (first.width > 0 && first.height > 0) {
                return new int[]{first.width, first.height};
            }
        }
        return new int[]{1280, 640};
    }

    private void closeCameraSync() {
        UVCCamera cam = uvcCamera;
        uvcCamera = null;
        previewing = false;
        if (cam == null) {
            return;
        }
        try {
            cam.stopPreview();
        } catch (Throwable ignored) {
        }
        try {
            cam.destroy();
        } catch (Throwable ignored) {
        }
    }

    private void refreshUsb() {
        try {
            UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
            if (um == null) {
                return;
            }
            UsbDevice found = null;
            for (UsbDevice d : um.getDeviceList().values()) {
                if (isUvcDevice(d)) {
                    found = d;
                    break;
                }
            }
            if (found == null) {
                usbText = "USB: 未检测到相机";
            } else if (ctrlBlock != null || previewing) {
                usbText = "USB: " + hex(found.getVendorId()) + ":" + hex(found.getProductId()) + " 已授权";
            } else if (usbManager != null && usbManager.hasPermission(found)) {
                usbText = "USB: " + hex(found.getVendorId()) + ":" + hex(found.getProductId()) + " 已授权";
                connectAuthorizedDevice(found);
            } else {
                usbText = "USB: " + hex(found.getVendorId()) + ":" + hex(found.getProductId()) + " 未授权";
                requestUsbPermission(found);
            }
        } catch (Throwable ignored) {
        }
    }

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            refreshUsb();
            if (!previewing) {
                tryOpenCamera();
            }
            updateStatus();
            ui.postDelayed(this, 2000);
        }
    };

    // ---------------- UI ----------------

    private View buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        cameraContainer = new FrameLayout(this);
        root.addView(cameraContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cameraView = new AspectTextureView(this);
        cameraContainer.addView(cameraView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cameraView.setAspect(2, 1);

        hint = new TextView(this);
        hint.setTextColor(0xFFCCCCCC);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setText("等待相机画面…\n请确认全景相机已插入并允许 USB 访问");
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setBackgroundColor(0xB0000000);
        status.setPadding(24, 12, 24, 12);
        status.setTextSize(11);
        status.setText("初始化…");
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        status.setOnClickListener(v -> {
            if (!hasCameraPermission()) {
                ensureCameraPermission();
            }
        });

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        stitchBtn = makeButton("拼接");
        controls.addView(stitchBtn);
        FrameLayout.LayoutParams ctlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ctlLp.bottomMargin = 24;
        root.addView(controls, ctlLp);

        // 全景查看器直接复用网页端页面：投影切换、热点、寻宝、放大镜、网格、原图对比、
        // 海报导出等全部与网页一致，App 只额外提供实时帧与陀螺仪数据。
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(bridge, "PanoHost");
        web.setVisibility(View.GONE);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        closeBtn = makeButton("退出全景");
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        closeLp.topMargin = 24;
        closeLp.rightMargin = 24;
        closeBtn.setVisibility(View.GONE);
        root.addView(closeBtn, closeLp);

        // web 是后加的，会盖住状态栏和按钮；把它们提到最上层，保证全景查看时仍可点。
        status.bringToFront();
        controls.bringToFront();
        closeBtn.bringToFront();

        closeBtn.setOnClickListener(v -> closeViewer());
        stitchBtn.setOnClickListener(v -> doStitch());

        return root;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xCC202020);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 8;
        lp.rightMargin = 8;
        b.setLayoutParams(lp);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(24, 12, 24, 12);
        return b;
    }

    private static float dist(MotionEvent ev) {
        if (ev.getPointerCount() < 2) {
            return 0f;
        }
        float dx = ev.getX(1) - ev.getX(0);
        float dy = ev.getY(1) - ev.getY(0);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static String hex(int v) {
        return String.format(Locale.US, "%04x", v);
    }

    /** WebView 页面（pano_demo.html）调用的原生桥：实时帧、原图、陀螺仪增量、海报保存。 */
    public class PanoHostBridge {
        private volatile String frameUrl = "";
        private volatile String rawUrl = "";
        private volatile float gyroYaw;
        private volatile float gyroPitch;
        private volatile boolean rawRequested;
        private volatile boolean gyroEnabled;
        private volatile boolean frameConsumed = true;
        private volatile long pushedAt;
        private volatile float roll;

        void setRoll(float deg) {
            roll = deg;
        }

        void pushFrame(String url) {
            frameUrl = url;
            frameConsumed = false;
            pushedAt = System.currentTimeMillis();
        }

        /**
         * 只有上一帧被页面真正解码上传后才允许再拼下一帧；超时兜底防止页面异常时卡死。
         */
        boolean canStitch() {
            return frameConsumed || System.currentTimeMillis() - pushedAt > 600;
        }

        @JavascriptInterface
        public void frameDone() {
            frameConsumed = true;
        }

        void pushRaw(String url) {
            rawUrl = url;
        }

        void addGyroDelta(float dyaw, float dpitch) {
            if (!gyroEnabled) {
                return;
            }
            gyroYaw += dyaw;
            gyroPitch += dpitch;
        }

        void resetGyro() {
            gyroYaw = 0f;
            gyroPitch = 0f;
        }

        boolean takeRawRequest() {
            if (!rawRequested) {
                return false;
            }
            rawRequested = false;
            return true;
        }

        @JavascriptInterface
        public String pollFrame() {
            String s = frameUrl;
            frameUrl = "";
            return s;
        }

        @JavascriptInterface
        public String pollRaw() {
            String s = rawUrl;
            rawUrl = "";
            return s;
        }

        /** 返回 "dyaw,dpitch,roll"（度），roll 为重力水平补偿的绝对值。 */
        @JavascriptInterface
        public String pollGyro() {
            float dy = gyroYaw;
            float dp = gyroPitch;
            gyroYaw = 0f;
            gyroPitch = 0f;
            return dy + "," + dp + "," + roll;
        }

        @JavascriptInterface
        public void setGyro(boolean on) {
            gyroEnabled = on;
            gyroOn = on;
            if (!on) {
                resetGyro();
            }
        }

        @JavascriptInterface
        public void requestRaw() {
            rawRequested = true;
        }

        /** 录制开关：录的是拼接后的 2:1 等距原图，状态通过 panoOnEvent 回推。 */
        @JavascriptInterface
        public void toggleRecord() {
            runOnUiThread(MainActivity.this::toggleRecording);
        }

        @JavascriptInterface
        public boolean isRecording() {
            return recorder != null;
        }

        /** 保存当前拼接好的 2:1 等距原图到相册。 */
        @JavascriptInterface
        public void capturePhoto() {
            runOnUiThread(MainActivity.this::captureEquirectPhoto);
        }

        @JavascriptInterface
        public void copyText(final String text) {
            runOnUiThread(() -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("pano", text));
                }
            });
        }

        @JavascriptInterface
        public void savePoster(final String dataUrl) {
            new Thread(() -> {
                try {
                    String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    File dir = new File(getExternalFilesDir(null), "poster");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new java.io.IOException("无法创建目录");
                    }
                    File f = new File(dir, "pano-" + System.currentTimeMillis() + ".png");
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(bytes);
                    }
                    ui.post(() -> Toast.makeText(MainActivity.this,
                            "海报已保存: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show());
                } catch (Throwable t) {
                    ui.post(() -> Toast.makeText(MainActivity.this,
                            "海报保存失败: " + t, Toast.LENGTH_SHORT).show());
                }
            }, "poster").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.getVisibility() == View.VISIBLE) {
            closeViewer();
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 状态显示 ----------------

    private void updateStatus() {
        if (status == null) {
            return;
        }
        String flow = previewing
                ? ("实时 " + previewW + "x" + previewH)
                : "未启动";
        StringBuilder sb = new StringBuilder();
        if (!hasCameraPermission()) {
            sb.append("未授予相机权限，点此授权\n");
        }
        sb.append(usbText).append('\n');
        sb.append(camText).append(" | ").append(flow).append('\n');
        sb.append(String.format(Locale.US,
                "陀螺 g=%.2f,%.2f,%.2f a=%.2f,%.2f,%.2f yaw=%.1f",
                gx, gy, gz, ax, ay, az, yaw));
        if (!errText.isEmpty()) {
            sb.append('\n').append(errText);
        } else if (!previewing && !sizeInfo.isEmpty()) {
            sb.append('\n').append(sizeInfo);
        }
        sb.append("\n采集 ").append(CAPTURE_W).append('x').append(CAPTURE_H)
                .append(" · 预览 ").append(EQ_W).append('x').append(EQ_H)
                .append(" · 原图 ").append(hiOutW()).append('x').append(hiOutW() / 2);
        if (liveFps > 0) {
            sb.append(String.format(Locale.US, "\n实时 %.1f fps · %.0f ms/帧", liveFps, liveFrameMs));
        }
        status.setText(sb.toString());
        if (hint != null) {
            hint.setVisibility(previewing ? View.GONE : View.VISIBLE);
        }
    }

    // ---------------- 录制 / 拍照 ----------------

    /** 把状态回推到 WebView 页面（网页端根据事件更新 REC 指示与提示）。 */
    private void sendToPage(String js) {
        WebView w = web;
        if (w != null) {
            w.post(() -> w.evaluateJavascript(js, null));
        }
    }

    private void onRecordState(boolean on, String path, String error) {
        if (on) {
            sendToPage("window.panoOnEvent && panoOnEvent('rec-start','')");
            Toast.makeText(this, "开始录制高分辨率原图", Toast.LENGTH_SHORT).show();
            return;
        }
        if (error != null) {
            sendToPage("window.panoOnEvent && panoOnEvent('toast',"
                    + JSONObject.quote(error) + ")");
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = path == null ? "录制已结束，没有生成文件"
                : "高分辨率全景视频已保存到相册 Movies/PanoCam";
        sendToPage("window.panoOnEvent && panoOnEvent('rec-stop',"
                + JSONObject.quote(msg) + ")");
        Toast.makeText(this, path == null ? msg : msg + "\n" + path, Toast.LENGTH_LONG).show();
    }

    private void toggleRecording() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        if (!previewing || cameraView == null) {
            Toast.makeText(this, "画面尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int ow = hiOutW();
            EquirectRecorder rec = new EquirectRecorder(this, ow, ow / 2);
            rec.start();
            recorder = rec;
            onRecordState(true, null, null);
        } catch (Throwable t) {
            Log.e("PanoCam", "start recorder failed", t);
            recorder = null;
            onRecordState(false, null, "录制启动失败: " + t);
        }
    }

    private void stopRecording() {
        final EquirectRecorder rec = recorder;
        recorder = null;
        if (rec == null) {
            return;
        }
        new Thread(() -> {
            String path = null;
            String error = null;
            try {
                path = rec.stop();
            } catch (Throwable t) {
                error = "结束录制失败: " + t;
            }
            final String p = path;
            final String e = error;
            ui.post(() -> onRecordState(false, p, e));
        }, "rec-stop").start();
    }

    /** 保存拼接后的 2:1 等距原图（最高分辨率）到相册 Pictures/PanoCam。 */
    private void captureEquirectPhoto() {
        if (!previewing || cameraView == null) {
            Toast.makeText(this, "画面尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        // 回读必须在 UI 线程；拍照单独按原生分辨率取一帧，再在后台拼接
        int[] size = new int[2];
        final int[] frame = grabPreview(hiSrcW(), hiSrcH(), size, null);
        if (frame == null) {
            Toast.makeText(this, "读取画面失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final int fw = size[0];
        final int fh = size[1];
        final int ow = hiOutW();
        final int oh = ow / 2;
        sendToPage("window.panoOnEvent && panoOnEvent('shutter','')");
        new Thread(() -> {
            String msg;
            try {
                int[] eq = PanoStitcher.stitchEquirectFast(frame, fw, fh, ow, oh, null);
                if (eq == null) {
                    throw new IllegalStateException("拼接失败");
                }
                Bitmap bmp = Bitmap.createBitmap(eq, ow, oh, Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                bmp.recycle();
                byte[] bytes = bos.toByteArray();
                String name = "pano-" + System.currentTimeMillis() + ".png";
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/PanoCam");
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri uri = getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) {
                        throw new java.io.IOException("无法创建相册条目");
                    }
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) {
                            throw new java.io.IOException("无法写入相册");
                        }
                        os.write(bytes);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, done, null, null);
                    msg = "已保存 " + ow + "x" + oh + " 拼接原图到相册 Pictures/PanoCam";
                } else {
                    File dir = new File(getExternalFilesDir(null), "pictures");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new java.io.IOException("无法创建图片目录");
                    }
                    File f = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(bytes);
                    }
                    msg = "已保存 " + ow + "x" + oh + " 拼接原图: " + f.getAbsolutePath();
                }
            } catch (Throwable t) {
                msg = "照片保存失败: " + t;
            }
            final String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        }, "photo").start();
    }

    /** 手势触发后延时 1 秒再执行，等相机画面稳定。 */
    private void scheduleGestureAction(Runnable action) {
        if (pendingGesture != null) {
            ui.removeCallbacks(pendingGesture);
        }
        pendingGesture = () -> {
            pendingGesture = null;
            action.run();
        };
        ui.postDelayed(pendingGesture, 1000);
    }

    // ---------------- 拼接 / 查看器 ----------------

    private void doStitch() {
        if (stitching) {
            return;
        }
        if (!previewing) {
            Toast.makeText(this, "还没有收到相机画面", Toast.LENGTH_SHORT).show();
            return;
        }
        int[] size = new int[2];
        int[] frame = capturePreview(size);
        if (frame == null) {
            Toast.makeText(this, "读取预览画面失败", Toast.LENGTH_SHORT).show();
            return;
        }
        stitching = true;
        stitchBtn.setText("拼接中");
        final int fw = size[0];
        final int fh = size[1];
        new Thread(() -> {
            try {
                int[] eq = PanoStitcher.stitchEquirectFast(frame, fw, fh, EQ_W, EQ_H, stitchBuf);
                if (eq == null) {
                    throw new IllegalStateException("拼接失败");
                }
                stitchBuf = eq;
                equirectPixels = eq;
                eqW = EQ_W;
                eqH = EQ_H;
                saveEquirect(eq);
                ui.post(() -> {
                    stitching = false;
                    stitchBtn.setText("拼接");
                    showViewer();
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    stitching = false;
                    stitchBtn.setText("拼接");
                    Toast.makeText(MainActivity.this, "拼接失败: " + t, Toast.LENGTH_LONG).show();
                });
            }
        }, "stitch").start();
    }

    /**
     * 从 TextureView 回读预览帧。getBitmap 支持缩放到目标尺寸：
     * 预览拼接按 1280x640 回读省开销，录制/拍照按相机原生分辨率回读保证画质。
     */
    private int[] grabPreview(int wantW, int wantH, int[] sizeOut, int[] reuse) {
        if (cameraView == null) {
            return null;
        }
        Bitmap bmp = cameraView.getBitmap(wantW, wantH);
        if (bmp == null) {
            return null;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) {
            bmp.recycle();
            return null;
        }
        int[] buf = (reuse != null && reuse.length == w * h) ? reuse : new int[w * h];
        bmp.getPixels(buf, 0, w, 0, 0, w, h);
        bmp.recycle();
        sizeOut[0] = w;
        sizeOut[1] = h;
        return buf;
    }

    private int[] capturePreview(int[] sizeOut) {
        int[] px = grabPreview(CAPTURE_W, CAPTURE_H, sizeOut, captureBuf);
        if (px != null) {
            captureBuf = px;
        }
        return px;
    }

    /** 录制用：按相机原生分辨率回读（上限 2560 宽），缓冲区复用。 */
    private int[] captureHighRes(int[] sizeOut) {
        int w = hiSrcW();
        int h = hiSrcH();
        int[] px = grabPreview(w, h, sizeOut, captureBufHi);
        if (px != null) {
            captureBufHi = px;
        }
        return px;
    }

    /** 录制/拍照原图尺寸：2:1，宽不超过 2048。 */
    private int hiOutW() {
        int w = previewW > 0 ? previewW : CAPTURE_W;
        if (w > HI_OUT_MAX_W) {
            w = HI_OUT_MAX_W;
        }
        return w & ~1;
    }

    private int hiSrcW() {
        int w = previewW > 0 ? previewW : CAPTURE_W;
        if (w > HI_SRC_MAX_W) {
            w = HI_SRC_MAX_W;
        }
        return w & ~1;
    }

    private int hiSrcH() {
        int w = hiSrcW();
        if (previewW > 0 && previewH > 0) {
            return Math.max(2, (int) Math.round((double) previewH * w / previewW) & ~1);
        }
        return w / 2;
    }

    private void saveEquirect(int[] eq) {
        try {
            Bitmap bmp = Bitmap.createBitmap(eq, EQ_W, EQ_H, Bitmap.Config.ARGB_8888);
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, "equirect.jpg");
                FileOutputStream fos = new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
                fos.close();
            }
            bmp.recycle();
        } catch (Throwable ignored) {
        }
    }

    private void showViewer() {
        if (equirectPixels == null) {
            return;
        }
        if (!webLoaded) {
            web.loadUrl(WEB_PAGE);
            webLoaded = true;
        }
        web.setVisibility(View.VISIBLE);
        closeBtn.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        yaw = 180f;
        pitch = 0f;
        bridge.resetGyro();
        viewerVisible = true;
        resetGestures();
        liveFps = 0;
        liveFrameMs = 0;
        lastLiveFrameAt = 0;
        lastFpsPushAt = 0;
        // 进入全景后持续用最新画面重拼并推给网页，节奏由「上一帧是否已渲染完」驱动
        ui.removeCallbacks(liveStitch);
        ui.postDelayed(liveStitch, 300);
    }

    private void closeViewer() {
        web.setVisibility(View.GONE);
        closeBtn.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(liveStitch);
        hint.setVisibility(View.VISIBLE);
        viewerVisible = false;
        resetGestures();
        cancelPendingGesture();
        if (recorder != null) {
            stopRecording();   // 离开全景页时把录制收尾
        }
    }

    private final Runnable liveStitch = new Runnable() {
        @Override
        public void run() {
            if (web == null || web.getVisibility() != View.VISIBLE) {
                return;
            }
            if (!bridge.canStitch()) {
                // 上一帧还在 WebView 里解码/上传，等它消费完再拼，避免堆帧
                ui.postDelayed(this, 24);
                return;
            }
            stitchLive();
        }
    };

    private void scheduleStitch(long delay) {
        ui.removeCallbacks(liveStitch);
        ui.postDelayed(liveStitch, delay);
    }

    /** 用当前预览帧重新拼接等距全景，编码后推给网页端渲染。 */
    private void stitchLive() {
        if (!previewing || !stitchBusy.compareAndSet(false, true)) {
            ui.postDelayed(liveStitch, 30);
            return;
        }
        long t0 = System.nanoTime();
        // 录制时按相机原生分辨率回读并拼高分辨率原图，预览仍降到 1024x512
        final boolean recording = recorder != null;
        int[] size = new int[2];
        int[] frame = recording ? captureHighRes(size) : capturePreview(size);
        long t1 = System.nanoTime();
        if (frame == null) {
            stitchBusy.set(false);
            ui.postDelayed(liveStitch, 30);
            return;
        }
        final int fw = size[0];
        final int fh = size[1];
        final int ow = recording ? hiOutW() : EQ_W;
        final int oh = recording ? ow / 2 : EQ_H;
        renderExec.execute(() -> {
            long t2 = System.nanoTime();
            try {
                int[] eq = PanoStitcher.stitchEquirectFast(frame, fw, fh, ow, oh,
                        recording ? stitchBufHi : stitchBuf);
                long t3 = System.nanoTime();
                if (eq != null) {
                    if (recording) {
                        stitchBufHi = eq;
                    } else {
                        stitchBuf = eq;
                    }
                    equirectPixels = eq;
                    eqW = ow;
                    eqH = oh;
                    // 录制时把这一帧直接送进 2:1 原图编码器（在拼接线程里同步拷进纹理）
                    EquirectRecorder rec = recorder;
                    if (recording && rec != null) {
                        rec.push(eq);
                    }
                    // 预览仍按 1024 宽推送，避免 WebView 每帧处理高分辨率
                    bridge.pushFrame(encodeJpeg(eq, ow, oh, recording ? EQ_W : 0));
                }
                long t4 = System.nanoTime();
                // 实时帧率：按相邻两帧完成时刻的间隔估计，再做指数平滑
                if (lastLiveFrameAt > 0) {
                    double dt = (t4 - lastLiveFrameAt) / 1e9;
                    if (dt > 0.001) {
                        double inst = Math.min(120.0, 1.0 / dt);
                        liveFps = liveFps <= 0 ? inst : liveFps * 0.85 + inst * 0.15;
                    }
                }
                lastLiveFrameAt = t4;
                double cost = (t4 - t0) / 1e6;
                liveFrameMs = liveFrameMs <= 0 ? cost : liveFrameMs * 0.85 + cost * 0.15;
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastFpsPushAt > 500) {
                    lastFpsPushAt = nowMs;
                    final String fpsText = String.format(Locale.US,
                            "%.1f fps · %.0f ms", liveFps, liveFrameMs);
                    ui.post(() -> {
                        updateStatus();
                        // 全景页全屏时状态栏是隐藏的，这里同步一份到页面角标
                        sendToPage("window.panoOnEvent && panoOnEvent('fps',"
                                + JSONObject.quote(fpsText) + ")");
                    });
                }
                if (bridge.takeRawRequest()) {
                    bridge.pushRaw(encodeJpeg(frame, fw, fh, 960));
                }
                Log.i("PanoCam", String.format(Locale.US,
                        "frame cap=%.1fms stitch=%.1fms jpeg=%.1fms total=%.1fms",
                        (t1 - t0) / 1e6, (t3 - t2) / 1e6, (t4 - t3) / 1e6, (t4 - t0) / 1e6));
            } catch (Throwable t) {
                Log.w("PanoCam", "stitch frame failed", t);
            } finally {
                stitchBusy.set(false);
            }
            ui.post(() -> scheduleStitch(0));
        });
    }

    /** int[] ARGB -> JPEG data URL；maxW>0 时按宽度等比缩小。 */
    private String encodeJpeg(int[] px, int w, int h, int maxW) {
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        if (maxW > 0 && w > maxW) {
            Bitmap small = Bitmap.createScaledBitmap(bmp, maxW, h * maxW / w, true);
            bmp.recycle();
            bmp = small;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        bmp.recycle();
        return "data:image/jpeg;base64,"
                + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    // ---------------- 传感器 ----------------

    @Override
    protected void onResume() {
        super.onResume();
        Sensor g = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor a = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g != null) {
            sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        }
        if (a != null) {
            sensorManager.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
        }
        // 手势用游戏旋转矢量（不依赖磁力计）：绕重力轴的朝向可以直接取方位角
        Sensor rv = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rv == null) {
            rv = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rv != null) {
            sensorManager.registerListener(this, rv, SensorManager.SENSOR_DELAY_GAME);
        }
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 1000);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        ui.removeCallbacks(watchdog);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        previewing = false;
        closeCameraSync();
        try {
            if (web != null) {
                web.removeJavascriptInterface("PanoHost");
                web.destroy();
                web = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (usbMonitor != null) {
                if (usbMonitor.isRegistered()) {
                    usbMonitor.unregister();
                }
                usbMonitor.destroy();
            }
        } catch (Throwable ignored) {
        }
        camExec.shutdownNow();
        renderExec.shutdownNow();
        cancelPendingGesture();
        if (recorder != null) {
            stopRecording();
        }
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) {
            onGestureYaw(event.values);
            return;
        }
        if (type == Sensor.TYPE_ACCELEROMETER) {
            // 低通滤掉手持抖动，只保留重力方向
            ax = ax + (event.values[0] - ax) * 0.25f;
            ay = ay + (event.values[1] - ay) * 0.25f;
            az = az + (event.values[2] - az) * 0.25f;
            // 只有重力主要落在屏幕平面内（手机大致竖直）时，roll 才有定义
            if (Math.hypot(ax, ay) > 4f) {
                float r = (float) Math.toDegrees(Math.atan2(ax, ay));
                rollDeg += (r - rollDeg) * 0.18f;
                bridge.setRoll(rollDeg);
            }
            onGestureTilt();
            return;
        }
        if (type != Sensor.TYPE_GYROSCOPE) {
            return;
        }
        gx = event.values[0];
        gy = event.values[1];
        gz = event.values[2];
        long ts = event.timestamp;
        float dt = lastGyroTs == 0L ? 0.01f : (ts - lastGyroTs) * 1e-9f;
        lastGyroTs = ts;
        if (dt <= 0f || dt > 0.5f) {
            return;
        }
        // 与网页端同一约定：pitch > 0 = 朝下（重力方向），绕 X 轴正向转动即低头看地面
        if (gyroOn && web != null && web.getVisibility() == View.VISIBLE) {
            float dyaw = -gy * dt * 57.2958f;
            float nextPitch = Math.max(-85f, Math.min(85f, pitch + gx * dt * 57.2958f));
            float dpitch = nextPitch - pitch;
            yaw += dyaw;
            pitch = nextPitch;
            bridge.addGyroDelta(dyaw, dpitch);
        }
        if (now - lastStatusAt > 250) {
            lastStatusAt = now;
            updateStatus();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---------------- 手势 ----------------

    private void resetGestures() {
        spinYaw = Float.NaN;
        spinArmed = false;
        spinStillSince = 0;
        photoArmed = false;
    }

    private void cancelPendingGesture() {
        if (pendingGesture != null) {
            ui.removeCallbacks(pendingGesture);
            pendingGesture = null;
        }
    }

    /**
     * 旋转手势：绕重力轴（竖直方向）转过 90° 以上，再转回出发点附近，触发录制开关。
     * 方位角来自旋转矢量，是绝对量，不存在陀螺积分漂移。
     */
    private void onGestureYaw(float[] quat) {
        if (!viewerVisible) {
            spinArmed = false;
            spinYaw = Float.NaN;
            return;
        }
        SensorManager.getRotationMatrixFromVector(rotMat, quat);
        // 设备 x 轴在世界水平面上的朝向（旋转矢量给出的 R 把设备坐标映射到 东/北/上）
        float raw = (float) Math.toDegrees(Math.atan2(rotMat[0], rotMat[3]));
        if (Float.isNaN(spinYaw)) {
            spinYaw = raw;
            spinBase = raw;
            return;
        }
        // 方位角在 ±180° 处会跳变，先展开成连续值
        float d = raw - spinYaw;
        if (d > 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        spinYaw += d;

        long now = System.currentTimeMillis();
        if (!spinArmed) {
            // 只在手机静止时更新基准（手持缓慢漂移不会积累成假手势，
            // 起手转动时基准保持不动，95° 阈值才是真实转角）
            if (Math.abs(d) < 1.2f) {
                if (spinStillSince == 0) {
                    spinStillSince = now;
                } else if (now - spinStillSince > 400) {
                    spinBase = spinYaw;
                }
            } else {
                spinStillSince = 0;
            }
            if (Math.abs(spinYaw - spinBase) > SPIN_ARM_DEG) {
                spinArmed = true;
                spinArmedAt = now;
            }
            return;
        }
        if (now - spinArmedAt > SPIN_TIMEOUT_MS) {
            spinArmed = false;
            spinBase = spinYaw;
            return;
        }
        if (Math.abs(spinYaw - spinBase) < SPIN_BACK_DEG
                && now - spinArmedAt > SPIN_MIN_MS
                && now - lastSpinFireAt > SPIN_COOLDOWN_MS) {
            spinArmed = false;
            spinBase = spinYaw;
            lastSpinFireAt = now;
            Log.i("PanoCam", "gesture spin -> record toggle");
            ui.post(this::onSpinGesture);
        }
    }

    /**
     * 拍照手势：镜头仰角先超过 +35°（上仰），再在 1.2s 内俯到 -25° 以下（下压）。
     * 仰角用重力方向算，不受陀螺漂移影响；-az 为正表示后镜头朝上。
     */
    private void onGestureTilt() {
        if (!viewerVisible) {
            photoArmed = false;
            return;
        }
        float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (mag < 1f) {
            return;
        }
        float up = -az / mag;
        float elev = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, up))));
        long now = System.currentTimeMillis();
        if (!photoArmed) {
            if (elev > PHOTO_UP_DEG) {
                photoArmed = true;
                photoArmedAt = now;
            }
            return;
        }
        if (now - photoArmedAt > PHOTO_WINDOW_MS) {
            photoArmed = false;
            return;
        }
        if (elev < PHOTO_DOWN_DEG && now - lastPhotoAt > PHOTO_COOLDOWN_MS) {
            photoArmed = false;
            lastPhotoAt = now;
            Log.i("PanoCam", "gesture nod -> photo");
            ui.post(this::onPhotoGesture);
        }
    }

    private void onSpinGesture() {
        if (recorder != null) {
            toggleRecording();   // 结束录制不需要延时
            return;
        }
        Toast.makeText(this, "手势：1 秒后开始录制", Toast.LENGTH_SHORT).show();
        scheduleGestureAction(this::toggleRecording);
    }

    private void onPhotoGesture() {
        Toast.makeText(this, "手势：1 秒后拍照", Toast.LENGTH_SHORT).show();
        scheduleGestureAction(this::captureEquirectPhoto);
    }

    /**
     * 按固定宽高比显示（UVC 输出是横向 2:1 的双鱼眼，每一半是 1:1）。
     * 不用 AUSBC 的 AspectRatioTextureView，因为它在竖屏下会把宽高交换，导致画面被压扁。
     */
    private static class AspectTextureView extends TextureView {
        private int ratioW = 2;
        private int ratioH = 1;

        AspectTextureView(Context context) {
            super(context);
        }

        void setAspect(int w, int h) {
            if (w <= 0 || h <= 0 || (w == ratioW && h == ratioH)) {
                return;
            }
            ratioW = w;
            ratioH = h;
            requestLayout();
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
    }
}
