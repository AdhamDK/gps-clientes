package com.gpsclientes.data.importer

import java.text.Normalizer

/**
 * Normalization: lowercase + trim + stripAccents(NFD) + collapse whitespace.
 * Handles UTF-8 correctly for Vigía etc.
 */
object Normalize {

    fun normalize(input: String): String {
        val trimmed = input.trim().lowercase()
        // NFD decompose then strip combining marks
        val nfd = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val withoutAccents = nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        // collapse whitespace
        return withoutAccents.replace(Regex("\\s+"), " ").trim()
    }
}
