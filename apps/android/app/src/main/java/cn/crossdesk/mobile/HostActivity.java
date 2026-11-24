package cn.crossdesk.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.security.SecureRandom;

/**
 * Control panel for host mode: shows the device ID and connection password,
 * starts/stops HostService, and runs the one-time permission flows that only
 * an activity can do (MediaProjection consent, accessibility settings,
 * battery-optimization exemption).
 */
public final class HostActivity extends Activity implements HostService.Observer {
    private static final int REQUEST_PROJECTION = 7001;

    private SharedPreferences prefs;
    private TextView statusView, idView, accessibilityView, captureView, fileAccessView;
    private EditText passwordEdit, nameEdit;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text("CrossDesk 被控模式", 22, true);
        root.addView(title);
        root.addView(text("开启后，其他 CrossDesk 客户端可用「本机 ID + 连接密码」远程控制这台设备。" +
                "连接经信令/中继服务器建立，不使用局域网直连。", 14, false));

        statusView = text("", 15, false);
        idView = text("", 17, true);
        accessibilityView = text("", 15, false);
        captureView = text("", 15, false);
        root.addView(statusView);
        root.addView(idView);
        root.addView(accessibilityView);
        root.addView(captureView);

        root.addView(text("连接密码", 14, true));
        passwordEdit = new EditText(this);
        passwordEdit.setSingleLine(true);
        passwordEdit.setText(prefs.getString("hostPassword", ""));
        root.addView(passwordEdit);
        Button generate = button("随机生成密码");
        generate.setOnClickListener(v -> passwordEdit.setText(newPassword()));
        root.addView(generate);

        root.addView(text("设备名称（主控端看到的名称）", 14, true));
        nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        String savedName = prefs.getString("hostName", "");
        nameEdit.setText(savedName == null || savedName.isEmpty() ? android.os.Build.MODEL : savedName);
        root.addView(nameEdit);

        Button start = button("开启被控");
        start.setOnClickListener(v -> startHosting());
        root.addView(start);
        Button projection = button("重新授权投屏");
        projection.setOnClickListener(v -> requestProjection());
        root.addView(projection);
        Button stop = button("停止被控");
        stop.setOnClickListener(v -> stopHosting());
        root.addView(stop);
        Button accessibility = button("打开无障碍设置");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);
        Button battery = button("忽略电池优化");
        battery.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        root.addView(battery);
        Button autostart = button("厂商自启动设置（国产平板保活关键）");
        autostart.setOnClickListener(v -> openVendorAutostart());
        root.addView(autostart);

        root.addView(text("功能开关", 14, true));
        root.addView(toggle("允许文件传输（主控可浏览 / 下载 / 上传本机文件）",
                "hostFileEnabled", true, this::applyConfigToService));
        root.addView(toggle("系统声音（本机播放的声音传给主控）",
                "hostSystemAudio", true, this::ensureRecordPermission));
        root.addView(toggle("麦克风（语音通话：主控能听到本机周围声音）",
                "hostMic", false, () -> { ensureRecordPermission(); applyConfigToService(); }));
        Button filesAccess = button("文件访问授权（浏览任意目录需要）");
        filesAccess.setOnClickListener(v -> openAllFilesAccess());
        root.addView(filesAccess);
        fileAccessView = text("", 15, false);
        root.addView(fileAccessView);

        root.addView(text("注意：安卓 14+ 在每次重启或应用进程被杀后，需要重新授权投屏一次；" +
                "无障碍开启后重启不失效。无人值守部署请同时开启被控并完成投屏授权。", 13, false));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        HostService.addObserver(this);
        render();
    }

    @Override protected void onPause() {
        HostService.removeObserver(this);
        super.onPause();
    }

    @Override public void onHostStateChanged() { runOnUiThread(this::render); }

    private void render() {
        HostService service = HostService.instance;
        boolean running = service != null && service.startRequested;
        statusView.setText("服务状态：" + (running ? service.statusText : "未开启"));
        idView.setText("本机 ID：" + (service != null && !service.deviceId.isEmpty()
                ? service.deviceId : "（连接服务器后显示）"));
        accessibilityView.setText("无障碍：" + (HostInputService.isConnected() ? "已开启" : "未开启（必须开启才能被控制）"));
        String captureError = prefs.getString("hostCaptureError", "");
        if (HostCaptureService.running) {
            captureView.setText("投屏：进行中");
        } else if (captureError != null && !captureError.isEmpty()) {
            captureView.setText("投屏：启动失败 - " + captureError);
        } else {
            captureView.setText("投屏：未启动（开启被控时授权）");
        }
        if (Build.VERSION.SDK_INT >= 30) {
            fileAccessView.setText("文件访问：" + (Environment.isExternalStorageManager()
                    ? "已授权全部文件" : "未授权（接收文件仅限「下载/CrossDesk」目录）"));
        } else {
            fileAccessView.setText("文件访问：安卓 10 以下使用存储权限");
        }
    }

    private Switch toggle(String label, String key, boolean defaultValue, Runnable after) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextSize(14);
        view.setChecked(prefs.getBoolean(key, defaultValue));
        view.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if (after != null) after.run();
        });
        return view;
    }

    private void applyConfigToService() {
        if (HostService.isRunning()) {
            startService(new Intent(this, HostService.class).setAction(HostService.ACTION_APPLY_CONFIG));
        }
    }

    private void ensureRecordPermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7002);
        }
    }

    private void openAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) { }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Exception ignored) { }
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    /** Vendor autostart managers — the make-or-break keep-alive setting on
     *  Chinese OEM tablets (Xiaomi / OPPO / vivo / Huawei / Samsung). */
    private void openVendorAutostart() {
        String[][] vendors = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] pair : vendors) {
            try {
                startActivity(new Intent().setComponent(new ComponentName(pair[0], pair[1])));
                return;
            } catch (Exception ignored) { }
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    private void startHosting() {
        String passcode = passwordEdit.getText().toString().trim();
        if (passcode.isEmpty()) {
            passcode = newPassword();
            passwordEdit.setText(passcode);
        }
        prefs.edit()
                .putString("hostPassword", passcode)
                .putString("hostName", nameEdit.getText().toString().trim())
                .putBoolean("hostMode", true)
                .apply();
        Intent service = new Intent(this, HostService.class).setAction(HostService.ACTION_START);
        startForegroundService(service);
        if (!HostCaptureService.running) requestProjection();
        render();
    }

    private void stopHosting() {
        prefs.edit().putBoolean("hostMode", false).apply();
        stopService(new Intent(this, HostCaptureService.class));
        stopService(new Intent(this, HostService.class));
        render();
    }

    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager != null) startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROJECTION && resultCode == RESULT_OK && data != null) {
            Intent capture = new Intent(this, HostCaptureService.class);
            capture.putExtra(HostCaptureService.EXTRA_RESULT_CODE, resultCode);
            capture.putExtra(HostCaptureService.EXTRA_DATA, data);
            startForegroundService(capture);
        }
        render();
    }

    private String newPassword() {
        return String.valueOf(100_000 + new SecureRandom().nextInt(900_000));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(4), 0, dp(4));
        button.setLayoutParams(params);
        button.setGravity(Gravity.CENTER);
        return button;
    }
}
