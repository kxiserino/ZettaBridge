package com.zettabridge.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The game this build ships with, if any.
 *
 * A wrapper build puts one plugin APK in `assets/bundled/plugin.apk`; the launcher imports it
 * itself on first run and then opens it, so the user installs one thing and never sees a library
 * screen. A build without that asset behaves as the generic launcher.
 */
final class BundledPlugin {
    static final String ASSET = "bundled/plugin.apk";
    private static final String PREFS = "zb";
    private static final String PACKAGE = "bundled-package";
    private static final String LABEL = "bundled-label";

    private BundledPlugin() {}

    /** True when this build carries a game. */
    static boolean present(Context context) {
        try (InputStream in = context.getAssets().open(ASSET)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The game's label for anything user-visible, falling back to this app's own name. */
    static String displayName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String label = prefs.getString(LABEL, null);
        return label != null ? label : context.getString(R.string.app_name);
    }

    /** The bundled game's imported record, or null when it has not been imported yet. */
    static PluginRecord imported(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String packageName = prefs.getString(PACKAGE, null);
        return packageName == null ? null : PluginStore.find(context, packageName);
    }

    /** Copy the bundled APK out of assets and import it, remembering what it turned out to be. */
    static PluginRecord install(Context context) throws IOException {
        File apk = new File(context.getCacheDir(), "bundled-plugin.apk");
        try (InputStream in = context.getAssets().open(ASSET);
             OutputStream out = new FileOutputStream(apk)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
        PluginRecord record = PluginStore.importApk(context, Uri.fromFile(apk));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(PACKAGE, record.packageName)
                .putString(LABEL, record.label)
                .apply();
        // The copy has served its purpose; the store keeps its own.
        apk.delete();
        return record;
    }
}
