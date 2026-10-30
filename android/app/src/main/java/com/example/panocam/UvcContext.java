package com.example.panocam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * AUSBC 3.2.7 的 USBMonitor 仍用两参数 registerReceiver 动态注册 USB 广播。
 * targetSdk 33+ 在 Android 13/14 上必须显式声明导出标志，否则抛 SecurityException，
 * 直接把 UVC 策略初始化打断。这里用一层 Context 包装补上该标志。
 */
public final class UvcContext extends ContextWrapper {

    public UvcContext(Context base) {
        super(base);
    }

    /** AUSBC 内部会调用 getApplicationContext() 保存 Context，这里必须返回自身，
     *  否则包装层被丢掉，registerReceiver 的补丁就不生效。 */
    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return super.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        }
        return super.registerReceiver(receiver, filter);
    }
}
