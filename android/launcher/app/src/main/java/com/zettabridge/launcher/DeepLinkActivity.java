package com.zettabridge.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Entry point for a browser OAuth callback that names a plugin scheme (declared in this app's
 * manifest). It forwards the intent to the active plugin activity that declares the scheme, so a
 * "sign in with Discord" redirect lands back in ZettaBridge instead of a separately installed copy
 * of the client. Runs in :guest, where that plugin lives.
 */
public final class DeepLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!GuestRuntime.get().handleDeepLink(this)) finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!GuestRuntime.get().handleDeepLink(this)) finish();
    }
}
