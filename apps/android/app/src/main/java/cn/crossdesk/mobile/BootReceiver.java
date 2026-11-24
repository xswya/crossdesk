package cn.crossdesk.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Keep-alive watchdog for host mode, in the style of unattended remote-access
 * agents (RustDesk and peers): several independent revival paths so no single
 * kill takes the device offline for good.
 *
 *  - BOOT_COMPLETED / MY_PACKAGE_REPLACED restart the service after reboots
 *    and app updates;
 *  - HostService returns START_STICKY and schedules ACTION_RESTART from
 *    onTaskRemoved/onDestroy, so a swipe-away or an unexpected death brings
 *    it back within seconds;
 *  - a repeating ACTION_WATCHDOG alarm (re-armed on every delivery) revives
 *    the service if the process was reaped while idle;
 *  - HostInputService (accessibility) restarts the service from
 *    onServiceConnected — the system keeps accessibility bindings alive far
 *    longer than ordinary processes.
 *
 * Alarms use setAndAllowWhileIdle (no exact-alarm permission needed); the
 * battery-optimization exemption the panel asks for is what lets the
 * foreground service start from these background triggers on Android 12+.
 * Screen capture still needs one MediaProjection consent after a reboot
 * (platform rule) — everything else comes back unattended.
 */
public final class BootReceiver extends BroadcastReceiver {
    static final String ACTION_WATCHDOG = "cn.crossdesk.mobile.HOST_WATCHDOG";
    static final String ACTION_RESTART = "cn.crossdesk.mobile.HOST_RESTART";
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60_000;
    private static final long RESTART_DELAY_MS = 4_000;
    private static final int REQUEST_WATCHDOG = 4202;
    private static final int REQUEST_RESTART = 4203;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_WATCHDOG.equals(action)) scheduleWatchdog(context); // keep the chain alive
        if (!hostModeEnabled(context)) return;
        if (HostService.isRunning()) return;
        Intent service = new Intent(context, HostService.class).setAction(HostService.ACTION_START);
        try {
            context.startForegroundService(service);
        } catch (Exception ignored) {
            // Background-start restrictions: the next watchdog tick or the
            // accessibility anchor will retry from a permitted context.
        }
    }

    static boolean hostModeEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return prefs.getBoolean("hostMode", false);
    }

    static void scheduleWatchdog(Context context) {
        schedule(context, ACTION_WATCHDOG, REQUEST_WATCHDOG, WATCHDOG_INTERVAL_MS);
    }

    static void scheduleRestart(Context context) {
        schedule(context, ACTION_RESTART, REQUEST_RESTART, RESTART_DELAY_MS);
    }

    private static void schedule(Context context, String action, int requestCode, long delayMs) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Intent intent = new Intent(context, BootReceiver.class).setAction(action);
        PendingIntent pending = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pending);
        } catch (Exception ignored) { }
    }
}
