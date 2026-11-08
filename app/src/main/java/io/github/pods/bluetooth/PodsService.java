package io.github.pods.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.pods.Diagnostics;
import io.github.pods.MainActivity;
import io.github.pods.R;
import io.github.pods.core.AirPodsAdvertisement;
import io.github.pods.core.AirPodsIdentity;
import io.github.pods.core.AirPodsProtocol;
import io.github.pods.core.AutoConnection;
import io.github.pods.core.BatteryStatus;
import io.github.pods.core.ListeningMode;
import io.github.pods.core.ModeSession;
import io.github.pods.core.NearbyAirPods;
import io.github.pods.core.WearPlayback;
import io.github.pods.ui.StatusBatteryIcon;

@SuppressLint("MissingPermission")
public final class PodsService extends Service implements ClassicChannel.Listener {
    public enum ControlState { IDLE, CONNECTING, SYNCING, READY, FAILED }
    public interface Listener { void onStateChanged(); }

    public static final class Reading {
        public Integer level;
        public boolean charging;
        public boolean approximate;
        public long updated;

        boolean set(Integer value, boolean charging, boolean approximate) {
            // 缺失字段不覆盖最近有效电量，盒外广播经常不携带充电盒读数。
            if (value == null) return false;
            long now = SystemClock.elapsedRealtime();
            if (approximate && !this.approximate && updated > 0 && now - updated < 30000) return false;
            boolean changed = !Objects.equals(level, value) || this.charging != (value != null && charging)
                    || this.approximate != approximate || stale();
            level = value;
            this.charging = value != null && charging;
            this.approximate = approximate;
            updated = now;
            return changed;
        }

        public boolean stale() { return updated > 0 && SystemClock.elapsedRealtime() - updated > 60000; }
    }

    public final class LocalBinder extends Binder {
        public PodsService service() { return PodsService.this; }
    }

