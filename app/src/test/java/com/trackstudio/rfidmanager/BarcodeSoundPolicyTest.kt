package com.trackstudio.rfidmanager

import org.junit.Assert.assertEquals
import org.junit.Test

class BarcodeSoundPolicyTest {

    @Test
    fun disablesVendorBarcodeSoundsSoWriteOutcomeOwnsFeedback() {
        val events = mutableListOf<String>()

        BarcodeSoundPolicy.apply(
            object : BarcodeSoundSettings {
                override fun setSuccessSound(enabled: Boolean) {
                    events.add("success:$enabled")
                }

                override fun setFailureSound(enabled: Boolean) {
                    events.add("failure:$enabled")
                }
            }
        )

        assertEquals(listOf("success:false", "failure:false"), events)
    }
}
