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

    private val location = setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @Test
    fun android13AndUpAddsNearbyWifiDevicesAndStillNeedsLocation() {
        val expected = bluetooth + location + Manifest.permission.NEARBY_WIFI_DEVICES
        assertEquals(expected, at(33))
        assertEquals(expected, at(36))
    }

    @Test
    fun android12AndUpNeedLocationAskedAsAPair() {
        assertEquals(bluetooth + location, at(31))
        assertEquals(bluetooth + location, at(32))
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
