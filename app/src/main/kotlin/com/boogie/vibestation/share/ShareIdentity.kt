package com.boogie.vibestation.share

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat

/** Works out how this phone introduces itself to others. */
internal object ShareIdentity {

    /**
     * Builds this phone's hello: the exact version code, because both phones must run the same build,
     * a fresh nonce, and the name the owner gave the phone in Settings (or its model when there is none).
     *
     * @param context Any context.
     * @return A new hello for this Share Mode session.
     */
    fun hello(context: Context): ShareHello {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val custom = Settings.Global.getString(context.contentResolver, "device_name")
        val name = if (custom.isNullOrBlank()) Build.MODEL else custom
        return ShareHello.create(PackageInfoCompat.getLongVersionCode(info).toInt(), name)
    }
}
