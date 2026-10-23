package com.vscodroid.storage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Grants only this APK's fixed disposable tree to the debug application. */
public final class SafProviderGrantReceiver extends BroadcastReceiver {
    public static final String TARGET_PACKAGE = "com.vscodroid.debug";
    public static final String GRANT = "com.vscodroid.test.saf.GRANT";
    public static final String REVOKE = "com.vscodroid.test.saf.REVOKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri tree = DocumentsContract.buildTreeDocumentUri(
            SafDocumentsProvider.AUTHORITY, SafDocumentsProvider.ROOT_ID);
        int access = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (GRANT.equals(intent.getAction())) {
            context.grantUriPermission(TARGET_PACKAGE, tree,
                access | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            setResultCode(1);
        } else if (REVOKE.equals(intent.getAction())) {
            context.revokeUriPermission(TARGET_PACKAGE, tree, access);
            setResultCode(1);
        } else {
            setResultCode(0);
        }
    }
}
