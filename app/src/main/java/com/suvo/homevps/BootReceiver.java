package com.suvo.homevps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.suvo.homevps.util.Prefs;

/**
 * Restarts the main API server after the phone reboots, so the VPS comes
 * back up on its own. Only fires if the user had the server running and
 * left autostart enabled.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        boolean bootAction =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!bootAction) return;

        Prefs prefs = new Prefs(context);
        if (prefs.isAutostart() && prefs.isDesiredRunning()) {
            Log.i(TAG, "Boot completed, restarting VPS server");
            VpsService.startServer(context);
        } else {
            Log.i(TAG, "Boot completed, autostart disabled or server was off");
        }
    }
}
