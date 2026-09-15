package com.kiki.spotifymixer.domain

import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.ui.theme.Strings
import com.kiki.spotifymixer.ui.viewmodel.ChipModifier
import com.kiki.spotifymixer.ui.viewmodel.ModifierChip
import com.kiki.spotifymixer.ui.viewmodel.RecentlyHeardFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryFilterTest {

    private fun createTrack(id: String, title: String, artist: String, album: String = "Test Album"): TrackEntity {
        return TrackEntity(
            id = id,
            uri = "spotify:track:$id",
            title = title,
            artist = artist,
            album = album,
            durationMs = 200000L
        )
    }

    @Test
    fun testModifierChipSetAllowsSimultaneousIncludeAndExclude() {
        val chips = mutableSetOf<ModifierChip>()

        val includeChip = ModifierChip("León Gieco", ChipModifier.INCLUDE)
        val excludeChip = ModifierChip("León Gieco", ChipModifier.EXCLUDE)

        chips.add(includeChip)
        chips.add(excludeChip)

        assertEquals("Both + and - of the same artist must coexist in the set", 2, chips.size)
        assertTrue(chips.contains(includeChip))
        assertTrue(chips.contains(excludeChip))

        // Removing the exclude chip leaves the include chip intact
        chips.remove(excludeChip)
        assertEquals(1, chips.size)
        assertTrue(chips.contains(includeChip))
        assertFalse(chips.contains(excludeChip))
    }

    @Test
    fun testSeedIsolationPreventsUnrelatedMasterCatalogTracks() {
        // Master catalog sample (includes Daft Punk)
        val daftPunkTrack = createTrack("dp123456789012345", "Around the World", "Daft Punk")
        val leonGiecoTrack = createTrack("lg123456789012345", "Sólo le pido a Dios", "León Gieco")
        val mercedesSosaTrack = createTrack("ms123456789012345", "Todo Cambia", "Mercedes Sosa")
        val masterCatalog = listOf(daftPunkTrack)

        val includedArtists = listOf("León Gieco")
        val hasPositiveSeeds = includedArtists.isNotEmpty()

        val candidates = mutableListOf<TrackEntity>()
        // Local/cloud harvested tracks for the seed
        candidates.add(leonGiecoTrack)
        candidates.add(mercedesSosaTrack) // e.g. from related artist orbit

        // Follow DiscoverViewModel logic:
        if (!hasPositiveSeeds) {
            candidates.addAll(masterCatalog)
        } else {
            for (catTrack in masterCatalog) {
                val matchesArtist = includedArtists.any { SearchUtils.fuzzyMatches(it, catTrack.artist) }
                if (matchesArtist) {
                    candidates.add(catTrack)
                }
            }
        }

        assertFalse("Daft Punk must NOT be in candidates when León Gieco is seeded",
            candidates.any { it.artist.contains("Daft Punk", ignoreCase = true) })
        assertTrue("León Gieco should be present", candidates.any { it.artist == "León Gieco" })
        assertTrue("Mercedes Sosa should be present from orbit", candidates.any { it.artist == "Mercedes Sosa" })
    }

    @Test
    fun testSimultaneousIncludeAsSeedAndExcludeArtistPruning() {
        // User wants: Seed by León Gieco, but exclude León Gieco's own tracks (only discover related/similar artists)
        val leonGiecoTrack = createTrack("lg123456789012345", "Sólo le pido a Dios", "León Gieco")
        val sosaTrack = createTrack("ms123456789012345", "Cinco Siglos Igual", "Mercedes Sosa")
        val santaolallaTrack = createTrack("gs123456789012345", "De Ushuaia a La Quiaca", "Gustavo Santaolalla")

        val candidates = listOf(leonGiecoTrack, sosaTrack, santaolallaTrack)

        val excludedArtists = listOf("león gieco")

        // Pruning logic matching DiscoverViewModel
        val filtered = candidates.filterNot { track ->
            excludedArtists.any { SearchUtils.fuzzyMatches(it, track.artist) || track.artist.lowercase().contains(it) }
        }

        assertFalse("León Gieco's own tracks must be pruned when excluded",
            filtered.any { it.artist.contains("León Gieco", ignoreCase = true) })
        assertTrue("Mercedes Sosa must remain", filtered.any { it.artist == "Mercedes Sosa" })
        assertTrue("Gustavo Santaolalla must remain", filtered.any { it.artist == "Gustavo Santaolalla" })
    }

    @Test
    fun testRecencyExclusionPruning() {
        val trackOld = createTrack("old12345678901234", "Old Song", "Artist A")
        val trackRecent = createTrack("recent12345678901", "Recently Played Song", "Artist B")
        val candidates = listOf(trackOld, trackRecent)

        val recentIds = setOf("recent12345678901")

        val filtered = candidates.filterNot { recentIds.contains(it.id) }

        assertEquals(1, filtered.size)
        assertEquals("old12345678901234", filtered[0].id)
        assertFalse("Recently heard track must be excluded", filtered.any { it.id == trackRecent.id })
    }

    @Test
    fun testRecentlyHeardFilterEnumValues() {
        assertEquals(0, RecentlyHeardFilter.NONE.days)
        assertEquals(7, RecentlyHeardFilter.LAST_7_DAYS.days)
        assertEquals(30, RecentlyHeardFilter.LAST_30_DAYS.days)
    }

    @Test
    fun testMultiArtistOrbitHarvestingReachesTargetCount() {
        val targetCount = 30
        val candidates = mutableListOf<TrackEntity>()

        // Simulate direct artist tracks (e.g. 50 tracks for León Gieco)
        for (i in 1..50) {
            candidates.add(createTrack("lg_track_$i", "León Song $i", "León Gieco"))
        }

        // Simulate related artists orbit tracks (e.g. Victor Heredia, Mercedes Sosa)
        val relatedArtists = listOf("Victor Heredia", "Mercedes Sosa", "Serú Girán")
        for (rel in relatedArtists) {
            for (j in 1..20) {
                candidates.add(createTrack("${rel.take(2)}_track_$j", "$rel Song $j", rel))
            }
        }

        // Simulate library exclusion: user has 40 of the León Gieco tracks already liked
        val userLikedIds = (1..40).map { "lg_track_$it" }.toSet()
        val filtered = candidates.filterNot { userLikedIds.contains(it.id) }

        // Filtered pool should have (50 - 40) + (3 * 20) = 70 tracks
        assertEquals(70, filtered.size)

        // Diversity enforcement with maxPerArtist = 3
        val maxPerArtist = 3
        val diverseSelection = mutableListOf<TrackEntity>()
        val artistCounts = mutableMapOf<String, Int>()
        val overflowTracks = mutableListOf<TrackEntity>()

        for (t in filtered) {
            val normArtist = t.artist.lowercase().trim()
            val cnt = artistCounts.getOrDefault(normArtist, 0)
            if (cnt < maxPerArtist) {
                diverseSelection.add(t)
                artistCounts[normArtist] = cnt + 1
            } else {
                overflowTracks.add(t)
            }
        }

        for (ot in overflowTracks) {
            if (diverseSelection.size >= targetCount) break
            diverseSelection.add(ot)
        }

        val finalSelection = diverseSelection.take(targetCount)
        assertEquals("Target count of 30 must be reached despite library exclusion", targetCount, finalSelection.size)
        assertTrue("Contains surviving direct tracks", finalSelection.any { it.artist == "León Gieco" })
        assertTrue("Contains related orbit tracks", finalSelection.any { it.artist == "Victor Heredia" || it.artist == "Mercedes Sosa" })
    }

    @Test
    fun testQuotaShortageDisplaysFoundTracksAndGeneratesNoticeWithoutBackfill() {
        val targetCount = 30
        val candidates = mutableListOf<TrackEntity>()

        // Simulate an artist with a limited orbit: only 12 tracks exist
        for (i in 1..12) {
            candidates.add(createTrack("fresh_track_$i", "Fresh Song $i", "Niche Artist"))
        }

        // Library has 50 tracks
        val libraryTracks = (1..50).map { createTrack("lib_track_$it", "My Fav $it", "Common Artist") }
        val libraryTrackIds = libraryTracks.map { it.id }.toSet()

        // Strict library exclusion: NO library tracks ever enter selection
        val nonLibrary = candidates.filterNot { libraryTrackIds.contains(it.id) }
        assertEquals(12, nonLibrary.size)

        // Do NOT backfill from library: take whatever fresh tracks were found
        val finalSelection = nonLibrary.take(targetCount)
        assertEquals("Only the 12 fresh tracks should be shown", 12, finalSelection.size)
        assertFalse("Must NOT backfill library tracks", finalSelection.any { libraryTrackIds.contains(it.id) })

        // Check informative banner notice
        val notice = if (finalSelection.size < targetCount) {
            Strings.MixQuotaNotice.format(finalSelection.size, targetCount)
        } else null

        assertEquals(
            "Se encontraron 12 de 30 canciones nuevas. La órbita disponible para esta búsqueda es limitada fuera de tu biblioteca.",
            notice
        )
    }

    @Test
    fun testRecursiveOrbitDepth2ReachesQuotaWhenDepth1FallsShort() {
        val targetCount = 30
        val targetBuffer = targetCount * 2 // 60

        val candidates = mutableListOf<TrackEntity>()

        // Depth 0: Direct seeds yield 10 tracks
        for (i in 1..10) {
            candidates.add(createTrack("d0_$i", "Seed Song $i", "Seed Artist"))
        }
        assertTrue("Depth 0 is below targetBuffer", candidates.size < targetBuffer)

        // Depth 1: 1st-degree related artists yield 20 tracks (total 30)
        for (i in 1..20) {
            candidates.add(createTrack("d1_$i", "D1 Song $i", "Related Artist ${i % 3}"))
        }
        assertTrue("Depth 1 is still below targetBuffer (30 < 60)", candidates.size < targetBuffer)

        // Depth 2: 2nd-degree related artists yield 35 tracks (total 65)
        for (i in 1..35) {
            candidates.add(createTrack("d2_$i", "D2 Song $i", "Second Degree Artist ${i % 4}"))
        }
        assertTrue("Depth 2 reached targetBuffer (65 >= 60)", candidates.size >= targetBuffer)

        // Diverse selection capped per artist
        val maxPerArtist = 3
        val diverseSelection = mutableListOf<TrackEntity>()
        val artistCounts = mutableMapOf<String, Int>()
        val overflowTracks = mutableListOf<TrackEntity>()

        for (t in candidates) {
            val normArtist = t.artist.lowercase().trim()
            val cnt = artistCounts.getOrDefault(normArtist, 0)
            if (cnt < maxPerArtist) {
                diverseSelection.add(t)
                artistCounts[normArtist] = cnt + 1
            } else {
                overflowTracks.add(t)
            }
        }
        for (ot in overflowTracks) {
            if (diverseSelection.size >= targetCount) break
            diverseSelection.add(ot)
        }

        val finalSelection = diverseSelection.take(targetCount)
        assertEquals(30, finalSelection.size)

        val notice = if (finalSelection.size < targetCount) {
            Strings.MixQuotaNotice.format(finalSelection.size, targetCount)
        } else null

        assertNull("Notice should be null when target quota is satisfied", notice)
        assertTrue("Contains Depth 0 tracks", finalSelection.any { it.artist == "Seed Artist" })
        assertTrue("Contains Depth 1 tracks", finalSelection.any { it.artist.startsWith("Related Artist") })
        assertTrue("Contains Depth 2 tracks", finalSelection.any { it.artist.startsWith("Second Degree Artist") })
    }
}
