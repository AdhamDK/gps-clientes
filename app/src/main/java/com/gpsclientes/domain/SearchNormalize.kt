package com.gpsclientes.domain

import java.text.Normalizer

/**
 * Search normalization: NFD + lower + trim + collapse whitespace + strip '#'.
 * Extends import Normalize with '#' stripping so flagged rows are findable.
 * RIF normalization is separate (uppercase trimmed).
 */
object SearchNormalize {

    fun normalizeForSearch(input: String): String {
        if (input.isBlank()) return ""
        var s = input.trim().lowercase()
        // Strip '#' used for flagged imports — remove all hashes then trim
        s = s.replace("#", "").trim()
        if (s.isEmpty()) return ""
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val withoutAccents = nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return withoutAccents.replace(Regex("\\s+"), " ").trim()
    }

    fun normalizeRif(input: String): String = input.trim().uppercase()

    fun isRifPattern(input: String): Boolean =
        Regex("^[JVEGP]\\d{7,9}$").matches(normalizeRif(input))
}
