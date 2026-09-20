package com.boogie.vibestation.share

import android.Manifest
import android.os.Build

/** The runtime permissions Nearby Connections needs, which depend on the Android version. */
internal object SharePermissions {

    /**
     * Lists the permissions to request before starting Share Mode.
     *
     * @param sdkInt Android API level of the device.
     * @return Runtime permissions that must be granted, never empty.
     */
    fun runtimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = when {
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> bluetooth() + Manifest.permission.NEARBY_WIFI_DEVICES
        sdkInt >= Build.VERSION_CODES.S_V2 -> bluetooth()
        sdkInt >= Build.VERSION_CODES.S -> bluetooth() + Manifest.permission.ACCESS_FINE_LOCATION
        sdkInt >= Build.VERSION_CODES.Q -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun bluetooth() = listOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )
}
