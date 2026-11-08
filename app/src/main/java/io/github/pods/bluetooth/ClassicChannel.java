package io.github.pods.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.pods.Diagnostics;
import io.github.pods.core.AirPodsProtocol;

@SuppressLint("MissingPermission")
public final class ClassicChannel {
    public interface Listener {
        void onSocketOpened();
        void onFrame(byte[] frame);
        void onFailure(String code);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Listener listener;
    private volatile BluetoothSocket socket;
    private volatile int generation;

    public ClassicChannel(Listener listener) { this.listener = listener; }

    public void connect(BluetoothDevice device, boolean secure) {
        close();
        final int token = generation;
        workers.execute(() -> {
            BluetoothSocket candidate = null;
            ScheduledFuture<?> deadline = null;
            AtomicBoolean timedOut = new AtomicBoolean();
            long started = SystemClock.elapsedRealtime();
            String phase = "socket creation";
            try {
                candidate = createSocket(device, secure);
                synchronized (this) {
                    if (token != generation) { closeSocket(candidate); return; }
                    socket = candidate;
                }
                BluetoothSocket connecting = candidate;
                deadline = timer.schedule(() -> {
                    timedOut.set(true);
                    closeSocket(connecting);
                }, 8, TimeUnit.SECONDS);
                phase = "connect";
                Diagnostics.record("Control socket connection started");
                candidate.connect();
                deadline.cancel(false);
                if (token != generation) return;
                phase = "handshake";
                Diagnostics.record("Control socket connected");
                candidate.getOutputStream().write(AirPodsProtocol.handshake());
                candidate.getOutputStream().write(AirPodsProtocol.features());
                candidate.getOutputStream().write(AirPodsProtocol.notifications());
                candidate.getOutputStream().flush();
                post(token, listener::onSocketOpened);

                phase = "read";
                AirPodsProtocol.Decoder decoder = new AirPodsProtocol.Decoder();
                byte[] buffer = new byte[2048];
                while (token == generation) {
                    int length = candidate.getInputStream().read(buffer);
                    if (length < 0) throw new IOException("Remote stream closed");
                    for (byte[] frame : decoder.feed(Arrays.copyOf(buffer, length))) {
                        post(token, () -> listener.onFrame(frame));
                    }
                }
            } catch (Exception | LinkageError error) {
                if (token != generation) return;
                Diagnostics.record("Control connection failed: phase=" + phase + ", elapsed="
                        + (SystemClock.elapsedRealtime() - started) + " ms, "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
                String code = timedOut.get() ? "CONNECTION_TIMEOUT" : candidate == null ? "SOCKET_UNAVAILABLE"
                        : "read".equals(phase) ? "CHANNEL_CLOSED" : "CONNECTION_FAILED";
                post(token, () -> listener.onFailure(code));
            } finally {
                if (deadline != null) deadline.cancel(false);
                closeSocket(candidate);
            }
        });
    }

    public void send(byte[] frame) {
        int token = generation;
        writer.execute(() -> {
            if (token != generation) return;
            BluetoothSocket current = socket;
            try {
                if (current == null || !current.isConnected()) throw new IOException("Socket is disconnected");
                current.getOutputStream().write(frame);
                current.getOutputStream().flush();
                Diagnostics.record("Control command written: " + (frame[6] & 0xFF));
            } catch (IOException | SecurityException error) {
                post(token, () -> listener.onFailure("WRITE_FAILED"));
            }
        });
    }

    private void post(int token, Runnable action) {
        main.post(() -> { if (token == generation) action.run(); });
    }

    public synchronized void close() {
        generation++;
        BluetoothSocket current = socket;
        socket = null;
        if (current != null) workers.execute(() -> closeSocket(current));
    }

    public void destroy() {
        close();
        writer.shutdownNow();
        timer.shutdownNow();
        workers.shutdown();
    }

    private static void closeSocket(BluetoothSocket value) {
        if (value == null) return;
        try { value.close(); }
        catch (IOException ignored) { /* 关闭已失效的通道不影响应用状态。 */ }
    }

    // 需要经典蓝牙 L2CAP；公开的 createL2capChannel 使用 BLE，不能代替此通道。
    // 此私有接口先通过 HiddenApiBypass 放行，调用失败仍按不可用处理。
    @SuppressLint("SoonBlockedPrivateApi")
    private static BluetoothSocket createSocket(BluetoothDevice device, boolean secure) throws Exception {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothDevice;");
        // 使用系统自己的 BR/EDR 工厂，让系统提供正确的文件描述符与安全参数。
        String name = secure ? "createL2capSocket" : "createInsecureL2capSocket";
        Method factory = BluetoothDevice.class.getDeclaredMethod(name, int.class);
        try {
            BluetoothSocket result = (BluetoothSocket) factory.invoke(device, 0x1001);
            Diagnostics.record("Classic L2CAP socket created: factory=" + name + ", psm=0x1001");
            return result;
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
}
