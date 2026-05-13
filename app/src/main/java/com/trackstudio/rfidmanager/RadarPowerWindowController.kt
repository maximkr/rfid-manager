package com.trackstudio.rfidmanager

enum class RadarPowerDecision {
    KEPT,
    MOVED_HIGHER,
    MOVED_LOWER,
    RECOVERING_HIGHER
}

class RadarPowerWindowController(
    private val powerLevels: List<Int> = listOf(30, 24, 19, 15, 12, 10, 8, 7, 6, 5),
    startIndex: Int = 0
) {
    private var windowIndex = startIndex.coerceIn(0, powerLevels.size - WINDOW_SIZE)
    private var phaseIndex = 0
    private var recoveryIndex: Int? = null
    private val detections = mutableMapOf<Int, Boolean>()

    fun currentWindow(): List<Int> = powerLevels.subList(windowIndex, windowIndex + WINDOW_SIZE)

    fun isRecovering(): Boolean = recoveryIndex != null

    fun displayPower(): Int = recoveryIndex?.let { powerLevels[it] } ?: currentWindow().first()

    fun currentPower(): Int = recoveryIndex?.let { powerLevels[it] } ?: currentWindow()[phaseIndex]

    fun advancePhase(): Int {
        recoveryIndex?.let { currentRecoveryIndex ->
            recoveryIndex = (currentRecoveryIndex - 1).coerceAtLeast(0)
            return currentPower()
        }

        phaseIndex = (phaseIndex + 1) % WINDOW_SIZE
        return currentPower()
    }

    fun recordDetection(power: Int, found: Boolean) {
        if (found && recoveryIndex != null) {
            centerWindowOn(power)
            return
        }

        detections[power] = detections[power] == true || found
    }

    fun evaluateWindow(): RadarPowerDecision {
        if (recoveryIndex != null) {
            detections.clear()
            return RadarPowerDecision.RECOVERING_HIGHER
        }

        val window = currentWindow()
        val highPower = window[0]
        val midPower = window[1]
        val lowPower = window[2]

        val seenHigh = detections[highPower] == true
        val seenMid = detections[midPower] == true
        val seenLow = detections[lowPower] == true

        val decision = when {
            seenHigh && seenMid && seenLow -> moveLower()
            !seenHigh || !seenMid -> startRecovery()
            else -> RadarPowerDecision.KEPT
        }

        detections.clear()
        phaseIndex = 0
        return decision
    }

    private fun moveLower(): RadarPowerDecision {
        val nextIndex = (windowIndex + 1).coerceAtMost(powerLevels.size - WINDOW_SIZE)
        return if (nextIndex == windowIndex) {
            RadarPowerDecision.KEPT
        } else {
            windowIndex = nextIndex
            RadarPowerDecision.MOVED_LOWER
        }
    }

    private fun moveHigher(): RadarPowerDecision {
        val nextIndex = (windowIndex - 1).coerceAtLeast(0)
        return if (nextIndex == windowIndex) {
            RadarPowerDecision.KEPT
        } else {
            windowIndex = nextIndex
            RadarPowerDecision.MOVED_HIGHER
        }
    }

    private fun startRecovery(): RadarPowerDecision {
        recoveryIndex = (windowIndex - 1).coerceAtLeast(0)
        return RadarPowerDecision.RECOVERING_HIGHER
    }

    private fun centerWindowOn(power: Int) {
        val powerIndex = powerLevels.indexOf(power)
        if (powerIndex < 0) return

        windowIndex = (powerIndex - 1).coerceIn(0, powerLevels.size - WINDOW_SIZE)
        phaseIndex = (powerIndex - windowIndex).coerceIn(0, WINDOW_SIZE - 1)
        recoveryIndex = null
        detections.clear()
    }

    companion object {
        private const val WINDOW_SIZE = 3
    }
}
