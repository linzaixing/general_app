# -*- coding: utf-8 -*-
import os
BASE = r"F:\vibe_coding\pano_camera\android"
F = {}
F["settings.gradle"] = r"""pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
rootProject.name = "PanoCam"
include ':app'
"""
F["gradle.properties"] = r"""org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
android.enableJetifier=true
org.gradle.java.home=D:/Program Files/Android/Android Studio/jbr
"""
F["app/build.gradle"] = r"""plugins { id 'com.android.application' }

android {
    namespace 'com.example.panocam'
    compileSdk 34
    defaultConfig {
        applicationId "com.example.panocam"
        minSdk 24
        targetSdk 34
        versionCode 2
        versionName "1.1"
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7'
}
"""
F["app/src/main/AndroidManifest.xml"] = r"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.usb.host" android:required="true" />
    <uses-feature android:name="android.hardware.sensor.gyroscope" android:required="false" />
    <application
        android:label="PanoCam"
        android:theme="@style/Theme.AppCompat.NoActionBar"
        android:hardwareAccelerated="true">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
    </application>
</manifest>
"""
F["app/src/main/java/com/example/panocam/MainActivity.java"] = r"""package com.example.panocam;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.jiangdg.ausbc.MultiCameraClient;
import com.jiangdg.ausbc.base.CameraActivity;
import com.jiangdg.ausbc.callback.ICameraStateCallBack;
import com.jiangdg.ausbc.camera.bean.CameraRequest;
import com.jiangdg.ausbc.render.env.RotateType;
import com.jiangdg.ausbc.widget.AspectRatioTextureView;
import com.jiangdg.ausbc.widget.IAspectRatio;

public class MainActivity extends CameraActivity implements SensorEventListener {
    private FrameLayout root;
    private FrameLayout cameraContainer;
    private AspectRatioTextureView cameraView;
    private TextView status;
    private SensorManager sensorManager;
    private long lastGyroTs = 0L;
    private float yaw = 0f;
    private float gx, gy, gz, ax, ay, az;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    }

    @Override
    protected View getRootView(LayoutInflater inflater) {
        root = new FrameLayout(this);
        cameraContainer = new FrameLayout(this);
        cameraView = new AspectRatioTextureView(this);
        root.addView(cameraContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setBackgroundColor(0x99000000);
        status.setPadding(24, 16, 24, 16);
        status.setTextSize(13);
        status.setText("等待 Insta360 Air...\n陀螺仪: --");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(status, lp);
        return root;
    }

    @Override
    protected IAspectRatio getCameraView() {
        return cameraView;
    }

    @Override
    protected ViewGroup getCameraViewContainer() {
        return cameraContainer;
    }

    @Override
    protected CameraRequest getCameraRequest() {
        return new CameraRequest.Builder()
                .setPreviewWidth(1920)
                .setPreviewHeight(960)
                .setRenderMode(CameraRequest.RenderMode.OPENGL)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                .setAspectRatioShow(true)
                .setDefaultRotateType(RotateType.ANGLE_0)
                .create();
    }

    @Override
    public void onCameraState(MultiCameraClient.ICamera self, ICameraStateCallBack.State code, String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (status != null) {
                    status.setText("相机: " + code + (msg == null ? "" : " / " + msg));
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor g = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor a = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (g != null) sensorManager.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        if (a != null) sensorManager.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax = event.values[0]; ay = event.values[1]; az = event.values[2];
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gx = event.values[0]; gy = event.values[1]; gz = event.values[2];
            long ts = event.timestamp;
            float dt = lastGyroTs == 0L ? 0.01f : (ts - lastGyroTs) * 1e-9f;
            lastGyroTs = ts;
            if (dt > 0f && dt < 0.5f) {
                yaw += Math.toDegrees(gz * dt);
                if (yaw > 180f) yaw -= 360f;
                if (yaw < -180f) yaw += 360f;
            }
        }
        if (status != null) {
            status.setText(String.format("陀螺仪 gx=%.2f gy=%.2f gz=%.2f\n加速度 ax=%.2f ay=%.2f az=%.2f\nyaw=%.1f°",
                    gx, gy, gz, ax, ay, az, yaw));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
"""
for rel, content in F.items():
    path = os.path.join(BASE, rel.replace("/", os.sep))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
print("updated", len(F))
