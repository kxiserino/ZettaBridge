package com.zettabridge.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

/**
 * The guest reads the host LocationManager, so without location permission the client reports
 * "GPS signal not found" and nothing spawns. The wrapper opens the game directly instead of the
 * library screen, and the library screen was the only thing that ever asked - so the wrapper never
 * asked at all, and the permission had to be granted by hand.
 */
final class Permissions {
    static final int REQUEST_LOCATION = 1;

    private Permissions() {}

    static boolean locationGranted(Activity activity) {
        return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Ask if needed. `whenDone` runs either way, so a refusal cannot strand the caller. */
    static void ensureLocation(Activity activity, Runnable whenDone) {
        if (locationGranted(activity)) {
            whenDone.run();
            return;
        }
        pending = whenDone;
        activity.requestPermissions(
                new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                              Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION);
    }

    /** Call from the activity's onRequestPermissionsResult. */
    static void onRequestResult(int requestCode) {
        if (requestCode != REQUEST_LOCATION) return;
        final Runnable done = pending;
        pending = null;
        if (done != null) done.run();
    }

    // One activity asks at a time, and the answer can arrive after a configuration change.
    private static Runnable pending;
}
