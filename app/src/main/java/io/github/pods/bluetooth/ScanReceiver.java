package io.github.pods.bluetooth;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import io.github.pods.Diagnostics;

/** 后台扫描由系统广播交付，让结果不依赖应用线程持续运行。 */
public final class ScanReceiver extends BroadcastReceiver {
    @SuppressLint("MissingPermission")
    @Override public void onReceive(Context context, Intent intent) {
        if (PodsService.receiveScan(intent)) return;
        // 服务已停止或进程被回收时，撤销遗留扫描，不因旧结果重新启动监测。
        int flags = PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pending = PendingIntent.getBroadcast(context, 2,
                new Intent(context, ScanReceiver.class).setAction(intent.getAction()), flags);
        if (pending == null) return;
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            BluetoothLeScanner scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner != null) scanner.stopScan(pending);
            Diagnostics.record("Orphaned background scan stopped");
        } catch (SecurityException | IllegalStateException error) {
            Diagnostics.record("Orphaned scan cleanup unavailable: " + error.getClass().getSimpleName());
        } finally {
            pending.cancel();
        }
    }
}
