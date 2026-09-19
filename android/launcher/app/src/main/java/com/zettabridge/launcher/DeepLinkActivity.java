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
        GuestRuntime.get().handleDeepLink(this);
        // A Theme.NoDisplay activity must finish before onResume completes or the framework throws
        // IllegalStateException and kills the process, taking the auth callback with it. The
        // forwarded startActivity is asynchronous, so finishing here does not cancel it.
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        GuestRuntime.get().handleDeepLink(this);
        finish();
    }
}
