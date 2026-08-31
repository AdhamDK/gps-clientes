package com.gpsclientes.domain

import com.gpsclientes.data.local.ClienteEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Pure domain helper for duplicate detection.
 * - NFD normalization delegated to [SearchNormalize]
 * - Levenshtein distance with thresholds <=2 (<=15 chars) else <=3 or ratio <=0.15
 * - RIF exact match (uppercase) and isFlaggedImport deprioritization.
 */
object SearchDuplicateHelper {

    data class DuplicateResult(
        val exactMatches: List<ClienteEntity>,
        val fuzzyMatches: List<ClienteEntity>,
        val rifMatch: ClienteEntity?
    ) {
        val hasCandidates: Boolean
            get() = exactMatches.isNotEmpty() || fuzzyMatches.isNotEmpty() || rifMatch != null
        val isEmpty: Boolean get() = !hasCandidates
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Use two rows for memory efficiency
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    fun isFuzzyMatch(normalizedQuery: String, normalizedTarget: String): Boolean {
        if (normalizedQuery.isBlank() || normalizedTarget.isBlank()) return false
        if (normalizedQuery == normalizedTarget) return false
        val dist = levenshtein(normalizedQuery, normalizedTarget)
        val maxLen = max(normalizedQuery.length, normalizedTarget.length)
        val threshold = if (maxLen <= 15) 2 else 3
        if (dist <= threshold) return true
        val ratio = dist.toDouble() / maxLen.toDouble()
        return ratio <= 0.15
    }

    fun isRifExactMatch(rifQuery: String?, entity: ClienteEntity): Boolean {
        if (rifQuery.isNullOrBlank()) return false
        val q = SearchNormalize.normalizeRif(rifQuery)
        val e = entity.rif?.trim()?.uppercase() ?: return false
        return q == e
    }

    /**
     * Classify candidates into exact / fuzzy / rif buckets.
     * Flagged imports are sorted last within each bucket.
     */
    fun classify(
        normalizedQuery: String,
        candidates: List<ClienteEntity>,
        rifQuery: String? = null
    ): DuplicateResult {
        if (normalizedQuery.isBlank() && rifQuery.isNullOrBlank()) {
            return DuplicateResult(emptyList(), emptyList(), null)
        }
        val exact = mutableListOf<ClienteEntity>()
        val fuzzy = mutableListOf<ClienteEntity>()
        var rifMatch: ClienteEntity? = null
        val rifUpper = rifQuery?.takeIf { it.isNotBlank() }?.let { SearchNormalize.normalizeRif(it) }

        for (c in candidates) {
            if (rifUpper != null && c.rif?.trim()?.uppercase() == rifUpper) {
                if (rifMatch == null) rifMatch = c
            }
            val target = c.nombreNormalizado
            when {
                normalizedQuery.isNotBlank() && target == normalizedQuery -> exact.add(c)
                normalizedQuery.isNotBlank() && isFuzzyMatch(normalizedQuery, target) -> fuzzy.add(c)
                normalizedQuery.isNotBlank() && (
                    target.contains(normalizedQuery) || normalizedQuery.contains(target)
                    ) -> {
                    // LIKE contains both directions — advisory candidate, treat as fuzzy for modal visibility
                    // but only if not already exact/fuzzy; distance may exceed threshold but still relevant
                    if (target != normalizedQuery && !isFuzzyMatch(normalizedQuery, target)) {
                        fuzzy.add(c)
                    }
                }
            }
        }

        val sortedExact = exact.sortedWith(
            compareBy<ClienteEntity> { if (it.isFlaggedImport) 1 else 0 }
                .thenBy { it.nombreNormalizado }
        )
        val sortedFuzzy = fuzzy.sortedWith(
            compareBy<ClienteEntity> { if (it.isFlaggedImport) 1 else 0 }
                .thenBy { levenshtein(normalizedQuery, it.nombreNormalizado) }
                .thenBy { it.nombreNormalizado }
        )
        return DuplicateResult(sortedExact, sortedFuzzy, rifMatch)
    }

    /**
     * Ranking for search results: exact first, then non-flagged, then by distance.
     */
    fun rankForSearch(
        normalizedQuery: String,
        candidates: List<ClienteEntity>
    ): List<ClienteEntity> {
        if (normalizedQuery.isBlank()) return candidates
        return candidates.sortedWith(
            compareBy<ClienteEntity> { if (it.nombreNormalizado == normalizedQuery) 0 else 1 }
                .thenBy { if (it.isFlaggedImport) 1 else 0 }
                .thenBy { levenshtein(normalizedQuery, it.nombreNormalizado) }
                .thenBy { it.nombreNormalizado }
        )
    }
}
