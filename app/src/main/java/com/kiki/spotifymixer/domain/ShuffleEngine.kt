package com.kiki.spotifymixer.domain

import com.kiki.spotifymixer.data.local.entity.TrackEntity
import java.security.SecureRandom
import kotlin.math.min

/**
 * True Mathematical Random Shuffle Engine (Fisher-Yates) with Artist Anti-Clumping.
 *
 * Direct parity with Kiki's Spotify Mixer cryptographic shuffle algorithm.
 * Conceived by Quio, designed collaboratively, and coded & assembled using AI with Google Antigravity.
 */
object ShuffleEngine {

    private val secureRandom = SecureRandom()

    /**
     * Executes an unbiased, mathematically uniform Fisher-Yates permutation
     * using a cryptographically secure random number generator.
     *
     * @param items The list to shuffle.
     * @param avoidConsecutiveArtists Whether to space out consecutive tracks by the same artist.
     * @param artistExtractor Lambda returning the artist string identifier for a given item.
     * @return A newly shuffled list.
     */
    fun <T> trueShuffle(
        items: List<T>,
        avoidConsecutiveArtists: Boolean = false,
        artistExtractor: ((T) -> String?)? = null
    ): List<T> {
        if (items.size <= 1) return items.toList()

        val shuffled = items.toMutableList()
        val n = shuffled.size

        // True Fisher-Yates shuffle
        for (i in n - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val temp = shuffled[i]
            shuffled[i] = shuffled[j]
            shuffled[j] = temp
        }

        // Optional Anti-Clumping Pass
        if (avoidConsecutiveArtists && artistExtractor != null && shuffled.size > 2) {
            for (i in 0 until shuffled.size - 1) {
                val art1 = artistExtractor(shuffled[i])?.trim()?.lowercase()
                val art2 = artistExtractor(shuffled[i + 1])?.trim()?.lowercase()

                if (!art1.isNullOrEmpty() && !art2.isNullOrEmpty() && art1 == art2) {
                    // Search forward up to 10 positions for a different artist to swap with
                    val lookAheadLimit = min(shuffled.size, i + 10)
                    for (k in (i + 2) until lookAheadLimit) {
                        val artK = artistExtractor(shuffled[k])?.trim()?.lowercase()
                        if (!artK.isNullOrEmpty() && artK != art1) {
                            val temp = shuffled[i + 1]
                            shuffled[i + 1] = shuffled[k]
                            shuffled[k] = temp
                            break
                        }
                    }
                }
            }
        }

        return shuffled
    }

    /**
     * Convenience method specifically for TrackEntity lists.
     */
    fun shuffleTracks(
        tracks: List<TrackEntity>,
        avoidConsecutiveArtists: Boolean = false
    ): List<TrackEntity> {
        return trueShuffle(
            items = tracks,
            avoidConsecutiveArtists = avoidConsecutiveArtists,
            artistExtractor = { it.artist }
        )
    }
}
