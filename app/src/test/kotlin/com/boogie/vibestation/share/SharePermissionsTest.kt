package com.boogie.vibestation.share

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

class SharePermissionsTest {
    private val bluetooth = setOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private fun at(sdk: Int) = SharePermissions.runtimePermissions(sdk).toSet()

    @Test
    fun android13AndUpAddsNearbyWifiDevices() {
        assertEquals(bluetooth + Manifest.permission.NEARBY_WIFI_DEVICES, at(33))
        assertEquals(bluetooth + Manifest.permission.NEARBY_WIFI_DEVICES, at(36))
    }

    @Test
    fun android12LThroughBluetoothOnly() {
        assertEquals(bluetooth, at(32))
    }

    @Test
    fun android12StillNeedsFineLocation() {
        assertEquals(bluetooth + Manifest.permission.ACCESS_FINE_LOCATION, at(31))
    }

    @Test
    fun android10And11NeedFineLocation() {
        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION), at(29))
        assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION), at(30))
    }

    @Test
    fun olderVersionsNeedCoarseLocation() {
        assertEquals(setOf(Manifest.permission.ACCESS_COARSE_LOCATION), at(28))
        assertEquals(setOf(Manifest.permission.ACCESS_COARSE_LOCATION), at(24))
    }
}
