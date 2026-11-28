package com.theo.remote

import com.theo.remote.ui.NightMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {
    @Test fun nightHours() {
        assertTrue(NightMode.isNight(21))
        assertTrue(NightMode.isNight(23))
        assertTrue(NightMode.isNight(0))
        assertTrue(NightMode.isNight(6))
    }

    @Test fun dayHours() {
        assertFalse(NightMode.isNight(7))
        assertFalse(NightMode.isNight(12))
        assertFalse(NightMode.isNight(20))
    }
}
