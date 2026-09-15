package com.kiki.spotifymixer.domain

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object SearchUtils {

    /**
     * Normalizes text by decomposing diacritics (accents, tildes, umlauts, cedillas),
     * stripping combining marks, replacing common ligatures, and converting to lowercase.
     * Examples:
     *   "León Gieco" -> "leon gieco"
     *   "Gustavo Cerati" -> "gustavo cerati"
     *   "Charly García" -> "charly garcia"
     *   "Mötley Crüe" -> "motley crue"
     *   "Beyoncé" -> "beyonce"
     *   "Caetano Veloso" -> "caetano veloso"
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val preprocessed = text
            .replace("æ", "ae")
            .replace("Æ", "ae")
            .replace("œ", "oe")
            .replace("Œ", "oe")
            .replace("ø", "o")
            .replace("Ø", "o")
            .replace("ß", "ss")
            .replace("’", "'")
            .replace("`", "'")

        val nfd = Normalizer.normalize(preprocessed, Normalizer.Form.NFD)
        return nfd.replace("\\p{Mn}+".toRegex(), "").lowercase().trim()
    }

    /**
     * Computes the Levenshtein edit distance between two strings.
     * Fast O(min(m, n)) space implementation.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        // Ensure s2 is the shorter string to optimize space
        val (shorter, longer) = if (s1.length <= s2.length) s1 to s2 else s2 to s1
        val n = shorter.length
        val m = longer.length

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            val c2 = longer[i - 1]
            for (j in 1..n) {
                val c1 = shorter[j - 1]
                val cost = if (c1 == c2) 0 else 1
                curr[j] = min(
                    curr[j - 1] + 1,        // insertion
                    min(
                        prev[j] + 1,        // deletion
                        prev[j - 1] + cost  // substitution
                    )
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }

    /**
     * Checks if a single target token matches a query token allowing up to maxDistance edits.
     * For queries <= 2 characters, strictly requires exact prefix/substring to avoid false positives.
     */
    fun matchesToken(queryToken: String, targetToken: String, maxDistance: Int = 1): Boolean {
        if (queryToken.isEmpty()) return true
        if (targetToken.isEmpty()) return false

        // Exact prefix or contains
        if (targetToken.startsWith(queryToken) || targetToken.contains(queryToken)) {
            return true
        }

        // For very short query tokens (<= 2 chars), don't allow fuzzy edits
        if (queryToken.length <= 2) {
            return false
        }

        // 1. Full token edit distance (e.g. query "leom" vs target "leon")
        if (abs(queryToken.length - targetToken.length) <= maxDistance) {
            if (levenshteinDistance(queryToken, targetToken) <= maxDistance) {
                return true
            }
        }

        // 2. Prefix edit distance (e.g. query "giek" vs target "gieco" -> prefix "giec")
        if (targetToken.length >= queryToken.length) {
            val minLen = max(1, queryToken.length - maxDistance)
            val maxLen = min(targetToken.length, queryToken.length + maxDistance)
            for (len in minLen..maxLen) {
                if (levenshteinDistance(queryToken, targetToken.take(len)) <= maxDistance) {
                    return true
                }
            }
        }

        // 3. Substring edit distance in target token (requires token length >= 4 to avoid rampant false matches on short 3-letter words like 'and', 'the')
        if (queryToken.length >= 4 && targetToken.length > queryToken.length) {
            val windowLen = queryToken.length
            for (start in 0..(targetToken.length - windowLen)) {
                val sub = targetToken.substring(start, start + windowLen)
                if (levenshteinDistance(queryToken, sub) <= maxDistance) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Determines whether `target` matches `query` under accent normalization and
     * typo tolerance (up to maxDistance character edits).
     *
     * Supports:
     *  - Exact substring / prefix matches (normalized)
     *  - Multi-token queries where each token matches at least one target token
     *  - 1-character typo tolerance for tokens with length >= 3
     */
    fun fuzzyMatches(query: String, target: String, maxDistance: Int = 1): Boolean {
        val qNorm = normalize(query)
        val tNorm = normalize(target)
        if (qNorm.isEmpty()) return true
        if (tNorm.isEmpty()) return false

        // 1. Direct normalized substring match (handles 90% of cases instantly)
        if (tNorm.contains(qNorm)) return true

        // 2. Direct full-string edit distance
        if (qNorm.length > 2 && abs(qNorm.length - tNorm.length) <= maxDistance) {
            if (levenshteinDistance(qNorm, tNorm) <= maxDistance) return true
        }

        // 3. Token-based matching
        val qTokens = qNorm.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (qTokens.isEmpty()) return true

        val tTokens = tNorm.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tTokens.isEmpty()) return false

        // All query tokens must match at least one target token
        return qTokens.all { qTok ->
            tTokens.any { tTok -> matchesToken(qTok, tTok, maxDistance) }
        }
    }

    /**
     * Calculates a relevance score for ordering suggestions (lower score = more relevant).
     * 0: Exact match
     * 1: Target starts with query
     * 2: Target contains query phrase
     * 3: Target token starts with query
     * 4: Target token contains query
     * 10 + editDistance: Fuzzy match
     * 999: No match
     */
    fun matchScore(query: String, target: String): Int {
        val qNorm = normalize(query)
        val tNorm = normalize(target)
        if (qNorm.isEmpty()) return 0
        if (tNorm == qNorm) return 0
        if (tNorm.startsWith(qNorm)) return 1
        if (tNorm.contains(qNorm)) return 2

        val tTokens = tNorm.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tTokens.any { it.startsWith(qNorm) }) return 3
        if (tTokens.any { it.contains(qNorm) }) return 4

        val qTokens = qNorm.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        var minDistance = 999
        for (qTok in qTokens) {
            for (tTok in tTokens) {
                val len = min(qTok.length, tTok.length)
                if (len > 2) {
                    val dist = levenshteinDistance(qTok, tTok.take(len))
                    if (dist < minDistance) minDistance = dist
                }
            }
        }
        if (minDistance <= 1) {
            return 10 + minDistance
        }

        return 999
    }

    /**
     * Splits multi-artist collaboration strings (e.g. "Mercedes Sosa, León Gieco"
     * or "Charly García feat. Pedro Aznar") into individual artist names,
     * while preserving the original string as well.
     */
    fun splitArtists(rawArtist: String): List<String> {
        val trimmed = rawArtist.trim()
        if (trimmed.isEmpty()) return emptyList()

        val delimiterRegex = Regex("[,;/]|\\s+(?:feat\\.?|ft\\.?|featuring|&|and|with)\\s+", RegexOption.IGNORE_CASE)
        val parts = trimmed.split(delimiterRegex).map { it.trim() }.filter { it.isNotBlank() }

        return (listOf(trimmed) + parts).distinct()
    }
}
