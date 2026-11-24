package cn.crossdesk.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import java.io.File;
import android.view.Display;
import android.view.WindowManager;
import org.json.JSONObject;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Owns the device session while this device acts as a CrossDesk host
 * (controlled endpoint). It runs a presence-mode NativeSession; once the
 * signaling login yields the device ID it starts the native host peer with
 * login "deviceId@hostPassword", so any CrossDesk client can connect with
 * the device ID + connection password through the signaling/relay servers
 * (no LAN direct connection involved).
 *
 * Screen frames come from HostCaptureService (MediaProjection); input is
 * executed by HostInputService (accessibility). This service is the hub
 * between them and the native peer.
 */
final class HostService extends Service implements NativeSession.Listener {
    static final String ACTION_START = "cn.crossdesk.mobile.host.START";
    static final String ACTION_STOP = "cn.crossdesk.mobile.host.STOP";
    static final String ACTION_APPLY_CONFIG = "cn.crossdesk.mobile.host.APPLY_CONFIG";
    private static final String CHANNEL_ID = "crossdesk_host";
    private static final int NOTIFICATION_ID = 4201;
    private static final long RESTART_DELAY_MS = 10_000;

    interface Observer { void onHostStateChanged(); }
    static final Set<Observer> observers = new CopyOnWriteArraySet<>();
    static volatile HostService instance;
    private static volatile boolean runningFlag;

    static boolean isRunning() { return runningFlag; }

    volatile String deviceId = "";
    volatile String statusText = "未启动";
    volatile String connectedRemote = "";
    volatile boolean startRequested;
    volatile boolean hostPeerStarted;

    private final Handler main = new Handler(Looper.getMainLooper());
    private NativeSession session;
    private PowerManager.WakeLock wakeLock;
    private int restartAttempts;
    private AudioPlayer audioPlayer;
    private final HostAudio micAudio = new HostAudio();
    private Thread pumpThread;
    private volatile boolean pumpActive;
    private int lastFilePercent = -1;

    static void addObserver(Observer observer) { observers.add(observer); }
    static void removeObserver(Observer observer) { observers.remove(observer); }
    private void notifyObservers() { for (Observer o : observers) o.onHostStateChanged(); }

    /** Frame entry point used by HostCaptureService. */
    static boolean pushFrame(byte[] rgba, int width, int height, int stride) {
        HostService service = instance;
        if (service == null || service.session == null) return false;
        return service.session.hostFrame(rgba, width, height, stride);
    }

