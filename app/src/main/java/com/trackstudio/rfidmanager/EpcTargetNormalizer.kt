package com.trackstudio.rfidmanager

data class EpcTarget(
    val label: String,
    val paddedHex: String
)

object EpcTargetNormalizer {
    private val hexPattern = Regex("^[0-9A-F]+$")

    fun normalize(input: String, epcSizeWords: Int? = null): EpcTarget {
        val label = input.trim().uppercase()
        require(label.isNotEmpty()) { "Barcode must not be empty" }
        require(hexPattern.matches(label)) { "Barcode must contain only hexadecimal characters 0-9 and A-F" }

        val targetLength = epcSizeWords?.let { words ->
            val length = words * 4
            require(label.length <= length) { "Barcode is too long for $words-word EPC" }
            length
        }

        return EpcTarget(
            label = label,
            paddedHex = targetLength?.let { label.padEnd(it, '0') } ?: label
        )
    }
}
