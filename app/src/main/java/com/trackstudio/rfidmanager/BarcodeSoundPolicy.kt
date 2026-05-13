package com.trackstudio.rfidmanager

interface BarcodeSoundSettings {
    fun setSuccessSound(enabled: Boolean)
    fun setFailureSound(enabled: Boolean)
}

object BarcodeSoundPolicy {
    fun apply(settings: BarcodeSoundSettings) {
        settings.setSuccessSound(false)
        settings.setFailureSound(false)
    }
}
