package com.kiki.spotifymixer

import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.domain.ShuffleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleEngineTest {

    private fun createTrack(id: String, artist: String): TrackEntity {
        return TrackEntity(
            id = id,
            uri = "spotify:track:$id",
            title = "Title $id",
            artist = artist,
            album = "Album $id",
            durationMs = 180000L
        )
    }

    @Test
    fun testEmptyAndSingleList() {
        val empty = emptyList<TrackEntity>()
        assertEquals(emptyList<TrackEntity>(), ShuffleEngine.shuffleTracks(empty))

        val single = listOf(createTrack("1", "Daft Punk"))
        assertEquals(single, ShuffleEngine.shuffleTracks(single))
    }

    @Test
    fun testPreservesAllElements() {
        val list = (1..50).map { createTrack(it.toString(), "Artist $it") }
        val shuffled = ShuffleEngine.shuffleTracks(list)

        assertEquals(list.size, shuffled.size)
        assertEquals(list.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
    }

    @Test
    fun testRandomization() {
        val list = (1..100).map { createTrack(it.toString(), "Artist $it") }
        val shuffled = ShuffleEngine.shuffleTracks(list)

        // It is statistically astronomically unlikely for 100 items to shuffle to exact original order
        assertNotEquals(list.map { it.id }, shuffled.map { it.id })
    }

    @Test
    fun testAntiClumping() {
        // Create 20 tracks alternating between two artists
        val list = mutableListOf<TrackEntity>()
        for (i in 1..10) {
            list.add(createTrack("dp_$i", "Daft Punk"))
            list.add(createTrack("wk_$i", "The Weeknd"))
        }

        // Shuffle with anti-clumping enabled multiple times
        for (run in 1..10) {
            val shuffled = ShuffleEngine.shuffleTracks(list, avoidConsecutiveArtists = true)
            assertEquals(list.size, shuffled.size)

            // Verify consecutive same-artist tracks are minimized
            var clumpingCount = 0
            for (i in 0 until shuffled.size - 1) {
                if (shuffled[i].artist.equals(shuffled[i + 1].artist, ignoreCase = true)) {
                    clumpingCount++
                }
            }
            // In a balanced 50/50 pool with anti-clumping, clump count should be near zero or minimal
            assertTrue("Clumping count was $clumpingCount", clumpingCount < 5)
        }
    }
}
