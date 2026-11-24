package cn.crossdesk.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Restarts host mode after a reboot when the user left it enabled.
 * The signaling/host side comes back unattended; screen capture still needs
 * one MediaProjection consent (Android platform rule), which the user grants
 * from HostActivity — the persistent notification leads there.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("hostMode", false)) return;
        Intent service = new Intent(context, HostService.class).setAction(HostService.ACTION_START);
        context.startForegroundService(service);
    }
}
