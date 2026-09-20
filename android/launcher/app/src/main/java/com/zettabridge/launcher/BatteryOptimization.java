package com.zettabridge.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * Android's app freezer stops every thread of an app that is not exempt from battery optimisation.
 * For a translated guest that is indistinguishable from a crash: no frames, no error, no recovery.
 * The launcher is only a wrapper around a plugin, so it asks once, before the user launches
 * anything, and never blocks the launch if they decline.
 */
final class BatteryOptimization {
    private static final String PREFS = "zb";
    private static final String ASKED = "battery-optimization-asked";

    private BatteryOptimization() {}

    /** True when the OS will not freeze this app. */
    static boolean exempt(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    /** Ask once, if the app is not exempt yet. */
    static void ensureExempt(Activity activity) {
        if (exempt(activity)) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(ASKED, false)) return;
        prefs.edit().putBoolean(ASKED, true).apply();
        new AlertDialog.Builder(activity)
                .setTitle("Keep games from freezing")
                .setMessage("Android freezes apps that are not exempt from battery optimisation. "
                        + "While frozen the game stops responding and cannot recover, so allow "
                        + "ZettaBridge to run in the background.")
                .setPositiveButton("Allow", (dialog, which) -> request(activity))
                .setNegativeButton("Not now", null)
                .show();
    }

    private static void request(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        } catch (Exception e) {
            Diagnostics.report(activity, "could not ask for the battery-optimisation exemption", e, false);
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            Diagnostics.report(activity, "could not open battery-optimisation settings", e, false);
        }
    }
}