    private static final String BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String ACTIVE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";
    // 鸿蒙 4.x 的过滤器配置完成回调会复用 107；它不是扫描失败。
    private static final int HARMONY_FILTER_CONFIGURED = 107;
    private static volatile PodsService scanService;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final LocalBinder binder = new LocalBinder();
    private final List<Listener> listeners = new ArrayList<>();
    private final AutoConnection automatic = new AutoConnection();
    private final NearbyAirPods nearby = new NearbyAirPods();
    private final WearPlayback wearPlayback = new WearPlayback();
    private final Set<String> scanFormats = new HashSet<>();
    public final ModeSession mode = new ModeSession();
    public Reading left = new Reading();
    public Reading right = new Reading();
    public Reading chargingCase = new Reading();
    public ControlState control = ControlState.IDLE;
    public String deviceName = "你的 AirPods";
    public String modelName = "";
    public String message = "连接 AirPods 后，状态会自动显示。";
    public String errorCode;
    public boolean audioConnected;
    public boolean audioKnown;
    public volatile boolean scanning;
    public Integer systemBattery;
    public Boolean leftInEar;
    public Boolean rightInEar;
    public Boolean leftMicrophone;
    public Boolean rightMicrophone;
    public Boolean lidOpen;
    public Boolean allowOff;
    public boolean adaptiveSupported;
    public boolean nearbyBroadcast;
    public boolean broadcastAmbiguous;
    public int nearbyCandidates;
    public boolean autoPlaybackEnabled;
    private AudioManager audioManager;
    private String lastNotificationState;
    private boolean filteredScan;
    private boolean indirectScan;
    private PendingIntent scanIntent;
    private volatile long scanSession;
    private long lastScanDelivery;
    private volatile long lastTick;
    private long lastStallLog;
    private BluetoothAdapter adapter;
    private BluetoothDevice selected;
    private BluetoothProfile a2dp;
    private BluetoothProfile headset;
    private boolean a2dpRequested;
    private boolean headsetRequested;
    private BluetoothLeScanner scanner;
    private ClassicChannel channel;
    private Method batteryGetter;
    private byte[] identityKey;
    private boolean visible;
    private boolean monitoring;
    private boolean foreground;
    private boolean foregroundRequested;
    private volatile boolean musicActive;
    private volatile int audioMode = AudioManager.MODE_NORMAL;
    private int attempt;
    private int staleReadings;
    private int scanPackets;
    private int scanStatusPackets;
    private int matchedStatusPackets;
    private int nearbyStatusPackets;
    private long broadcastSeen;
    private long lidSeen;
    private long microphoneSeen;
    private NearbyAirPods.Sample appliedNearby;
    private long nextSystemRead;
    private long nextScanAt;
    private long nextScanFlush;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (lastTick > 0 && monitoring && now - lastTick > 15000) {
                Diagnostics.record("Monitoring scheduling gap: elapsed=" + (now - lastTick) + " ms");
            }
            lastTick = now;
            boolean changed = false;
            if (monitoring && audioConnected) {
                tryConnect();
                if (now >= nextSystemRead) {
                    changed |= readSystemBattery();
                    nextSystemRead = now + 30000;
                }
            }
            if (monitoring && !scanning && now >= nextScanAt) startScan();
            if (scanning && !indirectScan && now >= nextScanFlush) {
                BluetoothLeScanner currentScanner = scanner;
                if (currentScanner != null) {
                    scanWorker.execute(() -> {
                        try { currentScanner.flushPendingScanResults(scanCallback); }
                        catch (SecurityException | IllegalStateException error) {
                            main.post(() -> scanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED));
                        }
                    });
                }
                nextScanFlush = now + 5000;
            }
            if (scanning) changed |= refreshNearby(now);
            if (mode.expire(now)) {
                message = "耳机尚未确认切换。";
                Diagnostics.record("Listening mode confirmation timed out");
                changed = true;
            }
            if (broadcastSeen > 0 && now - broadcastSeen > 15000) {
                if (control != ControlState.READY) {
                    changed |= leftInEar != null || rightInEar != null;
                    leftInEar = null;
                    rightInEar = null;
                }
            }
            if (lidSeen > 0 && now - lidSeen > 15000) {
                changed |= lidOpen != null;
                lidOpen = null;
            }
            if (microphoneSeen > 0 && now - microphoneSeen > 15000) {
                changed |= leftMicrophone != null || rightMicrophone != null;
                leftMicrophone = null;
                rightMicrophone = null;
            }
            int stale = (left.stale() ? 1 : 0) | (right.stale() ? 2 : 0) | (chargingCase.stale() ? 4 : 0);
            changed |= staleReadings != stale;
            staleReadings = stale;
            if (autoPlaybackEnabled && audioConnected) {
                if (audioMode != AudioManager.MODE_NORMAL) wearPlayback.reset();
                else wearPlayback.playbackChanged(musicActive, now);
                wearPlayback.expire(now);
            }
            if (changed) notifyChanged();
            main.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                startScan();
                return;
            }
            if (BATTERY_CHANGED.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1);
                if (selected != null && selected.equals(device) && level >= 0 && level <= 100) {
                    systemBattery = level;
                    Diagnostics.record("System headset battery updated: " + level);
                    notifyChanged();
                }
                return;
            }
            reconcileAudio();
        }
    };

    private final AudioManager.AudioPlaybackCallback playbackCallback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configurations) {
            boolean playing = false;
            for (AudioPlaybackConfiguration configuration : configurations) {
                if (configuration.getAudioAttributes().getVolumeControlStream() == AudioManager.STREAM_MUSIC) {
                    playing = true;
                    break;
                }
            }
            musicActive = playing;
            wearPlayback.playbackChanged(playing, SystemClock.elapsedRealtime());
            audioWorker.execute(() -> {
                try { audioMode = audioManager.getMode(); }
                catch (RuntimeException error) { Diagnostics.record("Audio mode query unavailable: " + error.getClass().getSimpleName()); }
            });
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy;
            if (profile == BluetoothProfile.HEADSET) headset = proxy;
            reconcileAudio();
        }

        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) { a2dp = null; a2dpRequested = false; }
            if (profile == BluetoothProfile.HEADSET) { headset = null; headsetRequested = false; }
            reconcileAudio();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        channel = new ClassicChannel(this);
        audioManager = getSystemService(AudioManager.class);
        autoPlaybackEnabled = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("autoPlayback", true);
        audioManager.registerAudioPlaybackCallback(playbackCallback, main);
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/BluetoothDevice;", "Landroid/bluetooth/BluetoothA2dp;");
            batteryGetter = BluetoothDevice.class.getDeclaredMethod("getBatteryLevel");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Diagnostics.record("System battery reader unavailable: " + error.getClass().getSimpleName());
        }
        NotificationChannel batteryChannel = new NotificationChannel("battery", "电量与佩戴", NotificationManager.IMPORTANCE_DEFAULT);
        batteryChannel.setSound(null, null);
        batteryChannel.enableVibration(false);
        batteryChannel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(batteryChannel);
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        if (Build.VERSION.SDK_INT >= 30) filter.addAction(BluetoothDevice.ACTION_ALIAS_CHANGED);
        filter.addAction(ACTIVE_CHANGED);
        filter.addAction(BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
        main.post(tick);
        watchdog.scheduleAtFixedRate(() -> {
            long now = SystemClock.elapsedRealtime();
            long heartbeat = lastTick;
            if (!monitoring || heartbeat <= 0 || now - heartbeat < 10000 || now - lastStallLog < 10000) return;
            StackTraceElement[] stack = null;
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                if ("main".equals(entry.getKey().getName())) { stack = entry.getValue(); break; }
            }
            String top = stack == null || stack.length == 0 ? "unknown" : stack[0].toString();
            lastStallLog = now;
            Diagnostics.record("Main thread stalled: " + top);
        }, 10, 10, TimeUnit.SECONDS);
        Diagnostics.record("Automatic monitoring service created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        foregroundRequested = false;
        startForeground(1, notification());
        foreground = true;
        if (intent != null && "stop".equals(intent.getAction())) {
            monitoring = false;
            wearPlayback.reset();
            stopScan();
            closeControl();
            automatic.follow(null);
            message = "自动更新已暂停。";
            stopForegroundConnection();
            notifyChanged();
        } else if (!monitoring || !audioConnected) {
            stopForegroundConnection();
        } else startScan();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    public void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean bluetoothEnabled() {
        try { return adapter != null && adapter.isEnabled(); }
        catch (SecurityException error) { return false; }
    }

    public void setVisible(boolean value) {
        visible = value;
        if (value) {
            monitoring = true;
            reconcileAudio();
        } else if (!audioConnected || !foreground) stopScan();
        else startScan();
    }

    public void setAutoPlaybackEnabled(boolean enabled) {
        autoPlaybackEnabled = enabled;
        wearPlayback.reset();
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("autoPlayback", enabled).apply();
        Diagnostics.record("Wear playback automation changed: enabled=" + enabled);
        notifyChanged();
    }

    private void requestProfiles() {
        if (!a2dpRequested) a2dpRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP);
        if (!headsetRequested) headsetRequested = adapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET);
    }

    private static String safeName(BluetoothDevice device) {
        // 系统蓝牙页面重命名通常只更新本地别名，远端名称可能仍是旧值。
        String name = Build.VERSION.SDK_INT >= 30 ? device.getAlias() : null;
        if (name == null || name.trim().isEmpty()) name = device.getName();
        return name == null || name.trim().isEmpty() ? "AirPods" : name;
    }

    private static boolean recognizes(BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        String[] services = uuids == null ? null : new String[uuids.length];
        if (uuids != null) for (int i = 0; i < uuids.length; i++) services[i] = uuids[i].toString();
        return AirPodsIdentity.recognizes(device.getName(), services);
    }

    private BluetoothDevice activeDevice(List<BluetoothDevice> connected) {
        if (a2dp != null) {
            try {
                BluetoothDevice active = (BluetoothDevice) BluetoothA2dp.class.getDeclaredMethod("getActiveDevice").invoke(a2dp);
                if (active != null && connected.contains(active)) return recognizes(active) ? active : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* 部分系统不公开当前输出设备，继续检查连接列表。 */ }
        }
        if (selected != null && connected.contains(selected) && recognizes(selected)) return selected;
        for (BluetoothDevice device : connected) if (recognizes(device)) return device;
        return null;
    }

    private void reconcileAudio() {
        if (!monitoring) return;
        if (!hasPermissions() || !bluetoothEnabled()) {
            audioKnown = false;
            audioConnected = false;
            if (automatic.follow(null)) { closeControl(); selected = null; resetReadings(); }
            stopScan();
            stopForegroundConnection();
            message = hasPermissions() ? "打开蓝牙后，AirPods 会自动连接。" : "允许附近设备权限后，状态会自动显示。";
            notifyChanged();
            return;
        }
        try {
            requestProfiles();
            List<BluetoothDevice> connected = new ArrayList<>();
            if (a2dp != null) connected.addAll(a2dp.getConnectedDevices());
            if (headset != null) {
                for (BluetoothDevice device : headset.getConnectedDevices()) if (!connected.contains(device)) connected.add(device);
            }
            BluetoothDevice active = activeDevice(connected);
            audioKnown = a2dp != null || headset != null;
            audioConnected = active != null;
            if (automatic.follow(active == null ? null : active.getAddress())) {
                closeControl();
                resetReadings();
                selected = active;
                Diagnostics.record("Audio device followed automatically: connected=" + audioConnected);
            }
            if (active != null) {
                String name = safeName(active);
                if (!deviceName.equals(name)) {
                    deviceName = name;
                    Diagnostics.record("Device display name updated");
                }
                readSystemBattery();
                nextSystemRead = SystemClock.elapsedRealtime() + 30000;
                if (!foreground && !foregroundRequested) {
                    foregroundRequested = true;
                    try { startForegroundService(new Intent(this, PodsService.class)); }
                    catch (IllegalStateException | SecurityException error) {
                        foregroundRequested = false;
                        Diagnostics.record("Foreground monitoring unavailable: " + error.getClass().getSimpleName());
                    }
                }
                tryConnect();
            } else {
                message = "在系统蓝牙中连接 AirPods，状态会自动显示。";
                stopForegroundConnection();
                if (!visible) stopScan();
            }
            startScan();
        } catch (SecurityException error) {
            audioKnown = false;
            audioConnected = false;
            wearPlayback.reset();
            Diagnostics.record("Audio connection query denied");
        }
        notifyChanged();
    }

    private void tryConnect() {
        if (!monitoring || selected == null || !audioConnected || !hasPermissions() || !bluetoothEnabled()
                || !automatic.begin(SystemClock.elapsedRealtime())) return;
        closeControl();
        control = ControlState.CONNECTING;
        errorCode = null;
        message = "正在读取聆听模式…";
        Diagnostics.record("Automatic control connection started");
        channel.connect(selected, automatic.attempts() > 1);
        notifyChanged();
    }

    private void closeControl() {
        attempt++;
        channel.close();
        mode.reset();
        control = ControlState.IDLE;
        errorCode = null;
        allowOff = null;
        if (broadcastSeen == 0 || SystemClock.elapsedRealtime() - broadcastSeen > 15000) {
            leftInEar = null;
            rightInEar = null;
        }
    }

    private void stopForegroundConnection() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            lastNotificationState = null;
            stopSelf();
        }
    }

    private void resetReadings() {
        wearPlayback.reset();
        left = new Reading();
        right = new Reading();
        chargingCase = new Reading();
        systemBattery = null;
        identityKey = null;
        broadcastSeen = 0;
        lidSeen = 0;
        microphoneSeen = 0;
        nearby.reset();
        appliedNearby = null;
        nearbyBroadcast = false;
        broadcastAmbiguous = false;
        nearbyCandidates = 0;
        modelName = "";
        adaptiveSupported = false;
        leftInEar = null;
        rightInEar = null;
        leftMicrophone = null;
        rightMicrophone = null;
        lidOpen = null;
    }

    private boolean readSystemBattery() {
        if (selected == null || batteryGetter == null) return false;
        try {
            int level = (Integer) batteryGetter.invoke(selected);
            if (level < 0 || level > 100 || Objects.equals(systemBattery, level)) return false;
            systemBattery = level;
            Diagnostics.record("System headset battery read: " + level);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return false;
        }
    }

    @Override public void onSocketOpened() {
        int currentAttempt = attempt;
        control = ControlState.SYNCING;
        message = "正在读取耳机状态…";
        channel.send(AirPodsProtocol.requestIdentityKey());
        main.postDelayed(() -> {
            if (attempt == currentAttempt && control == ControlState.SYNCING) onFailure("NO_STATUS_RESPONSE");
        }, 10000);
        notifyChanged();
    }

    @Override public void onFrame(byte[] frame) {
        if (AirPodsProtocol.isFrame(frame, 0x31)) {
            byte[] key = AirPodsProtocol.identityKey(frame);
            if (key != null) {
                identityKey = key;
                if (nearbyBroadcast) {
                    clearNearbyReadings();
                    notifyChanged();
                }
                Diagnostics.record("Broadcast identity key received");
            }
            return;
        }
        control = ControlState.READY;
        automatic.ready();
        errorCode = null;
        if (AirPodsProtocol.isFrame(frame, 9) && frame.length == 11) {
            int identifier = frame[6] & 255;
            int value = frame[7] & 255;
            if (identifier == AirPodsProtocol.LISTENING_MODE) {
                boolean confirmed = mode.receive(value);
                if (value == 4) adaptiveSupported = true;
                if (confirmed) Diagnostics.record("Listening mode confirmed: " + value);
                if (confirmed || mode.pending() == null) message = value == 1
                        ? "降噪与通透已关闭。" : mode.confirmed() == null ? "聆听模式尚未获取。" : "";
            }
            if (identifier == AirPodsProtocol.ALLOW_OFF && (value == 1 || value == 2)) allowOff = value == 1;
        }
        for (AirPodsProtocol.Battery battery : AirPodsProtocol.batteries(frame)) {
            Reading reading = battery.component == AirPodsProtocol.LEFT ? left
                    : battery.component == AirPodsProtocol.RIGHT ? right : chargingCase;
            reading.set(battery.level, battery.charging, false);
        }
        if (AirPodsProtocol.isFrame(frame, 6) && frame.length == 8) {
            leftInEar = earState(frame[6]);
            rightInEar = earState(frame[7]);
            handleWearObservation(SystemClock.elapsedRealtime());
        }
        if (message.startsWith("正在读取")) message = "";
        notifyChanged();
    }

    private static Boolean earState(byte state) {
        return state == 0 ? Boolean.TRUE : state == 1 || state == 2 || state == 3 ? Boolean.FALSE : null;
    }

    @Override public void onFailure(String code) {
        closeControl();
        automatic.failed(SystemClock.elapsedRealtime());
        control = ControlState.FAILED;
        errorCode = code;
        message = "此系统暂时无法读取或切换聆听模式。";
        Diagnostics.record("Automatic control connection unavailable: " + code);
        notifyChanged();
    }

    public void setMode(ListeningMode requested) {
        if (control != ControlState.READY || !mode.begin(requested, SystemClock.elapsedRealtime())) return;
        int currentAttempt = attempt;
        message = "正在切换为" + requested.label + "…";
        Diagnostics.record("Listening mode requested: " + requested.name());
        if (requested == ListeningMode.OFF) {
            channel.send(AirPodsProtocol.control(AirPodsProtocol.ALLOW_OFF, 1));
            main.postDelayed(() -> {
                if (attempt == currentAttempt && control == ControlState.READY && mode.pending() == requested) {
                    channel.send(AirPodsProtocol.control(AirPodsProtocol.LISTENING_MODE, requested.value));
                }
            }, 350);
        } else channel.send(AirPodsProtocol.control(AirPodsProtocol.LISTENING_MODE, requested.value));
        notifyChanged();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { processScan(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) processScan(result);
        }
        @Override public void onScanFailed(int code) {
            long session = scanSession;
            main.post(() -> {
                if (session == scanSession) scanFailed(code);
            });
        }
    };

    static boolean receiveScan(Intent intent) {
        PodsService service = scanService;
        if (service == null || !service.scanning || !service.indirectScan
                || intent.getLongExtra("session", 0) != service.scanSession) return false;
        int error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0);
        if (service.isHarmonyFilterConfigured(error)) {
            Diagnostics.record("Background BLE filter configured");
            return true;
        }
        if (error != 0) {
            service.scanFailed(error);
            return true;
        }
        ArrayList<ScanResult> results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
        if (results != null) for (ScanResult result : results) if (result != null) service.processScan(result);
        return true;
    }

    private void scanFailed(int code) {
        if (isHarmonyFilterConfigured(code)) {
            Diagnostics.record("BLE filter configured");
            return;
        }
        stopScan();
        nextScanAt = SystemClock.elapsedRealtime() + 30000;
        Diagnostics.record("Automatic BLE scan failed: " + code);
    }

    private boolean isHarmonyFilterConfigured(int code) {
        return code == HARMONY_FILTER_CONFIGURED && "HUAWEI".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private void processScan(ScanResult result) {
        if (result.getScanRecord() == null) return;
        byte[] payload = result.getScanRecord().getManufacturerSpecificData(0x004C);
        if (payload == null) return;
        long session = scanSession;
        main.post(() -> {
            if (!scanning || session != scanSession) return;
            lastScanDelivery = SystemClock.elapsedRealtime();
            scanPackets++;
            String format = "length=" + payload.length + ", type=" + (payload.length > 0 ? payload[0] & 255 : -1);
            if (scanFormats.size() < 8 && scanFormats.add(format)) Diagnostics.record("BLE advertisement format: " + format);
            AirPodsAdvertisement data = AirPodsAdvertisement.parse(payload);
            if (data == null) return;
            scanStatusPackets++;
            if (scanStatusPackets <= 3) Diagnostics.record("AirPods status format: model=" + Integer.toHexString(data.modelId)
                    + ", connection=" + data.connectionState + ", rssi=" + result.getRssi());
            long now = SystemClock.elapsedRealtime();
            long receivedAt = result.getTimestampNanos() / 1000000;
            if (receivedAt <= 0 || receivedAt > now) receivedAt = now;
            if (now - receivedAt > 20000 || receivedAt < scanSession / 1000000) return;
            String address = result.getDevice().getAddress();
            nearby.observe(address, data, result.getRssi(), receivedAt);
            boolean changed;
            if (selected != null && AirPodsIdentity.matches(selected.getAddress(), address, identityKey)) {
                matchedStatusPackets++;
                if (matchedStatusPackets == 1) Diagnostics.record("AirPods broadcast matched automatically");
                broadcastAmbiguous = false;
                appliedNearby = null;
                changed = applyAdvertisement(data, receivedAt, false);
            } else changed = refreshNearby(now);
            if (changed) notifyChanged();
        });
    }

    private void startScan() {
        if (!(visible || foreground && audioConnected) || !monitoring || !hasPermissions() || !bluetoothEnabled()
                || SystemClock.elapsedRealtime() < nextScanAt) return;
        boolean indirect = !visible || !getSystemService(PowerManager.class).isInteractive();
        if (scanning && indirectScan == indirect) return;
        if (scanning) stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        try {
            // 页面内沿用已验证的回调；后台由系统广播交付，并过滤真实协议以兼容熄屏。
            indirectScan = indirect;
            filteredScan = indirect;
            scanSession = SystemClock.elapsedRealtimeNanos();
            List<ScanFilter> filters = indirect ? Collections.singletonList(new ScanFilter.Builder()
                    .setManufacturerData(0x004C, new byte[]{7, 25, 1}).build()) : Collections.emptyList();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(!indirect && adapter.isOffloadedScanBatchingSupported() ? 500 : 0).build();
            scanning = true;
            if (indirect) {
                Intent result = new Intent(this, ScanReceiver.class).setAction("io.github.pods.SCAN." + scanSession)
                        .putExtra("session", scanSession);
                // 蓝牙服务需要附加扫描结果；显式且不导出的接收器限制可变 Intent 的目标。
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                scanIntent = PendingIntent.getBroadcast(this, 2, result, flags);
                scanService = this;
                PendingIntent pending = scanIntent;
                BluetoothLeScanner currentScanner = scanner;
                long session = scanSession;
                scanWorker.execute(() -> {
                    if (!scanning || scanSession != session || scanIntent != pending) return;
                    try {
                        int code = currentScanner.startScan(filters, settings, pending);
                        if (code != 0) main.post(() -> scanFailed(code));
                    } catch (SecurityException | IllegalStateException error) {
                        main.post(() -> {
                            stopScan();
                            nextScanAt = SystemClock.elapsedRealtime() + 30000;
                            Diagnostics.record("Automatic BLE scan unavailable: " + error.getClass().getSimpleName());
                        });
                    }
                });
            } else {
                BluetoothLeScanner currentScanner = scanner;
                long session = scanSession;
                scanWorker.execute(() -> {
                    if (!scanning || scanSession != session || scanner != currentScanner) return;
                    try { currentScanner.startScan(filters, settings, scanCallback); }
                    catch (SecurityException | IllegalStateException error) {
                        main.post(() -> {
                            stopScan();
                            nextScanAt = SystemClock.elapsedRealtime() + 30000;
                            Diagnostics.record("Automatic BLE scan unavailable: " + error.getClass().getSimpleName());
                        });
                    }
                });
            }
            nextScanFlush = SystemClock.elapsedRealtime() + 5000;
            Diagnostics.record("Automatic BLE scan started: filtered=" + filteredScan
                    + ", callback=" + (indirect ? "intent" : "direct"));
        } catch (SecurityException | IllegalStateException error) {
            stopScan();
            nextScanAt = SystemClock.elapsedRealtime() + 30000;
            Diagnostics.record("Automatic BLE scan unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void stopScan() {
        BluetoothLeScanner currentScanner = scanner;
        PendingIntent currentIntent = scanIntent;
        boolean wasScanning = scanning;
        scanning = false;
        scanner = null;
        scanIntent = null;
        if (currentScanner != null && wasScanning) {
            scanWorker.execute(() -> {
            try {
                if (currentIntent != null) currentScanner.stopScan(currentIntent);
                else currentScanner.stopScan(scanCallback);
            }
            catch (SecurityException | IllegalStateException ignored) { /* 蓝牙关闭后，扫描可能已被系统停止。 */ }
            Diagnostics.record("Automatic BLE scan stopped: applePackets=" + scanPackets + ", statusPackets="
                    + scanStatusPackets + ", matchedPackets=" + matchedStatusPackets);
            });
        }
        if (currentIntent != null) currentIntent.cancel();
        if (scanService == this) scanService = null;
    }

    private boolean refreshNearby(long now) {
        // 身份已确认时继续严格匹配，无密钥路径只用于独立的附近状态显示。
        if (identityKey != null || !nearbyBroadcast && broadcastSeen > 0 && now - broadcastSeen <= 15000) return false;
        NearbyAirPods.Selection selection = nearby.select(now);
        boolean ambiguous = selection.state == NearbyAirPods.State.AMBIGUOUS;
        boolean changed = nearbyCandidates != selection.candidates || broadcastAmbiguous != ambiguous;
        nearbyCandidates = selection.candidates;
        if (broadcastAmbiguous != ambiguous) {
            Diagnostics.record("Nearby AirPods candidates changed: count=" + selection.candidates + ", ambiguous=" + ambiguous);
        }
        broadcastAmbiguous = ambiguous;
        if (selection.state == NearbyAirPods.State.SINGLE) {
            if (appliedNearby != selection.sample) {
                appliedNearby = selection.sample;
                nearbyStatusPackets++;
                changed |= applyAdvertisement(selection.sample.data, selection.sample.receivedAt, true);
            }
        } else if (nearbyBroadcast && selection.state != NearbyAirPods.State.NONE) {
            clearNearbyReadings();
            changed = true;
        }
        return changed;
    }

    private void clearNearbyReadings() {
        wearPlayback.reset();
        if (left.approximate) left = new Reading();
        if (right.approximate) right = new Reading();
        if (chargingCase.approximate) chargingCase = new Reading();
        if (control != ControlState.READY) {
            leftInEar = null;
            rightInEar = null;
        }
        lidOpen = null;
        leftMicrophone = null;
        rightMicrophone = null;
        microphoneSeen = 0;
        modelName = "";
        broadcastSeen = 0;
        lidSeen = 0;
        appliedNearby = null;
        nearbyBroadcast = false;
    }

    private boolean applyAdvertisement(AirPodsAdvertisement data, long receivedAt, boolean fromNearby) {
        broadcastSeen = receivedAt;
        boolean changed = !modelName.equals(data.model) || nearbyBroadcast != fromNearby;
        nearbyBroadcast = fromNearby;
        modelName = data.model;
        if (!fromNearby) adaptiveSupported = adaptiveSupported || data.supportsAdaptive();
        changed |= left.set(data.leftBattery, data.leftCharging, true);
        changed |= right.set(data.rightBattery, data.rightCharging, true);
        changed |= chargingCase.set(data.caseBattery, data.caseCharging, true);
        if (data.lidOpen != null) {
            changed |= !Objects.equals(lidOpen, data.lidOpen);
            lidOpen = data.lidOpen;
            lidSeen = receivedAt;
        }
        if (control != ControlState.READY) {
            changed |= !Objects.equals(leftInEar, data.leftInEar) || !Objects.equals(rightInEar, data.rightInEar);
            leftInEar = data.leftInEar;
            rightInEar = data.rightInEar;
        }
        changed |= !Objects.equals(leftMicrophone, data.leftMicrophone) || !Objects.equals(rightMicrophone, data.rightMicrophone);
        leftMicrophone = data.leftMicrophone;
        rightMicrophone = data.rightMicrophone;
        microphoneSeen = receivedAt;
        if (changed) Diagnostics.record("AirPods broadcast status updated: nearby=" + fromNearby
                + ", left=" + data.leftBattery + ", right=" + data.rightBattery + ", case=" + data.caseBattery
                + ", leftInEar=" + data.leftInEar + ", rightInEar=" + data.rightInEar
                + ", leftMicrophone=" + data.leftMicrophone + ", rightMicrophone=" + data.rightMicrophone);
        if (control != ControlState.READY) handleWearObservation(receivedAt);
        return changed;
    }

    private void handleWearObservation(long receivedAt) {
        if (!autoPlaybackEnabled || !monitoring || !audioConnected || control != ControlState.READY && broadcastAmbiguous
                || audioMode != AudioManager.MODE_NORMAL) {
            wearPlayback.reset();
            return;
        }
        WearPlayback.Action action = wearPlayback.observe(leftInEar, rightInEar, receivedAt,
                SystemClock.elapsedRealtime(), musicActive, control == ControlState.READY);
        if (action == WearPlayback.Action.NONE) return;
        try {
            // 确认 AirPods 仍连接后发送明确的媒体键，避免播放切换键反转状态。
            if (a2dp == null || selected == null || !a2dp.getConnectedDevices().contains(selected)) {
                wearPlayback.reset();
                return;
            }
            int key = action == WearPlayback.Action.PAUSE ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
            long at = SystemClock.uptimeMillis();
            audioWorker.execute(() -> {
                try {
                    audioManager.dispatchMediaKeyEvent(new KeyEvent(at, at, KeyEvent.ACTION_DOWN, key, 0));
                    audioManager.dispatchMediaKeyEvent(new KeyEvent(at, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0));
                } catch (RuntimeException error) {
                    main.post(wearPlayback::reset);
                    Diagnostics.record("Wear media command unavailable: " + error.getClass().getSimpleName());
                }
            });
            Diagnostics.record("Wear media command dispatched: " + action);
        } catch (RuntimeException error) { wearPlayback.reset(); }
    }

    public String diagnosticReport() {
        return Diagnostics.report("control=" + control + ", audio=" + (audioKnown ? audioConnected : "unknown")
                + ", mode=" + mode.confirmed() + ", pending=" + mode.pending() + ", error=" + errorCode
                + ", scanning=" + scanning + ", applePackets=" + scanPackets + ", statusPackets=" + scanStatusPackets
                + ", visible=" + visible + ", foreground=" + foreground + ", screenFiltered=" + filteredScan
                + ", scanCallback=" + (indirectScan ? "intent" : "direct")
                + ", scanDeliveryAgeMs=" + (lastScanDelivery == 0 ? -1 : SystemClock.elapsedRealtime() - lastScanDelivery)
                + ", left=" + left.level + ", right=" + right.level + ", case=" + chargingCase.level
                + ", leftInEar=" + leftInEar + ", rightInEar=" + rightInEar
                + ", matchedPackets=" + matchedStatusPackets + ", identityKey=" + (identityKey != null)
                + ", nearbyPackets=" + nearbyStatusPackets + ", nearbyCandidates=" + nearbyCandidates
                + ", nearbyBroadcast=" + nearbyBroadcast + ", broadcastAmbiguous=" + broadcastAmbiguous
                + ", leftMicrophone=" + leftMicrophone + ", rightMicrophone=" + rightMicrophone
                + ", autoPlayback=" + autoPlaybackEnabled + ", resumePending=" + wearPlayback.canResume()
                + ", musicActive=" + musicActive + ", audioMode=" + audioMode
                + ", systemBattery=" + systemBattery);
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(diagnosticReport());
    }

    private Integer statusBattery() {
        Integer a = left.stale() ? null : left.level;
        Integer b = right.stale() ? null : right.level;
        return BatteryStatus.level(a, b, systemBattery);
    }

    private String batteryText(String label, Reading reading) {
        return label + " " + (reading.level == null ? "—" : reading.level + "%"
                + (reading.stale() ? "（上次）" : reading.charging ? " ⚡" : ""));
    }

    private String notificationSummary() {
        if (left.level == null && right.level == null && chargingCase.level == null) {
            return systemBattery == null ? "耳机已连接 · 正在读取电量" : "耳机电量 " + systemBattery + "%";
        }
        return batteryText("左耳", left) + " · " + batteryText("右耳", right) + " · " + batteryText("盒", chargingCase);
    }

    private String notificationDetails() {
        return batteryText("左耳", left) + earDetail(left, leftInEar) + "\n"
                + batteryText("右耳", right) + earDetail(right, rightInEar) + "\n"
                + batteryText("充电盒", chargingCase) + (lidOpen == null ? "" : lidOpen ? " · 已开盖" : " · 已合盖");
    }

    private String earDetail(Reading reading, Boolean inEar) {
        return reading.charging && !reading.stale() ? " · 充电中"
                : inEar == null ? "" : inEar ? " · 佩戴中" : " · 未佩戴";
    }

    private String notificationState() {
        return deviceName + "|" + notificationSummary() + "|" + notificationDetails() + "|" + statusBattery() + "|" + autoPlaybackEnabled;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PodsService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Integer percent = statusBattery();
        lastNotificationState = notificationState();
        Notification.Builder builder = new Notification.Builder(this, "battery")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(deviceName)
                .setContentText(notificationSummary())
                .setStyle(new Notification.BigTextStyle().bigText(notificationDetails()))
                .setSubText(autoPlaybackEnabled ? "摘戴自动播放" : null)
                .setContentIntent(open).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setBadgeIconType(Notification.BADGE_ICON_NONE)
                .addAction(new Notification.Action.Builder(null, "停止自动更新", stop).build());
        if (percent != null) builder.setSmallIcon(StatusBatteryIcon.create(percent));
        return builder.build();
    }

    private void notifyChanged() {
        if (foreground && !Objects.equals(lastNotificationState, notificationState())) {
            getSystemService(NotificationManager.class).notify(1, notification());
        }
        for (Listener listener : new ArrayList<>(listeners)) listener.onStateChanged();
    }

    @Override public void onDestroy() {
        monitoring = false;
        stopScan();
        main.removeCallbacksAndMessages(null);
        channel.destroy();
        scanWorker.shutdownNow();
        audioWorker.shutdownNow();
        watchdog.shutdownNow();
        audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        unregisterReceiver(receiver);
        if (adapter != null) {
            if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        }
        super.onDestroy();
    }
}
