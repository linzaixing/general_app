package com.example.panocam;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.os.PowerManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
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

    /** 常见 UVC 输出尺寸；画质优先取 3008×1504（拼接光学上限 ≈2784×1392）。 */
    private static final int[][] CANDIDATES = {
            {3008, 1504}, {2176, 1088}, {1472, 736}, {1280, 640}, {1280, 720}, {640, 480}
    };
    /** 预览拼接宽度/保存分辨率由页面「画质」选项下发，这里只是默认值。 */
    private static final int PREVIEW_OUT_W = 1536;
    private static final int SAVE_OUT_W = 2784;
    /** 预览回读尺寸（2:1，与双鱼眼画面一致）。 */
    private static final int CAPTURE_W = 1280;
    private static final int CAPTURE_H = 640;
    /** 录制/拍照原图的采集上限（源宽），输出宽度由页面选择的保存分辨率决定。 */
    private static final int HI_SRC_MAX_W = 3008;
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
    private GlPreview cameraView;
    private TextView status;
    private TextView hint;
    private FrameLayout waitLayer;
    private LinearLayout statusWrap;
    private View statusDot;
    private TextView detail;
    private LinearLayout controls;
    private TextView worksBtn;
    private WebView web;
    private Button stitchBtn;
    private PanoGalleryView gallery;
    private final PanoHostBridge bridge = new PanoHostBridge();
    private final PreviewLoadPolicy previewPolicy = new PreviewLoadPolicy();
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

    private boolean permRequestedForDevice;
    /** 取景预览朝向：相机模组相对机身的固定偏置 + 重力量化到 90° 步进。 */
    private static final float PREVIEW_ROT_OFFSET_DEG = -90f;
    private int previewRotQuarter = -1;
    private long lastRotLogAt;
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
    /** 静态查看模式（打开已保存成片），不跑实时拼接。 */
    private volatile boolean viewerStatic;
    private boolean viewerFromGallery;
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
    private volatile int effectivePreviewW = PREVIEW_OUT_W;
    private volatile double lastPreviewRoundMs;
    private volatile long lastCpuPreviewAtNs;
    private Runnable pendingGesture;                 // 手势触发的延时动作

    private volatile int[] equirectPixels;
    private int[] captureBuf;
    private int[] captureBufHi;
    private int[] stitchBuf;
    private int[] stitchBufHi;
    private int[] photoBuf;
    private final PreviewEncoder previewEncoder = new PreviewEncoder();
    private int recPreviewTick;      // CPU 兜底录制时控制预览推送频率（每 2 帧推一次）
    private int eqW, eqH;
    /** 页面「画质」选择：预览拼接宽度、保存原图宽度（均为 2:1 的宽）。 */
    private volatile int previewOutW = PREVIEW_OUT_W;
    private volatile int saveOutW = SAVE_OUT_W;
    /** 防抖：开启时把重力 roll 烘进录制/拍照的原图，锁定地平线。 */
    private volatile boolean stabilize;
    private int viewMode = PanoStitcher.MODE_PERSPECTIVE;
    private float viewFov = 110f;
    private Bitmap renderBitmap;
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

        cameraView.setListener(new GlPreview.Listener() {
            @Override
            public void onCameraTexture(SurfaceTexture st) {
                surfaceTexture = st;
                tryOpenCamera();
            }

            @Override
            public void onCameraLost() {
                surfaceTexture = null;
                previewing = false;
                camExec.execute(MainActivity.this::closeCameraSync);
            }

            @Override
            public void onPreviewFrame(int[] eq, int ow, int oh) {
                handleGlPreview(eq, ow, oh);
            }

            @Override
            public void onPhotoFrame(int[] eq, int ow, int oh) {
                saveEquirectPhoto(eq, ow, oh, rollForSave());
            }

            @Override
            public void onPhotoFrame(int[] eq, int ow, int oh, float appliedRollDegrees) {
                saveEquirectPhoto(eq, ow, oh, 0f);
            }

            @Override
            public void onGlError(String msg) {
                errText = msg;
                ui.post(MainActivity.this::updateStatus);
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
        // 摸底用的设备/参数快照，和每 5 秒的聚合日志一起看
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
            Perf.logConfig(String.format(Locale.US,
                    "dev=%s hw=%s abi=%s sdk=%d screen=%dx%d preview=%dx%d cap=%dx%d save=%d stab=%s",
                    Build.MODEL, Build.HARDWARE, abi, Build.VERSION.SDK_INT,
                    dm.widthPixels, dm.heightPixels,
                    previewOutW, previewOutW / 2, CAPTURE_W, CAPTURE_H, saveOutW, stabilize));
        } catch (Throwable ignored) {
        }
        updateStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 插相机时系统按 device_filter 把 attach intent 送到已有实例：
        // singleTask 保证落在外屏当前实例上，不会新开一个内屏实例
        if (intent != null
                && "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            ui.postDelayed(() -> requestUsbPermission(findUvcDevice()), 600);
        }
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
        if (permRequestedForDevice) {
            return;     // 同一次插入只弹一次授权框，避免内屏堆积
        }
        permRequestedForDevice = true;
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
        permRequestedForDevice = false;
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
                        // 稍等片刻：若系统按 device_filter 自动授权，这里就不用再弹授权框
                        ui.postDelayed(() -> requestUsbPermission(device), 600);
                    });
                }

                @Override
                public void onDetach(UsbDevice device) {
                    ui.post(() -> {
                        permRequestedForDevice = false;
                        handleUsbLost("USB: 已拔出");
                    });
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
            StringBuilder fpsSb = new StringBuilder("uvc sizes:");
            if (sizes != null) {
                for (Size s : sizes) {
                    sb.append(' ').append(s.width).append('x').append(s.height);
                    fpsSb.append(' ').append(s.width).append('x').append(s.height).append('@');
                    if (s.fps != null && s.fps.length > 0) {
                        for (int i = 0; i < s.fps.length; i++) {
                            if (i > 0) {
                                fpsSb.append('/');
                            }
                            fpsSb.append(String.format(Locale.US, "%.0f", s.fps[i]));
                        }
                    } else {
                        fpsSb.append('?');
                    }
                }
            }
            sizeInfo = sb.toString();
            Perf.logConfig(fpsSb.toString());   // 摸底：相机支持的分辨率与帧率

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
            try {
                Size cur = cam.getPreviewSize();
                float curFps = 0f;
                try {
                    curFps = cur != null ? cur.getCurrentFrameRate() : 0f;
                } catch (Throwable ignored) {
                }
                Perf.logConfig(String.format(Locale.US, "uvc chosen=%dx%d fps=%.1f",
                        cur != null ? cur.width : w, cur != null ? cur.height : h, curFps));
            } catch (Throwable ignored) {
            }

            uvcCamera = cam;
            previewing = true;
            previewW = w;
            previewH = h;
            cameraView.setCameraSize(w, h);
            applyPreviewPolicy();
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
                if (s.width > s.height && s.width <= 3008
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
        root.setBackgroundColor(getColor(R.color.lib_background));

        cameraContainer = new FrameLayout(this);
        root.addView(cameraContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cameraView = new GlPreview(this);
        cameraView.setPreviewEligibility(bridge::canStitch);
        cameraContainer.addView(cameraView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        cameraView.setAspect(2, 1);

        // 等待相机：点阵背景 + 呼吸光斑 + 文案，与查看器页同一套视觉语言
        waitLayer = new FrameLayout(this);
        waitLayer.setBackground(new DotGridDrawable(0x1AEDEDED, dp(26), dp(1)));
        root.addView(waitLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout waitBox = new LinearLayout(this);
        waitBox.setOrientation(LinearLayout.VERTICAL);
        waitBox.setGravity(Gravity.CENTER_HORIZONTAL);

        View orb = new View(this);
        orb.setBackgroundResource(R.drawable.lib_orb);
        LinearLayout.LayoutParams orbLp = new LinearLayout.LayoutParams(dp(132), dp(132));
        orbLp.bottomMargin = dp(20);
        waitBox.addView(orb, orbLp);
        breathing(orb);

        TextView waitTitle = new TextView(this);
        waitTitle.setText("等待相机接入");
        waitTitle.setTextColor(getColor(R.color.lib_text_primary));
        waitTitle.setTextSize(17);
        waitTitle.setTypeface(Typeface.DEFAULT_BOLD);
        waitTitle.setGravity(Gravity.CENTER);
        waitBox.addView(waitTitle);

        hint = new TextView(this);
        hint.setTextColor(getColor(R.color.lib_text_tertiary));
        hint.setTextSize(12);
        hint.setLineSpacing(dp(5), 1f);
        hint.setGravity(Gravity.CENTER);
        hint.setText("请将全景相机插入 USB 接口\n并在弹窗中允许访问");
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(10);
        waitBox.addView(hint, hintLp);

        FrameLayout.LayoutParams waitLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        waitLp.bottomMargin = dp(48);
        waitLayer.addView(waitBox, waitLp);

        statusWrap = new LinearLayout(this);
        statusWrap.setOrientation(LinearLayout.HORIZONTAL);
        statusWrap.setGravity(Gravity.CENTER_VERTICAL);
        statusWrap.setBackgroundResource(R.drawable.lib_chip);
        statusWrap.setPadding(dp(13), dp(9), dp(14), dp(9));

        statusDot = new View(this);
        statusDot.setBackground(oval(getColor(R.color.lib_text_tertiary)));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotLp.rightMargin = dp(8);
        statusWrap.addView(statusDot, dotLp);

        status = new TextView(this);
        status.setTextColor(getColor(R.color.lib_text_secondary));
        status.setTextSize(11);
        status.setLineSpacing(dp(3), 1f);
        status.setMaxWidth(dp(272));
        status.setText("初始化…");
        statusWrap.addView(status);

        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        statusLp.leftMargin = dp(16);
        statusLp.topMargin = dp(16);
        root.addView(statusWrap, statusLp);

        detail = new TextView(this);
        detail.setTextColor(getColor(R.color.lib_text_tertiary));
        detail.setTextSize(10);
        detail.setLineSpacing(dp(3), 1f);
        detail.setBackgroundResource(R.drawable.lib_panel);
        detail.setPadding(dp(12), dp(10), dp(12), dp(10));
        detail.setVisibility(View.GONE);
        FrameLayout.LayoutParams detailLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        detailLp.leftMargin = dp(16);
        detailLp.rightMargin = dp(16);
        detailLp.topMargin = dp(72);
        root.addView(detail, detailLp);

        statusWrap.setOnClickListener(v -> {
            if (!hasCameraPermission()) {
                ensureCameraPermission();
            } else {
                detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });

        worksBtn = new TextView(this);
        worksBtn.setText("作品");
        worksBtn.setTextSize(11);
        worksBtn.setTextColor(getColor(R.color.lib_text_secondary));
        worksBtn.setBackgroundResource(R.drawable.lib_chip);
        worksBtn.setPadding(dp(14), dp(9), dp(14), dp(9));
        worksBtn.setOnClickListener(v -> showGallery(true));
        FrameLayout.LayoutParams worksLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        worksLp.rightMargin = dp(16);
        worksLp.topMargin = dp(16);
        root.addView(worksBtn, worksLp);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        stitchBtn = makeCtaButton();
        controls.addView(stitchBtn);
        FrameLayout.LayoutParams ctlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ctlLp.bottomMargin = dp(28);
        root.addView(controls, ctlLp);

        // 全景查看器直接复用网页端页面：投影切换、热点、寻宝、放大镜、网格、原图对比、
        // 海报导出等全部与网页一致，App 只额外提供实时帧与陀螺仪数据。
        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.lib_background));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(bridge, "PanoHost");
        web.setVisibility(View.GONE);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // web 是后加的，会盖住状态栏和按钮；把它们提到最上层，保证全景查看时仍可点。
        detail.bringToFront();
        statusWrap.bringToFront();
        worksBtn.bringToFront();
        controls.bringToFront();

        stitchBtn.setOnClickListener(v -> doStitch());

        // 首页「最近作品」瀑布流：启动即展示，点「开始拍摄」进入取景，点作品进 360° 查看器
        gallery = new PanoGalleryView(this, new PanoGalleryView.Listener() {
            @Override
            public void onShoot() {
                showGallery(false);
            }

            @Override
            public void onOpen(Uri uri, boolean video) {
                if (video) {
                    playVideo(uri);
                } else {
                    openPhotoViewer(uri);
                }
            }
        });
        root.addView(gallery, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gallery.bringToFront();
        cameraView.setRawDisplayEnabled(false);

        return root;
    }

    private void showGallery(boolean show) {
        if (gallery == null) {
            return;
        }
        if (cameraView != null) cameraView.setRawDisplayEnabled(!show);
        if (!show) {
            // 进入取景/拼接：务必退出静态查看状态，否则实时拼接会被跳过（画面定格）
            viewerStatic = false;
            viewerFromGallery = false;
            if (web != null) {
                web.post(() -> web.evaluateJavascript(
                        "if(window.panoStopVideo)panoStopVideo();"
                                + "var s=document.getElementById('panoStaticStyle'); if(s) s.remove();", null));
            }
        }
        if (show) {
            gallery.refresh();
            gallery.bringToFront();
        }
        gallery.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 主行动：白底胶囊 CTA（与 libraries.dev 的按钮同款）。 */
    private Button makeCtaButton() {
        Button b = new Button(this);
        b.setText("拼接全景");
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(getColor(R.color.lib_on_accent));
        b.setBackgroundResource(R.drawable.lib_cta);
        b.setPadding(dp(40), dp(15), dp(40), dp(15));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        return b;
    }

    /** 呼吸动画：把等待态的光斑做活，避免画面像卡死。 */
    private void breathing(View v) {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(
                v,
                PropertyValuesHolder.ofFloat("scaleX", 0.94f, 1.08f),
                PropertyValuesHolder.ofFloat("scaleY", 0.94f, 1.08f),
                PropertyValuesHolder.ofFloat("alpha", 0.55f, 1f));
        a.setDuration(2800);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
    }

    private GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setWaitingVisible(boolean visible) {
        if (waitLayer != null) {
            waitLayer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
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
        private volatile long pushedAtNano;
        private volatile float roll;

        void setRoll(float deg) {
            roll = deg;
        }

        void pushFrame(String url) {
            frameUrl = url;
            frameConsumed = false;
            pushedAt = System.currentTimeMillis();
            pushedAtNano = System.nanoTime();
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
            long pushed = pushedAtNano;
            if (pushed > 0) {
                lastPreviewRoundMs = Math.max(0, System.nanoTime() - pushed) / 1_000_000.0;
                Perf.end(Perf.ROUND, pushed);   // 帧推给页面 -> 页面解码并上传纹理完成
            }
        }

        /** 网页端每 5 秒上报一次 JS 侧指标（decode/upload/draw/raf），统一进 PanoPerf 日志。 */
        @JavascriptInterface
        public void logPerf(String key, double p50, double p95, int n) {
            Perf.logJs(key, p50, p95, n);
        }

        /** 右侧栏「退出全景」：关闭查看器回到相机页。 */
        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::closeViewer);
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

        /** 页面「画质」选项：预览拼接宽度 / 保存原图宽度（2:1 的宽）。 */
        @JavascriptInterface
        public void setQuality(int prevW, int saveW) {
            previewOutW = Math.max(512, Math.min(1536, prevW));
            saveOutW = Math.max(512, Math.min(2784, saveW));
            applyPreviewPolicy();
            runOnUiThread(MainActivity.this::updateStatus);
        }

        /** 防抖开关：开启后预览、录制、拍照都按重力 roll 锁定地平线；关闭则用相机原始朝向。 */
        @JavascriptInterface
        public void setStabilize(boolean on) {
            stabilize = on;
            if (on) {
                bridge.setRoll(rollForDisplay());
                cameraView.setRoll(rollForDisplay());
            } else {
                bridge.setRoll(0f);
                cameraView.setRoll(0f);
            }
            runOnUiThread(MainActivity.this::updateStatus);
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
        boolean permOk = hasCameraPermission();
        String flow = previewing
                ? ("实时 " + previewW + "x" + previewH)
                : "未启动";
        String fpsLine = liveFps > 0
                ? String.format(Locale.US, " · %.1f fps", liveFps)
                : "";
        status.setText((permOk ? usbText : "未授予相机权限，点此授权")
                + '\n' + camText + " · " + flow + fpsLine);

        // 状态点：权限缺失=琥珀，预览中=强调蓝，其余=灰
        int dot = !permOk ? 0xFFF5A623
                : previewing ? getColor(R.color.lib_accent)
                : getColor(R.color.lib_text_tertiary);
        if (statusDot != null) {
            statusDot.setBackground(oval(dot));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "陀螺 g=%.2f,%.2f,%.2f a=%.2f,%.2f,%.2f yaw=%.1f",
                gx, gy, gz, ax, ay, az, yaw));
        sb.append("\n采集 ").append(CAPTURE_W).append('x').append(CAPTURE_H)
                .append(" · 预览 ").append(previewOutW).append('x').append(previewOutW / 2)
                .append(" · 原图 ").append(hiOutW()).append('x').append(hiOutW() / 2)
                .append(stabilize ? " · 防抖开" : " · 防抖关");
        if (liveFps > 0) {
            sb.append(String.format(Locale.US, "\n实时 %.1f fps · %.0f ms/帧", liveFps, liveFrameMs));
        }
        if (!errText.isEmpty()) {
            sb.append('\n').append(errText);
        } else if (!previewing && !sizeInfo.isEmpty()) {
            sb.append('\n').append(sizeInfo);
        }
        if (detail != null) {
            detail.setText(sb.toString());
        }

        setWaitingVisible(!previewing);
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
        // GPU 正常时拼接结果直接画进编码器 Surface；只有 GPU 拼接不可用才回退 CPU/NEON 送帧
        final boolean direct = cameraView.isStitchOk();
        if (!direct) {
            Toast.makeText(this, "GPU 拼接不可用，录制回退 CPU（帧率会明显下降）",
                    Toast.LENGTH_SHORT).show();
        }
        try {
            int ow = hiOutW();
            EquirectRecorder rec = new EquirectRecorder(this, ow, ow / 2, direct);
            long rs = Perf.t();
            rec.start();
            Perf.end(Perf.REC_START, rs);        // MediaCodec + MediaMuxer + EGL 初始化
            recorder = rec;
            if (direct) {
                cameraView.setRecorder(rec);
                cameraView.setRecorderRoll(rollForSave());   // 录制成片与拍照同一朝向
            }
            applyPreviewPolicy();
            onRecordState(true, null, null);
        } catch (Throwable t) {
            Log.e("PanoCam", "start recorder failed", t);
            recorder = null;
            applyPreviewPolicy();
            onRecordState(false, null, "录制启动失败: " + t);
        }
    }

    private void stopRecording() {
        final EquirectRecorder rec = recorder;
        recorder = null;
        applyPreviewPolicy();
        if (rec == null) {
            return;
        }
        final GlPreview gl = cameraView;
        if (gl != null) {
            gl.setRecorder(null);
        }
        new Thread(() -> {
            String path = null;
            String error = null;
            try {
                if (gl != null) {
                    gl.flush();      // 等 GL 线程解绑编码器 Surface 再收编码器
                }
                long rs = Perf.t();
                path = rec.stop();
                Perf.end(Perf.REC_STOP, rs);     // 收尾编码 + muxer 完成 + 相册落盘
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
        sendToPage("window.panoOnEvent && panoOnEvent('shutter','')");
        final float shutterRoll = rollForSave();
        if (cameraView.isStitchOk()) {
            // GPU 直接按保存尺寸拼接后回读，省掉 Bitmap 再上传纹理的一次往返
            cameraView.requestPhoto(hiOutW(), shutterRoll);
            return;
        }
        // 回退：按相机原生分辨率回读一帧，CPU/NEON 拼接
        int[] size = new int[2];
        long g0 = Perf.t();
        final int[] frame = grabPreview(hiSrcW(), hiSrcH(), size, null);
        Perf.end(Perf.P_GRAB, g0);
        if (frame == null) {
            Toast.makeText(this, "读取画面失败", Toast.LENGTH_SHORT).show();
            return;
        }
        final int fw = size[0];
        final int fh = size[1];
        final int ow = hiOutW();
        final int oh = ow / 2;
        new Thread(() -> {
            try {
                long st = Perf.t();
                int[] eq = PanoStitcher.stitchEquirectFast(frame, fw, fh, ow, oh, photoBuf);
                Perf.end(Perf.P_STITCH, st);
                if (eq == null) {
                    throw new IllegalStateException("拼接失败");
                }
                photoBuf = eq;   // 复用输出缓冲，避免每次拍照都分配 8~13MB
                saveEquirectPhoto(eq, ow, oh, shutterRoll);
            } catch (Throwable t) {
                final String m = "照片保存失败: " + t;
                ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
            }
        }, "photo").start();
    }

    /** 把拼接好的 2:1 等距原图（按防抖角度摆正）写进相册 Pictures/PanoCam。 */
    private void saveEquirectPhoto(int[] eqIn, int ow, int oh) {
        saveEquirectPhoto(eqIn, ow, oh, rollForSave());
    }

    private void saveEquirectPhoto(int[] eqIn, int ow, int oh, float cpuRollDegrees) {
        final long pStart = Perf.t();
        new Thread(() -> {
            String msg;
            try {
                int[] eq = eqIn;
                float rollRad = (float) Math.toRadians(cpuRollDegrees);
                long rt = Perf.t();
                if (rollRad != 0f) {
                    eq = PanoStitcher.rotateEquirect(eq, ow, oh, rollRad);
                }
                Perf.end(Perf.P_ROTATE, rt);
                long p0 = Perf.t();
                Bitmap bmp = Bitmap.createBitmap(eq, ow, oh, Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                // PNG 压缩要秒级，JPEG 95 只要百毫秒级，文件也更小
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos);
                bmp.recycle();
                byte[] bytes = bos.toByteArray();
                Perf.end(Perf.P_PNG, p0);      // ARGB->Bitmap + JPEG 压缩 + 取字节
                String name = "pano-" + System.currentTimeMillis() + ".jpg";
                long io0 = Perf.t();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
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
                Perf.end(Perf.P_IO, io0);        // MediaStore 写入 / 文件落盘
                Perf.end(Perf.P_ALL, pStart);    // 拍照端到端
            } catch (Throwable t) {
                msg = "照片保存失败: " + t;
            }
            final String m = msg;
            ui.post(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (gallery != null) {
                    gallery.refresh();   // 新成片立刻出现在首页瀑布流
                }
            });
        }, "photo-save").start();
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
        if (!previewing) {
            Toast.makeText(this, "还没有收到相机画面", Toast.LENGTH_SHORT).show();
            return;
        }
        // 实时拼接已经在跑（GPU 每帧 / CPU 兜底每轮），直接进全景页即可
        showViewer();
    }

    /**
     * 回读一帧原始相机画面（CPU 兜底路径用）。
     * 老的 getBitmap 会走 UI 线程 + Bitmap 中转，这里改成 GL 线程整帧回读。
     */
    private int[] grabPreview(int wantW, int wantH, int[] sizeOut, int[] reuse) {
        if (cameraView == null) {
            return null;
        }
        int w = Math.max(2, wantW & ~1);
        int h = Math.max(2, wantH & ~1);
        int[] px = cameraView.grabRaw(w, h);
        if (px == null) {
            return null;
        }
        sizeOut[0] = w;
        sizeOut[1] = h;
        return px;
    }

    private int[] capturePreview(int[] sizeOut) {
        // 回读分辨率不必高于预览拼接宽度：1024 档回读 1024x512，比固定 1280x640 省一半多
        int w = Math.min(CAPTURE_W, Math.max(640, effectivePreviewW));
        if ((w & 1) != 0) {
            w--;
        }
        int[] px = grabPreview(w, w / 2, sizeOut, captureBuf);
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

    /** 录制/拍照原图尺寸：按页面选择的保存分辨率，且不超过采集源宽度。 */
    private int hiOutW() {
        int w = Math.min(saveOutW, hiSrcW());
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
            Bitmap bmp = Bitmap.createBitmap(eq, eqW, eqH, Bitmap.Config.ARGB_8888);
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
        if (!webLoaded) {
            web.loadUrl(WEB_PAGE);
            webLoaded = true;
        }
        web.setVisibility(View.VISIBLE);
        setWaitingVisible(false);
        statusWrap.setVisibility(View.GONE);
        detail.setVisibility(View.GONE);
        worksBtn.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        yaw = 180f;
        pitch = 0f;
        bridge.resetGyro();
        viewerVisible = true;
        applyPreviewPolicy();
        cameraView.setRawDisplayEnabled(false);
        resetGestures();
        liveFps = 0;
        liveFrameMs = 0;
        lastLiveFrameAt = 0;
        lastFpsPushAt = 0;
        ui.removeCallbacks(liveStitch);
        if (cameraView != null && cameraView.isStitchOk()) {
            // GPU 路径：GL 线程每帧自己拼并回调，不需要 UI 线程轮询
            cameraView.setPreviewEnabled(!viewerStatic);
        } else if (!viewerStatic) {
            // CPU 兜底：进入全景后用 UI 线程轮询重拼
            ui.postDelayed(liveStitch, 300);
        }
    }

    /** 静态查看模式：隐藏相机页那套功能栏，只留小行星/鸟瞰的可交互展示。 */
    private void applyStaticChrome() {
        final String js = "var s=document.getElementById('panoStaticStyle');"
                + "if(!s){s=document.createElement('style'); s.id='panoStaticStyle';"
                + "s.textContent='.bar,.dock,.captureDeck,.statusRow,.hdr,.hint,#recHud,#hud,.scrimTop,.scrimBot{display:none!important}';"
                + "document.head.appendChild(s);}"
                + "var b=document.querySelector(\".bar button[data-mode='1']\"); if(b) b.click();";
        web.postDelayed(() -> web.evaluateJavascript(js, null), 700);
        web.postDelayed(() -> web.evaluateJavascript(js, null), 1800);
    }

    /** 作品里的视频：复制到缓存后交给网页查看器做 360 鸟瞰播放。 */
    private void playVideo(Uri uri) {
        try {
            File dir = new File(getCacheDir(), "works");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "work.mp4");
            try (java.io.InputStream is = getContentResolver().openInputStream(uri);
                 FileOutputStream os = new FileOutputStream(f)) {
                if (is == null) {
                    return;
                }
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            viewerStatic = true;
            viewerFromGallery = true;
            gallery.setVisibility(View.GONE);
            showViewer();
            final String js = "window.panoSetVideo && panoSetVideo('file://" + f.getAbsolutePath() + "');";
            web.postDelayed(() -> web.evaluateJavascript(js, null), 900);
            web.postDelayed(() -> web.evaluateJavascript(js, null), 2000);
            applyStaticChrome();
        } catch (Throwable t) {
            viewerStatic = false;
            errText = "视频打开失败: " + t;
            updateStatus();
        }
    }

    /** 用 360° 查看器打开一张已保存的成片（静态帧，可拖动/陀螺仪浏览）。 */
    private void openPhotoViewer(Uri uri) {
        try {
            byte[] bytes;
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    return;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                }
                bytes = bo.toByteArray();
            }
            if (bytes.length == 0) {
                return;
            }
            String url = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
            viewerStatic = true;
            viewerFromGallery = true;
            gallery.setVisibility(View.GONE);
            showViewer();
            bridge.pushFrame(url);
            applyStaticChrome();
        } catch (Throwable t) {
            viewerStatic = false;
            errText = "打开作品失败: " + t;
            updateStatus();
        }
    }

    private void closeViewer() {
        web.setVisibility(View.GONE);
        statusWrap.setVisibility(View.VISIBLE);
        worksBtn.setVisibility(View.VISIBLE);
        controls.setVisibility(View.VISIBLE);
        ui.removeCallbacks(liveStitch);
        if (cameraView != null) {
            cameraView.setPreviewEnabled(false);
            cameraView.setRawDisplayEnabled(true);
        }
        hint.setVisibility(View.VISIBLE);
        setWaitingVisible(!previewing);
        viewerVisible = false;
        viewerStatic = false;
        if (web != null) {
            web.post(() -> web.evaluateJavascript(
                    "if(window.panoStopVideo)panoStopVideo();"
                            + "var s=document.getElementById('panoStaticStyle'); if(s) s.remove();", null));
        }
        if (gallery != null && viewerFromGallery) {
            gallery.refresh();
            gallery.setVisibility(View.VISIBLE);
            gallery.bringToFront();
        }
        viewerFromGallery = false;
        applyPreviewPolicy();
        resetGestures();
        cancelPendingGesture();
        if (recorder != null) {
            stopRecording();   // 离开全景页时把录制收尾
        }
    }

    private final Runnable liveStitch = new Runnable() {
        @Override
        public void run() {
            if (viewerStatic) {
                return;
            }
            if (web == null || web.getVisibility() != View.VISIBLE) {
                return;
            }
            if (cameraView != null && cameraView.isStitchOk()) {
                return;   // GPU 路径由 GL 线程回调驱动
            }
            EquirectRecorder active = recorder;
            boolean recording = active != null && active.isActive();
            if (shouldWaitForPreviewConsumer(recording, bridge.canStitch())) {
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

    private void applyPreviewPolicy() {
        int width = previewPolicy.width(previewOutW);
        boolean recording = recorder != null && recorder.isActive();
        effectivePreviewW = width;
        if (cameraView != null) {
            cameraView.setSizes(width);
            cameraView.setPreviewIntervalMs(viewerVisible
                    ? previewPolicy.intervalMs(recording) : 0);
        }
        String report = "{\"preview\":" + previewOutW + ",\"save\":" + saveOutW
                + ",\"effectivePreview\":" + width + "}";
        sendToPage("window.panoOnEvent && panoOnEvent('quality'," + JSONObject.quote(report) + ")");
    }

    private void samplePreviewLoad(double encodeMs) {
        int thermal = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
                if (manager != null) thermal = manager.getCurrentThermalStatus();
            } catch (Throwable ignored) {
            }
        }
        boolean recording = recorder != null && recorder.isActive();
        int oldWidth = previewPolicy.width(previewOutW);
        int oldQuality = previewPolicy.quality();
        int oldInterval = previewPolicy.intervalMs(recording);
        previewPolicy.sample(System.nanoTime(), thermal, encodeMs, lastPreviewRoundMs,
                recording ? 0 : liveFps);
        if (oldWidth != previewPolicy.width(previewOutW)
                || oldQuality != previewPolicy.quality()
                || oldInterval != previewPolicy.intervalMs(recording)) {
            applyPreviewPolicy();
        }
    }

    static boolean shouldWaitForPreviewConsumer(boolean recording, boolean pageReady) {
        return !recording && !pageReady;
    }

    static boolean shouldEncodeCpuPreview(boolean pageReady, boolean recording, int previewTick,
                                          int intervalMs, long nowNs, long lastPreviewAtNs) {
        return pageReady && (!recording || (previewTick & 1) == 0)
                && (intervalMs <= 0 || lastPreviewAtNs <= 0
                || nowNs - lastPreviewAtNs >= intervalMs * 1_000_000L);
    }

    /** GPU 路径：GL 线程拼好一帧后回调，这里只做 JPEG 编码 + 推给网页。 */
    private void handleGlPreview(final int[] eq, final int ow, final int oh) {
        final long t0 = Perf.t();
        renderExec.execute(() -> {
            try {
                equirectPixels = eq;
                eqW = ow;
                eqH = oh;
                EquirectRecorder rec = recorder;
                long e0 = Perf.t();
                // 录制中预览已经降到页面宽度，避免重复缩放大图
                String url = encodeJpeg(eq, ow, oh, effectivePreviewW);
                Perf.end(Perf.ENCODE, e0);          // Bitmap 拷贝 + 缩放 + JPEG + base64
                if (url != null) {
                    Perf.SIZE.add(Math.max(1L, (url.length() - 24) * 3L / 4L / 1024L) * 1000L);
                    long p0 = Perf.t();
                    bridge.pushFrame(url);
                    Perf.end(Perf.PUSH, p0);
                }
                if (bridge.takeRawRequest()) {
                    int[] raw = cameraView.grabRaw(960, 480);
                    if (raw != null) {
                        bridge.pushRaw(encodeJpeg(raw, 960, 480, 0));
                    }
                }
                samplePreviewLoad((System.nanoTime() - e0) / 1e6);
                long t4 = Perf.t();
                double cost = (t4 - t0) / 1e6;
                if (lastLiveFrameAt > 0) {
                    double dt = (t4 - lastLiveFrameAt) / 1e9;
                    if (dt > 0.001) {
                        double inst = Math.min(120.0, 1.0 / dt);
                        liveFps = liveFps <= 0 ? inst : liveFps * 0.85 + inst * 0.15;
                    }
                }
                lastLiveFrameAt = t4;
                liveFrameMs = liveFrameMs <= 0 ? cost : liveFrameMs * 0.85 + cost * 0.15;
                Perf.end(Perf.FRAME, t0);
                Perf.frameTick();
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastFpsPushAt > 500) {
                    lastFpsPushAt = nowMs;
                    final String fpsText = String.format(Locale.US,
                            "%.1f fps · %.0f ms", liveFps, liveFrameMs);
                    ui.post(() -> {
                        updateStatus();
                        Perf.flushIfDue();
                        sendToPage("window.panoOnEvent && panoOnEvent('fps',"
                                + JSONObject.quote(fpsText) + ")");
                    });
                }
            } catch (Throwable t) {
                Log.w("PanoCam", "gl preview encode failed", t);
            } finally {
                cameraView.releasePreview();
            }
        });
    }

    /** 用当前预览帧重新拼接等距全景，编码后推给网页端渲染（仅 GPU 不可用时的兜底路径）。 */
    private void stitchLive() {
        if (cameraView != null && cameraView.isStitchOk()) {
            return;   // GPU 路径走 handleGlPreview
        }
        if (!previewing || !stitchBusy.compareAndSet(false, true)) {
            ui.postDelayed(liveStitch, 30);
            return;
        }
        long t0 = System.nanoTime();
        // 录制时按相机原生分辨率回读并拼高分辨率原图，预览仍降到页面上限
        EquirectRecorder rec = recorder;
        final boolean recording = rec != null && rec.isActive();
        int[] size = new int[2];
        int[] frame = recording ? captureHighRes(size) : capturePreview(size);
        long t1 = System.nanoTime();
        Perf.end(Perf.GRAB, t0);   // 预览回读（TextureView.getBitmap + getPixels，UI 线程）
        if (frame == null) {
            stitchBusy.set(false);
            ui.postDelayed(liveStitch, 30);
            return;
        }
        final int fw = size[0];
        final int fh = size[1];
        // 尺寸跟编码器走：录制中改画质只影响下一次录制，不会把这一条视频写坏
        final int ow = recording ? rec.width() : effectivePreviewW;
        final int oh = ow / 2;
        // 防抖：把这一帧的重力 roll 一起带进拼接线程，预览/录像用的是同一时刻的角度
        final float rollRad = (float) Math.toRadians(rollForSave());
        renderExec.execute(() -> {
            long t2 = System.nanoTime();
            try {
                int[] eq = PanoStitcher.stitchEquirectFast(frame, fw, fh, ow, oh,
                        recording ? stitchBufHi : stitchBuf);
                Perf.end(Perf.STITCH, t2);
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
                    if (recording) {
                        long r0 = Perf.t();
                        rec.push(eq, rollRad);
                        Perf.end(Perf.REC_PUSH, r0);   // 纹理上传 + EGL 呈现（含编码器背压）
                        Perf.recFrameTick();
                    }
                    // 预览按页面选择的预览宽度推送；录制时每 2 帧推一次，省掉一半预览编码开销
                    boolean pageReady = bridge.canStitch();
                    int previewTick = recPreviewTick;
                    if (recording && pageReady) recPreviewTick++;
                    long previewNow = System.nanoTime();
                    boolean pushPreview = shouldEncodeCpuPreview(pageReady, recording, previewTick,
                            previewPolicy.intervalMs(recording), previewNow, lastCpuPreviewAtNs);
                    if (pushPreview) {
                        lastCpuPreviewAtNs = previewNow;
                        long e0 = Perf.t();
                        String url = encodeJpeg(eq, ow, oh, effectivePreviewW);
                        Perf.end(Perf.ENCODE, e0);          // Bitmap 拷贝 + 缩放 + JPEG + base64
                        if (url != null) {
                            Perf.SIZE.add(Math.max(1L, (url.length() - 24) * 3L / 4L / 1024L) * 1000L);
                            long p0 = Perf.t();
                            bridge.pushFrame(url);
                            Perf.end(Perf.PUSH, p0);
                        }
                        samplePreviewLoad((System.nanoTime() - e0) / 1e6);
                    }
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
                        Perf.flushIfDue();
                        // 全景页全屏时状态栏是隐藏的，这里同步一份到页面角标
                        sendToPage("window.panoOnEvent && panoOnEvent('fps',"
                                + JSONObject.quote(fpsText) + ")");
                    });
                }
                if (bridge.takeRawRequest()) {
                    bridge.pushRaw(encodeJpeg(frame, fw, fh, 960));
                }
                Perf.end(Perf.FRAME, t0);   // 单帧端到端（回读 -> 拼接 -> 编码 -> 推页面）
                Perf.frameTick();
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
        byte[] jpeg = previewEncoder.encode(px, w, h, maxW, previewPolicy.quality());
        if (jpeg == null) return null;
        return "data:image/jpeg;base64,"
                + Base64.encodeToString(jpeg, Base64.NO_WRAP);
    }

    // ---------------- 传感器 ----------------

    @Override
    protected void onResume() {
        super.onResume();
        updatePreviewRotation();
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

    /** 角度归一化到 [-180,180)。 */
    private static float normDeg(float d) {
        return ((d % 360f) + 540f) % 360f - 180f;
    }

    /** 写入文件（拍照/录像）的角度：与屏幕显示补偿相反，保证成片本身正立。 */
    private float rollForSave() {
        return normDeg(-rollForDisplay());
    }

    /** 屏幕旋转角（0/90/180/270）。界面跟随设备旋转，拼接补偿直接由它驱动。 */
    private int displayRotationDeg() {
        try {
            Display d = getDisplay();
            if (d != null) {
                switch (d.getRotation()) {
                    case Surface.ROTATION_90: return 90;
                    case Surface.ROTATION_180: return 180;
                    case Surface.ROTATION_270: return 270;
                    default: return 0;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 送进预览/录制/保存的 roll：相机模组固定 -90° 偏置，再按屏幕旋转角反补。 */
    private float rollForDisplay() {
        return normDeg(PREVIEW_ROT_OFFSET_DEG - displayRotationDeg());
    }

    /** 取景预览朝向：竖持为基准，重力方向量化成 90° 步进（倒置时自动翻 180°）。 */
    private void updatePreviewRotation() {
        // 原始双半球预览：界面已跟随设备旋转，横屏无需再转；只有倒置（D=180）时翻转
        int d = displayRotationDeg();
        int q = (d == 180) ? 2 : 0;
        if (previewRotQuarter != q) {
            previewRotQuarter = q;
            Log.i("PanoCam", "raw preview rot: display=" + d + " q=" + q);
            if (cameraView != null) cameraView.setShowRotationQuarter(q);
        }
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
            // 只有重力主要落在屏幕平面内（手机大致竖直）时，roll 才有定义。
            // 这里保持设备轴：预览的画面朝向一直按这个 roll 摆正（网页端拖动/陀螺仪增量
            // 都按同一个 roll 旋转），录制/拍照是否把 roll 烘进原图由「防抖」开关决定。
            if (Math.hypot(ax, ay) > 4f) {
                float r = (float) Math.toDegrees(Math.atan2(ax, ay));
                float d = r - rollDeg;
                d = ((d + 540f) % 360f) - 180f;    // 跨 ±180° 时走短边，避免整圈回绕
                rollDeg += d * 0.18f;
                // 防抖关闭时预览也不做补偿，保证「看到的就是保存下来的朝向」
                bridge.setRoll(rollForDisplay());
                cameraView.setRoll(rollForDisplay());
                updatePreviewRotation();
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
