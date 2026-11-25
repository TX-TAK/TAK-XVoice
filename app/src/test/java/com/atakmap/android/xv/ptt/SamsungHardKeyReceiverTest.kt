package com.atakmap.android.xv.ptt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.atakmap.android.xv.util.SamsungActiveKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Coverage for [SamsungHardKeyReceiver] — the manifest-declared half of
 * Samsung's unmanaged key mapping — and for the manifest entries the
 * mapping depends on. The manifest assertions matter as much as the
 * logic: without the meta-data the app is never selectable in Samsung
 * Settings, and that omission is silent at build time.
 */
@RunWith(RobolectricTestRunner::class)
class SamsungHardKeyReceiverTest {
    private val edges = mutableListOf<Boolean>()
    private val savedIsSupported = SamsungHardKeyReceiver.isSupported
    private val savedDeliver = SamsungHardKeyReceiver.deliver
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        SamsungHardKeyReceiver.isSupported = { true }
        SamsungHardKeyReceiver.deliver = { edges.add(it) }
    }

    @After
    fun teardown() {
        SamsungHardKeyReceiver.isSupported = savedIsSupported
        SamsungHardKeyReceiver.deliver = savedDeliver
    }

    private fun report(
        keyCode: Int? = SamsungActiveKey.KEY_CODE_PTT,
        reportType: Int? = SamsungActiveKey.KEY_REPORT_TYPE_PRESSED,
        action: String = SamsungActiveKey.ACTION_HARD_KEY_REPORT,
    ): Intent =
        Intent(action).apply {
            keyCode?.let { putExtra(SamsungActiveKey.EXTRA_KEY_CODE, it) }
            reportType?.let { putExtra(SamsungActiveKey.EXTRA_KEY_REPORT_TYPE, it) }
        }

    @Test
    fun `press then release delivers down then up`() {
        val rx = SamsungHardKeyReceiver()
        rx.onReceive(context, report(reportType = SamsungActiveKey.KEY_REPORT_TYPE_PRESSED))
        rx.onReceive(context, report(reportType = SamsungActiveKey.KEY_REPORT_TYPE_RELEASED))
        assertEquals(listOf(true, false), edges)
    }

    @Test
    fun `top key, unknown report type, missing extras and foreign action are dropped`() {
        val rx = SamsungHardKeyReceiver()
        rx.onReceive(context, report(keyCode = 1079))
        rx.onReceive(context, report(reportType = 99))
        rx.onReceive(context, report(keyCode = null))
        rx.onReceive(context, report(reportType = null))
        rx.onReceive(context, report(action = "com.example.NOT_THE_ACTION"))
        assertTrue("no edge may be delivered, got $edges", edges.isEmpty())
    }

    @Test
    fun `unsupported hardware drops the edge`() {
        SamsungHardKeyReceiver.isSupported = { false }
        SamsungHardKeyReceiver().onReceive(context, report())
        assertTrue(edges.isEmpty())
    }

    @Test
    fun `a throwing delivery does not escape onReceive`() {
        SamsungHardKeyReceiver.deliver = { error("boom") }
        SamsungHardKeyReceiver().onReceive(context, report())
    }

    @Test
    fun `manifest declares the HARD_KEY_PRESS meta-data as true`() {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertTrue(
            "application meta-data ${SamsungActiveKey.META_HARD_KEY_PRESS} must be true",
            appInfo.metaData?.getBoolean(SamsungActiveKey.META_HARD_KEY_PRESS, false) == true,
        )
    }

    @Test
    fun `manifest receiver is exported and resolves for HARD_KEY_REPORT`() {
        val pm = context.packageManager
        val info =
            pm.getReceiverInfo(
                ComponentName(context, SamsungHardKeyReceiver::class.java),
                0,
            )
        assertTrue("receiver must be exported for the Samsung framework to reach it", info.exported)
        val resolved =
            pm.queryBroadcastReceivers(
                Intent(SamsungActiveKey.ACTION_HARD_KEY_REPORT).setPackage(context.packageName),
                0,
            )
        assertTrue(
            "HARD_KEY_REPORT must resolve to SamsungHardKeyReceiver",
            resolved.any { it.activityInfo.name == SamsungHardKeyReceiver::class.java.name },
        )
    }
}
