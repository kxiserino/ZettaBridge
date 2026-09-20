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
 * So the wrapper asks once, before the game starts, and names the game rather than itself: the
 * user only ever sees the game's name.
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

    /**
     * Run `whenDone` once the exemption has been settled: immediately when it already is or when
     * the question has been asked before, otherwise after the user answers. The game is never
     * started while the dialog is up, so the answer applies to it.
     */
    static void ensureExempt(Activity activity, Runnable whenDone) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (exempt(activity) || prefs.getBoolean(ASKED, false)) {
            whenDone.run();
            return;
        }
        prefs.edit().putBoolean(ASKED, true).apply();
        final String name = BundledPlugin.displayName(activity);
        new AlertDialog.Builder(activity)
                .setTitle("Keep " + name + " from freezing")
                .setMessage("Android freezes apps that are not exempt from battery optimisation. "
                        + "While frozen " + name + " stops responding and cannot recover, so allow "
                        + "it to run in the background.")
                .setCancelable(false)
                .setPositiveButton("Allow", (dialog, which) -> {
                    request(activity);
                    whenDone.run();
                })
                .setNegativeButton("Not now", (dialog, which) -> whenDone.run())
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
