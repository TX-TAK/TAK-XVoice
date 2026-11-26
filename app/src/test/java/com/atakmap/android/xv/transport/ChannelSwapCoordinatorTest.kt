package com.atakmap.android.xv.transport

import com.atakmap.android.xv.transport.ChannelSwapCoordinator.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSwapCoordinatorTest {
    // VS1 on 5 ("Alpha"), VS2 on 9 ("Bravo"); a swap targets 9 / 5.
    private val plan = ChannelSwap.Plan(primaryFromId = 5, secondaryFromId = 9)

    @Test
    fun `both acks complete without a deadline`() {
        val c = ChannelSwapCoordinator()
        c.begin(plan, "Bravo")
        assertTrue(c.inFlight())
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = null)) // one ack
        val step = c.evaluate(9, 5, deadlineToken = null)
        assertTrue(step is Step.Report)
        assertTrue((step as Step.Report).outcome is ChannelSwap.Outcome.Completed)
        assertFalse(step.restore)
        assertFalse(c.inFlight())
    }

    @Test
    fun `an ack alone never times out the swap`() {
        val c = ChannelSwapCoordinator()
        c.begin(plan, "Bravo")
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = null))
        assertTrue(c.inFlight())
    }

    @Test
    fun `deadline with only VS1 moved rolls VS1 back and stays in flight`() {
        val c = ChannelSwapCoordinator()
        val t = c.begin(plan, "Bravo")
        val step = c.evaluate(9, 9, deadlineToken = t)
        assertTrue(step is Step.RollBack)
        step as Step.RollBack
        assertEquals(0, step.slot)
        assertEquals(5, step.toId)
        assertEquals("Bravo", step.restoreSecondaryName)
        assertNotEquals(t, step.token)
        // Rolling back: still in flight (no success cue, no second swap).
        assertTrue(c.inFlight())
        // The old deadline is stale now.
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = t))
        // Rollback ack lands → report RolledBack(failedSlot = 1).
        val done = c.evaluate(5, 9, deadlineToken = null)
        assertTrue(done is Step.Report)
        assertEquals(ChannelSwap.Outcome.RolledBack(failedSlot = 1), (done as Step.Report).outcome)
        assertTrue(done.restore)
        assertFalse(c.inFlight())
    }

    @Test
    fun `deadline with only VS2 moved rolls VS2 back`() {
        val c = ChannelSwapCoordinator()
        val t = c.begin(plan, "Bravo")
        val step = c.evaluate(5, 5, deadlineToken = t) as Step.RollBack
        assertEquals(1, step.slot)
        assertEquals(9, step.toId)
        val done = c.evaluate(5, 9, deadlineToken = null) as Step.Report
        assertEquals(ChannelSwap.Outcome.RolledBack(failedSlot = 0), done.outcome)
    }

    @Test
    fun `rollback that never acks finishes at its own deadline`() {
        val c = ChannelSwapCoordinator()
        val t = c.begin(plan, "Bravo")
        val rb = c.evaluate(9, 9, deadlineToken = t) as Step.RollBack
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = null))
        val done = c.evaluate(9, 9, deadlineToken = rb.token)
        assertTrue(done is Step.Report)
        assertFalse(c.inFlight())
    }

    @Test
    fun `nothing moved at the deadline fails and restores the name`() {
        val c = ChannelSwapCoordinator()
        val t = c.begin(plan, "Bravo")
        val done = c.evaluate(5, 9, deadlineToken = t) as Step.Report
        assertEquals(ChannelSwap.Outcome.Failed, done.outcome)
        assertTrue(done.restore)
        assertEquals("Bravo", done.restoreSecondaryName)
    }

    @Test
    fun `cancel abandons the swap once and invalidates its deadline`() {
        val c = ChannelSwapCoordinator()
        val t = c.begin(plan, "Bravo")
        assertEquals(ChannelSwapCoordinator.Cancelled("Bravo"), c.cancel())
        assertFalse(c.inFlight())
        assertNull(c.cancel())
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = t))
    }

    @Test
    fun `cancel during rollback ends it`() {
        val c = ChannelSwapCoordinator()
        val t = c.begin(plan, "Bravo")
        c.evaluate(9, 9, deadlineToken = t)
        assertEquals("Bravo", c.cancel()?.previousSecondaryName)
        assertFalse(c.inFlight())
    }

    @Test
    fun `idle coordinator ignores evaluations`() {
        val c = ChannelSwapCoordinator()
        assertEquals(Step.None, c.evaluate(9, 5, deadlineToken = null))
        assertNull(c.cancel())
    }

    @Test
    fun `a new swap gets a fresh token so an old deadline can't resolve it`() {
        val c = ChannelSwapCoordinator()
        val first = c.begin(plan, "Bravo")
        c.cancel()
        val second = c.begin(plan, "Bravo")
        assertNotEquals(first, second)
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = first))
        assertTrue(c.inFlight())
    }

    @Test
    fun `half-swap disconnect hands back VS2's real name`() {
        // VS1 acked (now on 9), VS2 hasn't (still on 9) when the link drops.
        val c = ChannelSwapCoordinator()
        c.begin(plan, "Bravo")
        assertEquals(Step.None, c.evaluate(9, 9, deadlineToken = null))
        assertEquals(ChannelSwapCoordinator.Cancelled("Bravo"), c.cancel())
        assertFalse(c.inFlight())
    }
}
