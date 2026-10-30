package com.example.panocam;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 直接使用 libuvc(UVCCamera) 打开 Insta360 Air，不经过 AUSBC 的相机状态机。
 * AUSBC 的 ICameraStrategy 在“USB 授权后才拿到 control block”这个时序下会静默失败，
 * 并在重复 startPreview 时 stop/start 互相踩踏，因此这里自己管理 USBMonitor + UVCCamera。
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    /** 常见 UVC 输出尺寸，按可能性排序；实际以设备上报的 size list 为准。 */
    private static final int[][] CANDIDATES = {
            {1280, 640}, {1920, 960}, {2560, 1280}, {3008, 1504}, {1280, 720}, {640, 480}
    };
    private static final int EQ_W = 512;
    private static final int EQ_H = 256;
    private static final int VIEW_W = 640;
    private static final int VIEW_H = 360;
    private static final int PLANET_SIZE = 512;
    /** 自己申请 USB 权限用的 action（libuvc 自带的 PendingIntent 是 IMMUTABLE，系统写不进结果）。 */
    private static final String ACTION_USB_PERMISSION = "com.example.panocam.action.USB_PERMISSION";

    private FrameLayout root;
    private FrameLayout cameraContainer;
    private AspectTextureView cameraView;
    private TextView status;
    private TextView hint;
    private LinearLayout controls;
    private FrameLayout viewer;
    private ImageView panoImage;
    private TextView modeLabel;
    private Button stitchBtn;
    private Button modeBtn;
    private Button gyroBtn;

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
    private String sizeInfo = "";

    private SensorManager sensorManager;
    private long lastGyroTs;
    private float gx, gy, gz, ax, ay, az;
    private float yaw, pitch;              // 全景视角（度）
    private long lastStatusAt;
    private boolean gyroOn = true;

    private volatile int[] equirectPixels;
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

        try {
            usbMonitor = new USBMonitor(new UvcContext(this), usbListener);
            usbMonitor.register();
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
        try {
            usbManager.requestPermission(device, permIntent);
        } catch (Throwable t) {
            errText = "请求 USB 权限失败: " + t;
            updateStatus();
        }
    }

    private void onPermissionResult(UsbDevice device, boolean granted) {
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

    /** 已授权后，让 USBMonitor 生成 control block（它内部 hasPermission 为真时直接走 processConnect）。 */
    private void connectAuthorizedDevice(UsbDevice device) {
        if (device == null) {
            return;
        }
        try {
            if (usbMonitor != null && usbMonitor.isRegistered()) {
                usbMonitor.requestPermission(device);
            }
        } catch (Throwable t) {
            errText = "建立 USB 连接失败: " + t;
            updateStatus();
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
                    if (!hasPermission || cb == null) {
                        ui.post(() -> {
                            camText = "相机: 无 USB 权限";
                            updateStatus();
                            requestUsbPermission(device);
                        });
                        return;
                    }
                    ctrlBlock = cb;
                    ui.post(() -> {
                        usbText = "USB: 已授权 " + hex(device.getVendorId()) + ":" + hex(device.getProductId());
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
        return d.getDeviceClass() == 239 || d.getVendorId() == 0x2E1A;
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
        if (cb == null || st == null || previewing) {
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
        hint.setText("等待相机画面…\n请确认 Insta360 Air 已插入并允许 USB 访问");
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

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        stitchBtn = makeButton("拼接");
        modeBtn = makeButton("模式");
        gyroBtn = makeButton("陀螺:开");
        controls.addView(stitchBtn);
        controls.addView(modeBtn);
        controls.addView(gyroBtn);
        FrameLayout.LayoutParams ctlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ctlLp.bottomMargin = 24;
        root.addView(controls, ctlLp);

        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        viewer.setVisibility(View.GONE);

        panoImage = new ImageView(this);
        panoImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        viewer.addView(panoImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        modeLabel = new TextView(this);
        modeLabel.setTextColor(0xFFFFFFFF);
        modeLabel.setTextSize(12);
        modeLabel.setBackgroundColor(0x99000000);
        modeLabel.setPadding(20, 8, 20, 8);
        FrameLayout.LayoutParams mlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        mlLp.bottomMargin = 24;
        mlLp.leftMargin = 24;
        viewer.addView(modeLabel, mlLp);

        Button close = makeButton("关闭");
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        closeLp.topMargin = 24;
        closeLp.rightMargin = 24;
        viewer.addView(close, closeLp);

        root.addView(viewer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // viewer 是后加的，会盖住状态栏和按钮；把它们提到最上层，保证全景查看时按钮仍可点。
        status.bringToFront();
        controls.bringToFront();

        close.setOnClickListener(v -> closeViewer());
        stitchBtn.setOnClickListener(v -> doStitch());
        modeBtn.setOnClickListener(v -> cycleMode());
        gyroBtn.setOnClickListener(v -> {
            gyroOn = !gyroOn;
            gyroBtn.setText(gyroOn ? "陀螺:开" : "陀螺:关");
        });
        panoImage.setOnTouchListener(new View.OnTouchListener() {
            private float lastX, lastY;
            private float lastDist;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = ev.getX();
                        lastY = ev.getY();
                        lastDist = 0f;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (ev.getPointerCount() >= 2) {
                            lastDist = dist(ev);
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (ev.getPointerCount() >= 2) {
                            float d = dist(ev);
                            if (lastDist > 0f && viewMode == PanoStitcher.MODE_PERSPECTIVE) {
                                viewFov *= lastDist / d;
                                viewFov = Math.max(25f, Math.min(120f, viewFov));
                                requestRender();
                            }
                            lastDist = d;
                        } else {
                            float dx = ev.getX() - lastX;
                            float dy = ev.getY() - lastY;
                            lastX = ev.getX();
                            lastY = ev.getY();
                            // 所有模式都是转视角，而不是平移图片
                            yaw -= dx * 0.18f;
                            if (viewMode == PanoStitcher.MODE_PERSPECTIVE
                                    || viewMode == PanoStitcher.MODE_FISHEYE) {
                                pitch += dy * 0.18f;
                                pitch = Math.max(-85f, Math.min(85f, pitch));
                            }
                            if (ev.getEventTime() - lastRenderAt > 60) {
                                requestRender();
                            }
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });

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

    // ---------------- 状态显示 ----------------

    private void updateStatus() {
        if (status == null) {
            return;
        }
        String flow = previewing
                ? ("实时 " + previewW + "x" + previewH)
                : "未启动";
        StringBuilder sb = new StringBuilder();
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
        status.setText(sb.toString());
        if (hint != null) {
            hint.setVisibility(previewing ? View.GONE : View.VISIBLE);
        }
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
                int[] eq = PanoStitcher.stitchEquirect(frame, fw, fh, EQ_W, EQ_H);
                if (eq == null) {
                    throw new IllegalStateException("拼接失败");
                }
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
     * 直接从 TextureView 取当前预览帧（ARGB），颜色与屏幕上看到的一致，
     * 同时也避免了 libuvc 每帧再做一次 NV21 转换（那正是掉帧的主因）。
     */
    private int[] capturePreview(int[] sizeOut) {
        if (cameraView == null) {
            return null;
        }
        Bitmap bmp = cameraView.getBitmap();
        if (bmp == null) {
            return null;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w <= 0 || h <= 0) {
            bmp.recycle();
            return null;
        }
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();
        sizeOut[0] = w;
        sizeOut[1] = h;
        return px;
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
        boolean firstOpen = viewer.getVisibility() != View.VISIBLE;
        viewer.setVisibility(View.VISIBLE);
        hint.setVisibility(View.GONE);
        if (firstOpen) {
            yaw = 180f;
            pitch = 0f;
            viewFov = 110f;
        }
        panoImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        updateModeLabel();
        requestRender();
        // 全景查看时持续用最新画面重拼，避免静止看一张旧图
        ui.removeCallbacks(liveStitch);
        ui.postDelayed(liveStitch, 400);
    }

    private void closeViewer() {
        viewer.setVisibility(View.GONE);
        ui.removeCallbacks(liveStitch);
        renderAgain = false;
        hint.setVisibility(View.VISIBLE);
    }

    private void cycleMode() {
        viewMode = (viewMode + 1) % 4;
        panoImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        updateModeLabel();
        if (viewer.getVisibility() == View.VISIBLE) {
            requestRender();
            return;
        }
        if (!previewing) {
            Toast.makeText(this, "还没有收到相机画面", Toast.LENGTH_SHORT).show();
            return;
        }
        // 没进查看器时点模式，直接拼一帧并按当前模式显示
        doStitch();
    }

    private final Runnable liveStitch = new Runnable() {
        @Override
        public void run() {
            if (viewer == null || viewer.getVisibility() != View.VISIBLE) {
                return;
            }
            stitchLive();
            ui.postDelayed(this, 1500);
        }
    };

    /** 用当前预览帧重新拼接等距全景，然后按当前模式重绘。 */
    private void stitchLive() {
        if (!previewing) {
            return;
        }
        if (!stitchBusy.compareAndSet(false, true)) {
            return;
        }
        int[] size = new int[2];
        int[] frame = capturePreview(size);
        if (frame == null) {
            stitchBusy.set(false);
            return;
        }
        final int fw = size[0];
        final int fh = size[1];
        renderExec.execute(() -> {
            try {
                int[] eq = PanoStitcher.stitchEquirect(frame, fw, fh, EQ_W, EQ_H);
                if (eq != null) {
                    equirectPixels = eq;
                    eqW = EQ_W;
                    eqH = EQ_H;
                }
            } catch (Throwable ignored) {
            } finally {
                stitchBusy.set(false);
            }
            ui.post(() -> {
                if (viewer != null && viewer.getVisibility() == View.VISIBLE) {
                    requestRender();
                }
            });
        });
    }

    private void updateModeLabel() {
        String name;
        switch (viewMode) {
            case PanoStitcher.MODE_TINY_PLANET:
                name = "小行星";
                break;
            case PanoStitcher.MODE_RABBIT_HOLE:
                name = "兔子洞";
                break;
            case PanoStitcher.MODE_FISHEYE:
                name = "鱼眼";
                break;
            default:
                name = "360° 全景";
                break;
        }
        modeLabel.setText(name + "  (" + EQ_W + "x" + EQ_H + ")");
    }

    private void requestRender() {
        if (viewer.getVisibility() != View.VISIBLE || equirectPixels == null) {
            return;
        }
        lastRenderAt = System.currentTimeMillis();
        if (!renderBusy.compareAndSet(false, true)) {
            renderAgain = true;
            return;
        }
        renderExec.execute(() -> {
            try {
                renderOnce();
            } finally {
                renderBusy.set(false);
                if (renderAgain) {
                    renderAgain = false;
                    requestRender();
                }
            }
        });
    }

    private void renderOnce() {
        int[] eq = equirectPixels;
        if (eq == null) {
            return;
        }
        int ow;
        int oh;
        int[] out;
        float localYaw = yaw;
        float localPitch = pitch;
        float localFov = viewFov;
        int mode = viewMode;
        if (mode == PanoStitcher.MODE_PERSPECTIVE) {
            ow = VIEW_W;
            oh = VIEW_H;
            out = PanoStitcher.renderPerspective(eq, eqW, eqH, ow, oh, localFov, localYaw, localPitch);
        } else if (mode == PanoStitcher.MODE_TINY_PLANET) {
            ow = PLANET_SIZE;
            oh = PLANET_SIZE;
            out = PanoStitcher.renderTinyPlanet(eq, eqW, eqH, ow, localYaw);
        } else if (mode == PanoStitcher.MODE_RABBIT_HOLE) {
            ow = PLANET_SIZE;
            oh = PLANET_SIZE;
            out = PanoStitcher.renderRabbitHole(eq, eqW, eqH, ow, localYaw);
        } else {
            ow = PLANET_SIZE;
            oh = PLANET_SIZE;
            out = PanoStitcher.renderFisheye(eq, eqW, eqH, ow, localYaw, localPitch);
        }
        if (out == null) {
            return;
        }
        final int fow = ow;
        final int foh = oh;
        final int[] fout = out;
        ui.post(() -> {
            if (viewer.getVisibility() != View.VISIBLE) {
                return;
            }
            if (renderBitmap == null || renderBitmap.getWidth() != fow
                    || renderBitmap.getHeight() != foh || renderBitmap.isRecycled()) {
                renderBitmap = Bitmap.createBitmap(fow, foh, Bitmap.Config.ARGB_8888);
            }
            renderBitmap.setPixels(fout, 0, fow, 0, 0, fow, foh);
            panoImage.setImageBitmap(renderBitmap);
            panoImage.invalidate();
        });
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
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax = event.values[0];
            ay = event.values[1];
            az = event.values[2];
            return;
        }
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) {
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
        if (gyroOn && viewer.getVisibility() == View.VISIBLE) {
            yaw -= gy * dt * 57.2958f;
            pitch -= gx * dt * 57.2958f;
            pitch = Math.max(-85f, Math.min(85f, pitch));
            if (now - lastRenderAt > 70) {
                requestRender();
            }
        }
        if (now - lastStatusAt > 250) {
            lastStatusAt = now;
            updateStatus();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
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
