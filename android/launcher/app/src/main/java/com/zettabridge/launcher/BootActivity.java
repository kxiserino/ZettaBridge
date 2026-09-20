package com.zettabridge.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The only entry point a wrapper build has: it opens the game.
 *
 * With a bundled game there is no library screen at all - first run imports it (once, out of
 * assets), later runs go straight to it. A build without a bundled game falls back to the library
 * screen so the APK stays usable as the generic launcher.
 *
 * Every step leaves a line in zb-errors.txt before it can fail, so a crash on a device we do not
 * have still says how far it got.
 */
public class BootActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean bundled = BundledPlugin.present(this);
        // The page size decides whether the translator can map guest memory at all, and it is the
        // one thing a device we cannot hold differs by.
        Diagnostics.note(this, "boot: bundled=" + bundled + " pageSize="
                + android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) + " started");
        if (bundled) show(status("Starting " + BundledPlugin.displayName(this)));
        // The exemption is settled before the game starts, so the answer applies to it.
        BatteryOptimization.ensureExempt(this, this::boot);
    }

    private void boot() {
        Diagnostics.note(this, "boot: exemption settled, exempt=" + BatteryOptimization.exempt(this));
        if (!BundledPlugin.present(this)) {
            startActivity(new Intent(this, LibraryActivity.class));
            finish();
            return;
        }
        PluginRecord record = BundledPlugin.imported(this);
        Diagnostics.note(this, "boot: imported=" + (record == null ? "no" : record.packageName));
        if (record != null && record.isLaunchable()) {
            launch(record.packageName);
            return;
        }
        // First run: the bundled APK has to be imported. It is a whole game, so it is copied and
        // parsed off the main thread, with a screen that says what is happening.
        show(status("Installing " + BundledPlugin.displayName(this) + "..."));
        new Thread(this::installAndLaunch, "zb-bundled-import").start();
    }

    private void installAndLaunch() {
        String packageName = null;
        String failure = null;
        try {
            PluginRecord imported = BundledPlugin.install(this);
            if (imported.isLaunchable()) {
                packageName = imported.packageName;
            } else {
                failure = imported.label + ": " + imported.status();
            }
        } catch (Throwable t) {
            // Throwable, not Exception: an Error here (the heap, a bad asset) would otherwise kill
            // the process with nothing written down.
            Diagnostics.report(this, "could not import the bundled game", t, false);
            failure = t.toString();
        }
        final String launch = packageName;
        final String error = failure;
        Diagnostics.note(this, launch != null ? "boot: installed " + launch : "boot: failed " + error);
        runOnUiThread(() -> {
            if (launch != null) {
                launch(launch);
                return;
            }
            // Falling back to the library screen beats a dead end: the user can import by hand.
            show(status("Could not install: " + error));
            startActivity(new Intent(this, LibraryActivity.class));
            finish();
        });
    }

    private void launch(String packageName) {
        Diagnostics.note(this, "boot: launching " + packageName);
        startActivity(PluginSwitchActivity.intent(this, packageName));
        finish();
    }

    private TextView status(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        view.setBackgroundColor(Color.BLACK);
        return view;
    }

    private void show(TextView view) {
        setContentView(view);
    }
}
