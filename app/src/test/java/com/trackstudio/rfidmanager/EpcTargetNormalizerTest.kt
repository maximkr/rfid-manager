package com.trackstudio.rfidmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EpcTargetNormalizerTest {

    @Test
    fun lowercaseAndSpaceInputNormalizesToUppercase() {
        val result = EpcTargetNormalizer.normalize(" e012ab ")

        assertEquals("E012AB", result.label)
        assertEquals("E012AB", result.paddedHex)
    }

    @Test
    fun invalidNonHexInputIsRejected() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            EpcTargetNormalizer.normalize("fdfdsf")
        }

        assertEquals("Barcode must contain only hexadecimal characters 0-9 and A-F", exception.message)
    }

    @Test
    fun tooLongInputIsRejectedForSixWordEpcSize() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            EpcTargetNormalizer.normalize("1234567890ABCDEF123456789", 6)
        }

        assertEquals("Barcode is too long for 6-word EPC", exception.message)
    }

    @Test
    fun tooLongInputIsRejectedForEightWordEpcSize() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            EpcTargetNormalizer.normalize("1234567890ABCDEF1234567890ABCDEF0", 8)
        }

        assertEquals("Barcode is too long for 8-word EPC", exception.message)
    }

    @Test
    fun sixWordEpcPadsRightWithZeros() {
        val result = EpcTargetNormalizer.normalize("e01234", 6)

        assertEquals("E01234", result.label)
        assertEquals("E01234000000000000000000", result.paddedHex)
    }

    @Test
    fun eightWordEpcPadsRightWithZeros() {
        val result = EpcTargetNormalizer.normalize("e01234", 8)

        assertEquals("E01234", result.label)
        assertEquals("E0123400000000000000000000000000", result.paddedHex)
    }

    @Test
    fun locateLabelRemainsNormalizedCodeNotPadded() {
        val result = EpcTargetNormalizer.normalize("e01234", 8)

        assertEquals("E01234", result.label)
    }
}
