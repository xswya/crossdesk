package cn.crossdesk.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import java.nio.ByteBuffer;

/**
 * Captures the screen with MediaProjection and feeds RGBA frames to the
 * native host peer (HostService.pushFrame). Runs as a mediaProjection
 * foreground service; the projection consent result must be supplied by
 * HostActivity, and on Android 14+ consent is needed again after every
 * process restart — the service simply stays stopped until then.
 */
final class HostCaptureService extends Service {
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "crossdesk_host_capture";
    private static final int NOTIFICATION_ID = 4202;

    static volatile boolean running;
    private static volatile int targetFps = 30;

    static void setTargetFps(int fps) { targetFps = fps == 60 ? 60 : 30; }
    static int targetFps() { return targetFps; }

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private final HostAudio systemAudio = new HostAudio();
    private HandlerThread thread;
    private Handler handler;
    private long lastFrameMs;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "CrossDesk 投屏", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CrossDesk 正在共享屏幕")
                .setContentText("远程控制会话可见到本机画面")
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        thread = new HandlerThread("crossdesk-host-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || running) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) { stopSelf(); return START_NOT_STICKY; }
        projection = manager.getMediaProjection(resultCode, data);
        // Android 14+ requires a registered callback before createVirtualDisplay.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, handler);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);

        reader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        virtualDisplay = projection.createVirtualDisplay(
                "CrossDeskHost", size.x, size.y, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, handler);
        running = true;
        startSystemAudio();
        return START_STICKY;
    }

    /**
     * System-audio capture rides the same MediaProjection consent
     * (AudioPlaybackCapture, Android 10+). Frames flow to the host peer via
     * HostService, which drops them while the voice-call microphone is live.
     */
    private void startSystemAudio() {
        boolean enabled = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("hostSystemAudio", true);
        boolean granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!enabled || !granted) return;
        systemAudio.startSystem(projection, HostService::pushAudioFrame);
    }

    private void onImage(ImageReader imageReader) {
        long now = android.os.SystemClock.uptimeMillis();
        long interval = 1000 / Math.max(1, targetFps);
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        try {
            if (now - lastFrameMs < interval) return;
            lastFrameMs = now;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer().duplicate();
            int width = image.getWidth(), height = image.getHeight();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            byte[] frame = new byte[width * height * 4];
            if (pixelStride == 4 && rowStride == width * 4) {
                buffer.get(frame, 0, Math.min(frame.length, buffer.remaining()));
            } else if (pixelStride == 4) {
                byte[] row = new byte[rowStride];
                for (int y = 0; y < height; y++) {
                    buffer.position(y * rowStride);
                    buffer.get(row, 0, rowStride);
                    System.arraycopy(row, 0, frame, y * width * 4, width * 4);
                }
            } else {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int src = y * rowStride + x * pixelStride;
                        int dst = (y * width + x) * 4;
                        frame[dst] = buffer.get(src);
                        frame[dst + 1] = buffer.get(src + 1);
                        frame[dst + 2] = buffer.get(src + 2);
                        frame[dst + 3] = (byte) 0xFF;
                    }
                }
            }
            HostService.pushFrame(frame, width, height, width * 4);
        } finally {
            image.close();
        }
    }

    @Override public void onDestroy() {
        running = false;
        systemAudio.stop();
        if (virtualDisplay != null) virtualDisplay.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
