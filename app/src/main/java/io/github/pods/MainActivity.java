package io.github.pods;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;

import io.github.pods.bluetooth.PodsService;
import io.github.pods.core.ListeningMode;
import io.github.pods.ui.BatteryIcon;
import io.github.pods.ui.ModeButton;
import io.github.pods.ui.PodsIllustration;
import io.github.pods.ui.Ui;

public final class MainActivity extends Activity implements PodsService.Listener {
    private static final int PERMISSIONS = 10;
    private PodsService service;
    private boolean bound;
    private boolean permissionRequested;
    private boolean notificationRequested;
    private LinearLayout content;
    private TextView deviceName;
    private TextView model;
    private TextView modeBadge;
    private TextView message;
    private TextView setupButton;
    private final BatteryIcon[] batteryIcons = new BatteryIcon[3];
    private final TextView[] batteryValues = new TextView[3];
    private final TextView[] batteryDetails = new TextView[3];
    private final TextView[] batteryMeta = new TextView[3];
    private final EnumMap<ListeningMode, ModeButton> modes = new EnumMap<>(ListeningMode.class);
    private PodsIllustration illustration;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PodsService.LocalBinder) binder).service();
            service.addListener(MainActivity.this);
            prepareBluetooth();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            render();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        permissionRequested = state != null && state.getBoolean("permissionRequested");
        notificationRequested = state != null && state.getBoolean("notificationRequested");
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        buildScreen();
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permissionRequested", permissionRequested);
        state.putBoolean("notificationRequested", notificationRequested);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PodsService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        if (service != null) prepareBluetooth();
    }

    @Override protected void onStop() {
        if (service != null) {
            service.removeListener(this);
            service.setVisible(false);
        }
        if (bound) unbindService(connection);
        bound = false;
        service = null;
        super.onStop();
    }

    private void buildScreen() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        content = Ui.column(this);
        content.setPadding(dp(24), dp(10), dp(24), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(root);

        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "Pods", 28, Ui.INK, true);
        title.setLetterSpacing(-0.035f);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = Ui.button(this, "···", Color.TRANSPARENT, Ui.SECONDARY);
        more.setTextSize(26);
        more.setPadding(0, 0, 0, 0);
        more.setContentDescription("更多");
        more.setOnClickListener(view -> showMenu());
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        content.addView(header);

        illustration = new PodsIllustration(this);
        add(illustration, -1, 138, 4);
        deviceName = Ui.text(this, "你的 AirPods", 21, Ui.INK, true);
        deviceName.setGravity(Gravity.CENTER);
        deviceName.setMaxLines(2);
        add(deviceName, -1, -2, 0);
        model = Ui.text(this, "等待耳机连接", 12, Ui.SECONDARY, false);
        model.setGravity(Gravity.CENTER);
        add(model, -1, -2, 6);

        LinearLayout batteryCard = Ui.row(this);
        batteryCard.setPadding(dp(8), dp(18), dp(8), dp(18));
        batteryCard.setBackground(Ui.surface(this, Color.WHITE, 22));
        String[] labels = {"左耳", "右耳", "充电盒"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout cell = Ui.column(this);
            cell.setGravity(Gravity.CENTER);
            TextView label = Ui.text(this, labels[i], 11, Ui.SECONDARY, true);
            label.setGravity(Gravity.CENTER);
            cell.addView(label);
            LinearLayout value = Ui.row(this);
            value.setGravity(Gravity.CENTER);
            batteryIcons[i] = new BatteryIcon(this);
            value.addView(batteryIcons[i], new LinearLayout.LayoutParams(dp(28), dp(16)));
            batteryValues[i] = Ui.text(this, "—", 20, Ui.INK, true);
            batteryValues[i].setGravity(Gravity.CENTER);
            batteryValues[i].setSingleLine(true);
            LinearLayout.LayoutParams percentageParams = wrap();
            percentageParams.setMarginStart(dp(6));
            value.addView(batteryValues[i], percentageParams);
            LinearLayout.LayoutParams valueParams = wrap();
            valueParams.topMargin = dp(8);
            cell.addView(value, valueParams);
            batteryDetails[i] = Ui.text(this, "未获取", 11, Ui.SECONDARY, false);
            batteryDetails[i].setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams detailParams = wrap();
            detailParams.topMargin = dp(8);
            cell.addView(batteryDetails[i], detailParams);
            batteryMeta[i] = Ui.text(this, "", 10, Ui.SECONDARY, false);
            batteryMeta[i].setGravity(Gravity.CENTER);
            batteryMeta[i].setMinLines(1);
            LinearLayout.LayoutParams metaParams = wrap();
            metaParams.topMargin = dp(4);
            cell.addView(batteryMeta[i], metaParams);
            batteryCard.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        add(batteryCard, -1, -2, 20);

        LinearLayout section = Ui.row(this);
        section.addView(Ui.text(this, "聆听模式", 16, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        modeBadge = Ui.text(this, "未获取", 11, Ui.SECONDARY, false);
        section.addView(modeBadge);
        add(section, -1, -2, 22);
        ModeButton off = modeButton(ListeningMode.OFF, true);
        off.setMinimumHeight(dp(66));
        add(off, -1, -2, 12);
        LinearLayout choices = Ui.row(this);
        for (ListeningMode mode : new ListeningMode[]{ListeningMode.NOISE_CANCELLATION, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE}) {
            ModeButton button = modeButton(mode, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(70), 1);
            if (choices.getChildCount() > 0) params.setMarginStart(dp(8));
            choices.addView(button, params);
        }
        add(choices, -1, -2, 8);
        message = Ui.text(this, "", 12, Ui.SECONDARY, false);
        message.setLineSpacing(dp(3), 1);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(message, -1, -2, 12);
        setupButton = Ui.button(this, "允许访问耳机", Ui.PALE_BLUE, Ui.BLUE);
        setupButton.setOnClickListener(view -> resolveSetup());
        add(setupButton, -1, -2, 10);
    }

    private ModeButton modeButton(ListeningMode mode, boolean wide) {
        ModeButton button = new ModeButton(this, mode, wide);
        modes.put(mode, button);
        button.setOnClickListener(view -> { if (service != null) service.setMode(mode); });
        return button;
    }

    @Override public void onStateChanged() { render(); }

    private void render() {
        if (deviceName == null) return;
        boolean ready = service != null && service.control == PodsService.ControlState.READY;
        boolean busy = service != null && (service.control == PodsService.ControlState.CONNECTING || service.control == PodsService.ControlState.SYNCING);
        boolean connected = service != null && service.audioConnected;
        illustration.setConnected(connected);
        deviceName.setText(service == null ? "你的 AirPods" : service.deviceName);
        model.setText(service != null && !service.modelName.isEmpty()
                ? service.modelName + (connected ? " · 已连接" : " · 未连接")
                : service != null && service.broadcastAmbiguous ? "发现多副 AirPods · 等待识别"
                : connected ? "已连接" : "等待耳机连接");
        modeBadge.setText(ready ? service.mode.pending() != null ? "正在切换" : service.mode.confirmed() == null ? "未获取" : service.mode.confirmed().label
                : busy ? "读取中" : "未获取");
        for (ListeningMode mode : ListeningMode.values()) {
            modes.get(mode).bind(ready && service.mode.pending() == null && (mode != ListeningMode.ADAPTIVE || service.adaptiveSupported),
                    ready && service.mode.confirmed() == mode, ready && service.mode.pending() == mode);
        }
        String explanation = service == null ? "连接 AirPods 后，状态会自动显示。" : service.message;
        boolean permissions = hasBluetoothPermissions();
        boolean bluetooth = service != null && service.bluetoothEnabled();
        boolean location = Build.VERSION.SDK_INT >= 31 || getSystemService(LocationManager.class).isLocationEnabled();
        boolean setupNeeded = !permissions || service != null && (!bluetooth || !location);
        setupButton.setVisibility(setupNeeded ? View.VISIBLE : View.GONE);
        setupButton.setText(!permissions ? "允许访问耳机" : !bluetooth ? "打开蓝牙" : "打开定位开关");
        if (!permissions) explanation = "允许附近设备权限，以自动读取耳机状态。";
        else if (!location) explanation = "此系统需要打开定位开关才能读取蓝牙广播。";
        message.setText(explanation);
        message.setVisibility(explanation.isEmpty() ? View.GONE : View.VISIBLE);
        PodsService.Reading[] readings = service == null ? null : new PodsService.Reading[]{service.left, service.right, service.chargingCase};
        for (int i = 0; i < 3; i++) {
            PodsService.Reading reading = readings == null ? null : readings[i];
            boolean known = reading != null && reading.level != null;
            boolean stale = known && reading.stale();
            boolean charging = known && reading.charging && !stale;
            batteryIcons[i].bind(known ? reading.level : null, charging, stale);
            batteryValues[i].setText(known ? reading.level + "%" : "—");
            batteryValues[i].setTextColor(stale || !known ? Ui.SECONDARY : reading.level <= 20 ? 0xFFBF4A3A : Ui.INK);
            if (i < 2) {
                Boolean inEar = service == null ? null : i == 0 ? service.leftInEar : service.rightInEar;
                Boolean microphone = service == null ? null : i == 0 ? service.leftMicrophone : service.rightMicrophone;
                batteryDetails[i].setText(charging ? "充电中" : inEar == null ? "状态未获取" : inEar ? "佩戴中" : "未佩戴");
                batteryDetails[i].setTextColor(charging ? Ui.GREEN : Ui.SECONDARY);
                batteryMeta[i].setText(Boolean.TRUE.equals(microphone) ? "主麦克风" : stale ? "上次读数" : "");
                batteryMeta[i].setTextColor(Ui.SECONDARY);
            } else {
                boolean lidKnown = service != null && service.lidOpen != null;
                batteryDetails[i].setText(lidKnown ? service.lidOpen ? "已开盖" : "已合盖" : "");
                batteryDetails[i].setVisibility(lidKnown ? View.VISIBLE : View.INVISIBLE);
                batteryDetails[i].setTextColor(Ui.SECONDARY);
                batteryMeta[i].setText(charging ? "充电中" : stale ? "上次读数" : "");
                batteryMeta[i].setTextColor(charging ? Ui.GREEN : Ui.SECONDARY);
            }
            batteryMeta[i].setVisibility(batteryMeta[i].length() == 0 ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void prepareBluetooth() {
        if (service == null) return;
        service.setVisible(true);
        if (!hasBluetoothPermissions() && !permissionRequested) requestBluetoothPermissions();
        else if (hasBluetoothPermissions() && Build.VERSION.SDK_INT >= 33 && !notificationRequested
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequested = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 12);
        }
        render();
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        permissionRequested = true;
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, PERMISSIONS);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS);
    }

    private void resolveSetup() {
        if (!hasBluetoothPermissions()) {
            String permission = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.ACCESS_FINE_LOCATION;
            if (permissionRequested && !shouldShowRequestPermissionRationale(permission)) openAppSettings();
            else requestBluetoothPermissions();
        } else if (service != null && !service.bluetoothEnabled()) {
            try { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 11); }
            catch (SecurityException | android.content.ActivityNotFoundException error) { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS); }
        } else openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == PERMISSIONS && hasBluetoothPermissions()) prepareBluetooth();
        else render();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 11) prepareBluetooth();
    }

    private void showMenu() {
        String playback = "摘戴自动播放 · " + (service == null || service.autoPlaybackEnabled ? "已开启" : "已关闭");
        new AlertDialog.Builder(this).setTitle("Pods").setItems(new String[]{playback, "蓝牙设置", "连接说明", "分享诊断", "关于 Pods"}, (dialog, which) -> {
            switch (which) {
                case 0 -> {
                    if (service != null) {
                        service.setAutoPlaybackEnabled(!service.autoPlaybackEnabled);
                        toast(service.autoPlaybackEnabled ? "摘戴自动播放已开启" : "摘戴自动播放已关闭");
                    }
                }
                case 1 -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS);
                case 2 -> showHelp();
                case 3 -> shareDiagnostics();
                case 4 -> showAbout();
                default -> { }
            }
        }).setNegativeButton("完成", null).show();
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle("连接与聆听模式")
                .setMessage("Pods 自动跟随系统正在使用的 AirPods，更新电量、充电、佩戴、主麦克风和盒盖状态。多副耳机同时出现时，会等待识别。\n\n连接后，状态栏数字显示左右耳较低电量，下拉通知查看各组件读数。图标的颜色和显示位置由系统管理。\n\n双耳都摘下时暂停，戴回任意一只继续。仅恢复由 Pods 暂停的播放；首次佩戴、原本已暂停、断线或候选冲突不会主动播放。可在更多菜单关闭「摘戴自动播放」。播放器需响应系统媒体键。\n\n「主麦克风」表示耳机报告的主麦克风所在侧，不表示正在录音。入盒充电或状态过期时不显示此标记。\n\n广播电量以 10% 为单位。缺失电量保留最近有效读数，超过一分钟标注为上次读数。盒电量在开盖、耳机入盒时更容易获取。\n\n「关闭」会停止主动降噪与通透，耳塞本身仍有被动隔音。本次 Mate 60 Pro 的模式控制连接仍未接通。可借用 iPhone 或 iPad 启用「关闭聆听模式」，在型号支持时将其加入耳机长按切换列表，再回到华为使用。")
                .setPositiveButton("知道了", null).show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle("Pods  0.1.6")
                .setMessage("为耳机留一点安静。\n\n原生运行，无广告，无联网权限。诊断由你手动分享，隐去设备地址，不含身份密钥。\n\n参考 LibrePods 的自动连接与地址验证、CAPod 的广播与扫描兼容处理，以及 AAP Protocol Definition。应用按 GPL-3.0-or-later 开源。\n\nPods 与 Apple 及上述项目无隶属关系。")
                .setPositiveButton("完成", null).show();
    }

    private void shareDiagnostics() {
        String report = service == null ? Diagnostics.report("service=unbound") : service.diagnosticReport();
        File file = new File(getCacheDir(), "diagnostics.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(report.getBytes(StandardCharsets.UTF_8));
            Uri uri = Uri.parse("content://" + getPackageName() + ".diagnostics/report");
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("Pods diagnostics", uri));
            startActivity(Intent.createChooser(share, "分享连接诊断"));
        } catch (IOException | android.content.ActivityNotFoundException error) { toast("暂时无法分享诊断。"); }
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException error) { toast("请在系统设置中打开对应页面。"); }
    }

    private void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
        catch (android.content.ActivityNotFoundException error) { toast("请在系统设置中允许附近设备权限。"); }
    }

    private int dp(float value) { return Ui.dp(this, value); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void add(View view, int width, int height, int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        params.topMargin = dp(marginTop);
        content.addView(view, params);
    }
}
