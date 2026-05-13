package com.trackstudio.rfidmanager

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarPowerWindowControllerTest {

    @Test
    fun startsWithHighestThreePowerLevels() {
        val controller = RadarPowerWindowController()

        assertEquals(listOf(30, 24, 19), controller.currentWindow())
    }

    @Test
    fun movesToLowerPowerWindowWhenTargetIsVisibleAtAllThreeLevels() {
        val controller = RadarPowerWindowController()

        controller.recordDetection(30, found = true)
        controller.recordDetection(24, found = true)
        controller.recordDetection(19, found = true)
        val decision = controller.evaluateWindow()

        assertEquals(RadarPowerDecision.MOVED_LOWER, decision)
        assertEquals(listOf(24, 19, 15), controller.currentWindow())
    }

    @Test
    fun keepsWindowWhenTargetIsHiddenAtLowPowerAndVisibleAtMidAndHighPower() {
        val controller = RadarPowerWindowController(startIndex = 2)

        controller.recordDetection(19, found = true)
        controller.recordDetection(15, found = true)
        controller.recordDetection(12, found = false)
        val decision = controller.evaluateWindow()

        assertEquals(RadarPowerDecision.KEPT, decision)
        assertEquals(listOf(19, 15, 12), controller.currentWindow())
    }

    @Test
    fun startsRecoveryWhenTargetIsNotVisibleAtMidPower() {
        val controller = RadarPowerWindowController(startIndex = 2)

        controller.recordDetection(19, found = true)
        controller.recordDetection(15, found = false)
        controller.recordDetection(12, found = false)
        val decision = controller.evaluateWindow()

        assertEquals(RadarPowerDecision.RECOVERING_HIGHER, decision)
        assertEquals(24, controller.currentPower())
    }

    @Test
    fun cyclesThroughTheCurrentWindowOnePowerAtATime() {
        val controller = RadarPowerWindowController(startIndex = 1)

        assertEquals(24, controller.currentPower())
        assertEquals(19, controller.advancePhase())
        assertEquals(15, controller.advancePhase())
        assertEquals(24, controller.advancePhase())
    }

    @Test
    fun displayPowerStaysAtCurrentWindowMaximumWhilePhaseAdvances() {
        val controller = RadarPowerWindowController(startIndex = 1)

        assertEquals(24, controller.displayPower())
        controller.advancePhase()
        controller.advancePhase()

        assertEquals(24, controller.displayPower())
    }

    @Test
    fun lostTargetStartsRecoveryAtNextHigherPowerLevel() {
        val controller = RadarPowerWindowController(startIndex = 5)

        controller.recordDetection(10, found = false)
        controller.recordDetection(8, found = false)
        controller.recordDetection(7, found = false)
        val decision = controller.evaluateWindow()

        assertEquals(RadarPowerDecision.RECOVERING_HIGHER, decision)
        assertEquals(12, controller.currentPower())
        assertEquals(12, controller.displayPower())
    }

    @Test
    fun recoveryWalksUpwardUntilTargetIsFound() {
        val controller = RadarPowerWindowController(startIndex = 5)

        controller.recordDetection(10, found = false)
        controller.recordDetection(8, found = false)
        controller.recordDetection(7, found = false)
        controller.evaluateWindow()

        assertEquals(12, controller.currentPower())
        assertEquals(15, controller.advancePhase())
        assertEquals(19, controller.advancePhase())
    }

    @Test
    fun recoveryDoesNotRestartAtWindowBoundary() {
        val controller = RadarPowerWindowController(startIndex = 5)

        controller.recordDetection(10, found = false)
        controller.recordDetection(8, found = false)
        controller.recordDetection(7, found = false)
        controller.evaluateWindow()
        controller.advancePhase()
        controller.advancePhase()

        assertEquals(24, controller.advancePhase())
    }

    @Test
    fun evaluatingWindowDuringRecoveryDoesNotRestartAtLowerPower() {
        val controller = RadarPowerWindowController(startIndex = 5)

        controller.recordDetection(10, found = false)
        controller.recordDetection(8, found = false)
        controller.recordDetection(7, found = false)
        controller.evaluateWindow()
        controller.advancePhase()
        controller.advancePhase()

        assertEquals(RadarPowerDecision.RECOVERING_HIGHER, controller.evaluateWindow())
        assertEquals(19, controller.currentPower())
        assertEquals(19, controller.displayPower())
    }

    @Test
    fun recoveryFoundPowerBecomesWindowCenter() {
        val controller = RadarPowerWindowController(startIndex = 5)

        controller.recordDetection(10, found = false)
        controller.recordDetection(8, found = false)
        controller.recordDetection(7, found = false)
        controller.evaluateWindow()
        controller.recordDetection(12, found = true)

        assertEquals(listOf(15, 12, 10), controller.currentWindow())
        assertEquals(12, controller.currentPower())
        assertEquals(15, controller.displayPower())
    }
}
