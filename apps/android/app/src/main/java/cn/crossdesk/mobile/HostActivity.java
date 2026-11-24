package cn.crossdesk.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private TextView statusView, idView, accessibilityView, captureView;
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
        captureView.setText("投屏：" + (HostCaptureService.running ? "进行中" : "未启动（开启被控时授权）"));
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