    /**
     * System-audio entry point used by HostCaptureService. The microphone,
     * when enabled for a voice call, takes precedence over system audio.
     */
    static boolean pushAudioFrame(byte[] frame) {
        HostService service = instance;
        if (service == null || service.session == null || service.connectedRemote.isEmpty()) return false;
        if (service.micAudio.isRunning()) return false;
        return service.session.hostAudio(frame);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        runningFlag = true;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "CrossDesk 被控服务", NotificationManager.IMPORTANCE_LOW));
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crossdesk:host");
            wakeLock.acquire();
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, buildNotification("正在连接服务器…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("正在连接服务器…"));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopHosting();
            return START_NOT_STICKY;
        }
        BootReceiver.scheduleWatchdog(this);
        if (ACTION_APPLY_CONFIG.equals(action)) {
            applyConfig();
            return START_STICKY;
        }
        startRequested = true;
        if (session == null) openSession();
        updateStatus(hostPeerStarted ? "被控已开启" : "正在连接服务器…");
        return START_STICKY;
    }

    /** Re-applies panel toggles (file transfer, microphone) to the live session. */
    private void applyConfig() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        if (session != null) session.hostFileEnabled(prefs.getBoolean("hostFileEnabled", true));
        applyMic(prefs);
    }

    private void applyMic(SharedPreferences prefs) {
        boolean want = prefs.getBoolean("hostMic", false)
                && !connectedRemote.isEmpty() && hasRecordPermission();
        if (want && !micAudio.isRunning()) {
            micAudio.startMic(frame -> {
                NativeSession current = session;
                if (current != null) current.hostAudio(frame);
            });
        } else if (!want && micAudio.isRunning()) {
            micAudio.stop();
        }
    }

    private boolean hasRecordPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Directory that receives files sent to this device: Downloads/CrossDesk. */
    private String receiveDirectory() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File dir = new File(downloads, "CrossDesk");
        if (!dir.exists()) dir.mkdirs();
        return dir.getAbsolutePath();
    }

    private void startFilePump() {
        stopFilePump();
        pumpActive = true;
        pumpThread = new Thread(() -> {
            while (pumpActive) {
                NativeSession current = session;
                if (current == null) break;
                current.hostPumpFiles();
                try { Thread.sleep(15); } catch (InterruptedException error) { break; }
            }
        }, "CrossDesk host file pump");
        pumpThread.start();
    }

    private void stopFilePump() {
        pumpActive = false;
        Thread worker = pumpThread;
        pumpThread = null;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(300); } catch (InterruptedException ignored) { }
        }
    }

    private void startAudioPlayer() {
        stopAudioPlayer();
        audioPlayer = new AudioPlayer();
    }

    private void stopAudioPlayer() {
        if (audioPlayer != null) { audioPlayer.close(); audioPlayer = null; }
    }

    private void openSession() {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        String host = prefs.getString("host", "api.crossdesk.cn");
        int port = prefs.getInt("port", 9099);
        session = new NativeSession(this, host, port, "", "", this);
        session.start();
    }

    private void closeSession() {
        stopFilePump();
        micAudio.stop();
        stopAudioPlayer();
        if (session != null) {
            try { session.hostStop(); } catch (Exception ignored) { }
            session.close();
            session = null;
        }
        hostPeerStarted = false;
    }

    private void stopHosting() {
        startRequested = false;
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("hostMode", false).apply();
        closeSession();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from recents: come back unless the user stopped hosting.
        if (BootReceiver.hostModeEnabled(this)) BootReceiver.scheduleRestart(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        boolean revive = BootReceiver.hostModeEnabled(this);
        startRequested = false;
        closeSession();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (instance == this) instance = null;
        runningFlag = false;
        // An intentional stop clears hostMode first (stopHosting), so reaching
        // here with hostMode still on means the system killed us: revive.
        if (revive) BootReceiver.scheduleRestart(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void updateStatus(String text) {
        statusText = text;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
        notifyObservers();
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, HostActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = deviceId.isEmpty() ? "CrossDesk 被控服务" : "CrossDesk 被控 · ID " + deviceId;
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void maybeStartHostPeer() {
        if (!startRequested || hostPeerStarted || session == null || deviceId.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        String passcode = prefs.getString("hostPassword", "");
        if (passcode.isEmpty()) {
            passcode = String.valueOf(100_000 + new java.util.Random().nextInt(900_000));
            prefs.edit().putString("hostPassword", passcode).apply();
        }
        String name = prefs.getString("hostName", "");
        if (name == null || name.isEmpty()) name = Build.MODEL;
        Point size = realDisplaySize();
        session.hostStart(deviceId + "@" + passcode, name, size.x, size.y);
        session.hostFileDirectory(receiveDirectory());
        session.hostFileEnabled(prefs.getBoolean("hostFileEnabled", true));
        hostPeerStarted = true;
        restartAttempts = 0;
        updateStatus("被控已开启，等待连接");
    }

    private Point realDisplaySize() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point(1280, 720);
        if (wm != null) {
            Display display = wm.getDefaultDisplay();
            Point real = new Point();
            display.getRealSize(real);
            if (real.x > 0 && real.y > 0) size = real;
        }
        return size;
    }

    // ------------------------------------------------------- NativeSession.Listener

    @Override public void signaling(int state, String id) {
        if (!id.isEmpty()) deviceId = id;
        if (state == 1) {
            updateStatus("已连接服务器");
            maybeStartHostPeer();
        } else if (state == 0 || state == 4) {
            updateStatus("正在连接服务器…");
        }
        notifyObservers();
    }

    @Override public void signalingEnded(String reason) {
        hostPeerStarted = false;
        updateStatus("连接中断：" + reason);
        if (startRequested && restartAttempts < 100) {
            restartAttempts++;
            main.postDelayed(() -> {
                if (startRequested && session == null) openSession();
                else if (startRequested && session != null) { closeSession(); openSession(); }
            }, RESTART_DELAY_MS);
        }
    }

    @Override public void hostConnected(String remote) {
        connectedRemote = remote;
        updateStatus("主控已连接：" + remote);
        startAudioPlayer();
        startFilePump();
        applyMic(getSharedPreferences("settings", MODE_PRIVATE));
    }

    @Override public void hostDisconnected(int status) {
        connectedRemote = "";
        stopFilePump();
        micAudio.stop();
        stopAudioPlayer();
        updateStatus("主控已断开，等待连接");
    }

    @Override public void hostAudio(byte[] pcm) {
        AudioPlayer player = audioPlayer;
        if (player != null) player.offer(pcm);
    }

    @Override public void fileTransfer(String name, double progress, boolean sending, File received) {
        if (progress < 0) {
            lastFilePercent = -1;
            updateStatus("文件传输失败：" + name);
            return;
        }
        if (progress >= 1) {
            lastFilePercent = -1;
            updateStatus(sending ? "文件已发送：" + name : "文件已接收：" + name);
            return;
        }
        int percent = (int) Math.round(progress * 100);
        if (percent != lastFilePercent && percent % 5 == 0) {
            lastFilePercent = percent;
            updateStatus((sending ? "正在发送 " : "正在接收 ") + name + " · " + percent + "%");
        }
    }

    @Override public void hostInput(String actionJson) {
        HostInputService.dispatch(actionJson);
    }

    @Override public void hostControl(String actionJson) {
        try {
            JSONObject action = new JSONObject(actionJson);
            if (action.optInt("type", -1) == 11) { // video_settings
                JSONObject vs = action.optJSONObject("video_settings");
                if (vs != null) HostCaptureService.setTargetFps(vs.optInt("frame_rate", 30));
            }
        } catch (Exception ignored) { }
    }

    @Override public void clipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("CrossDesk", text));
    }

    @Override public void status(String message) { }
    @Override public void connected() { }
    @Override public void ended(String reason) { signalingEnded(reason); }
    @Override public void data(JSONObject message) { }
    @Override public void videoSize(int width, int height) { }
    @Override public void statistics(RemoteNetworkStatistics.Snapshot value) { }
}
