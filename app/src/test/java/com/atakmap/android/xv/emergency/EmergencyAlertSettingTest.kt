package com.atakmap.android.xv.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmergencyAlertSettingTest {
    @Test
    fun `nothing saved sends the 911 alert`() {
        assertEquals("NineOneOne", EmergencyAlertSetting.alertToFire(null))
        assertEquals("911 Alert", EmergencyAlertSetting.label(null))
    }

    @Test
    fun `an unknown saved value falls back to 911, never to off`() {
        assertEquals("NineOneOne", EmergencyAlertSetting.alertToFire("Bogus"))
        assertEquals("NineOneOne", EmergencyAlertSetting.alertToFire(""))
    }

    @Test
    fun `a chosen alert is sent`() {
        assertEquals("TroopsInContact", EmergencyAlertSetting.alertToFire("TroopsInContact"))
        assertEquals("Ring The Bell", EmergencyAlertSetting.label("RingTheBell"))
    }

    @Test
    fun `off sends nothing`() {
        assertNull(EmergencyAlertSetting.alertToFire(EmergencyAlertSetting.OFF))
        assertEquals("Off", EmergencyAlertSetting.label(EmergencyAlertSetting.OFF))
    }
}
