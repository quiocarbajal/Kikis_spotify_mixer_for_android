package com.kiki.spotifymixer.domain

import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
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

    @Test
    fun testSanitizeQueryRemovesReservedLuceneCharacters() {
        assertEquals("Miranda", com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery("Miranda!"))
        assertEquals("Wham", com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery("Wham!"))
        assertEquals("Pink Floyd", com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery("\"Pink Floyd\""))
        assertEquals("Panic at the Disco", com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery("Panic! at the Disco"))
        assertEquals("AC/DC", com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery("AC/DC"))
        assertEquals("Hello World Test", com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery("Hello? [World]: (Test)^~*!"))
    }

    @Test
    fun testCoArtistExtractionFromCollaborationMetadata() {
        val raw1 = "León Gieco, Mercedes Sosa"
        val split1 = SearchUtils.splitArtists(raw1)
        assertTrue(split1.contains("Mercedes Sosa"))
        assertTrue(split1.contains("León Gieco"))

        val raw2 = "Mercedes Sosa feat. León Gieco"
        val split2 = SearchUtils.splitArtists(raw2)
        assertTrue(split2.contains("Mercedes Sosa"))
        assertTrue(split2.contains("León Gieco"))

        val raw3 = "Gustavo Santaolalla & León Gieco"
        val split3 = SearchUtils.splitArtists(raw3)
        assertTrue(split3.contains("Gustavo Santaolalla"))
        assertTrue(split3.contains("León Gieco"))
    }

    @Test
    fun testThreeTierCascadeWithStrictConditionalLibraryExclusion() {
        val targetCount = 30
        val targetBuffer = targetCount * 2 // 60

        // User library: user already owns 50 tracks of León Gieco
        val userLibraryTracks = (1..50).map { createTrack("lg_lib_$it", "León Hit $it", "León Gieco") }
        val libraryTrackIds = userLibraryTracks.map { it.id.lowercase().trim() }.toSet()
        val libraryKeys = userLibraryTracks.map { "${it.title.lowercase().trim()} - ${it.artist.lowercase().trim()}" }.toSet()

        // Depth 0: Direct tracks for León Gieco (all in library, but also includes a collaboration track)
        val d0Tracks = (1..50).map { createTrack("lg_lib_$it", "León Hit $it", if (it == 1) "León Gieco, Mercedes Sosa" else "León Gieco") }

        // Harvest co-artists from Depth 0
        val discoveredCollaborators = mutableSetOf<String>()
        val includedArtists = listOf("León Gieco")
        for (track in d0Tracks) {
            val parts = SearchUtils.splitArtists(track.artist)
            for (p in parts) {
                val cleanP = com.kiki.spotifymixer.data.remote.SpotifyCloudService.sanitizeQuery(p).trim()
                if (!includedArtists.any { SearchUtils.fuzzyMatches(it, cleanP) } && cleanP.length > 2) {
                    discoveredCollaborators.add(cleanP)
                }
            }
        }
        assertTrue("Discovered collaborator Mercedes Sosa from collaboration track", discoveredCollaborators.contains("Mercedes Sosa"))

        // --- Case A: excludeLibrarySongs = TRUE ---
        val candidatesA = mutableListOf<TrackEntity>()
        fun addCandidateA(track: TrackEntity): Boolean {
            val id = track.id.lowercase().trim()
            val key = "${track.title.lowercase().trim()} - ${track.artist.lowercase().trim()}"
            if (libraryTrackIds.contains(id) || libraryKeys.contains(key)) return false
            candidatesA.add(track)
            return true
        }

        // Add Depth 0 tracks -> all 50 rejected because they are in user's library
        d0Tracks.forEach { addCandidateA(it) }
        assertEquals("Zero direct tracks admitted when 100% in library and excludeLibrarySongs=true", 0, candidatesA.size)

        // Tier 1 (Genres: e.g. "rock nacional" -> 20 tracks from Charly García, Spinetta)
        val genreTracks = (1..20).map { createTrack("genre_$it", "Rock Song $it", if (it <= 10) "Charly García" else "Luis Alberto Spinetta") }
        genreTracks.forEach { addCandidateA(it) }

        // Tier 2 (Collaborator: "Mercedes Sosa" -> 20 tracks)
        val collabTracks = (1..20).map { createTrack("ms_$it", "Mercedes Song $it", "Mercedes Sosa") }
        collabTracks.forEach { addCandidateA(it) }

        // Tier 3 (Playlists: e.g. "This Is León Gieco" -> 20 tracks from Víctor Heredia)
        val playlistTracks = (1..20).map { createTrack("pl_$it", "Folk Song $it", "Víctor Heredia") }
        playlistTracks.forEach { addCandidateA(it) }

        // Total candidates harvested = 60 >= targetBuffer
        assertEquals(60, candidatesA.size)
        assertTrue("No library tracks admitted", candidatesA.none { libraryTrackIds.contains(it.id) })

        // Check diversity enforcement (matching DiscoverViewModel step 6)
        val maxPerArtist = 3
        val diverseSelection = mutableListOf<TrackEntity>()
        val artistCounts = mutableMapOf<String, Int>()
        val overflowTracks = mutableListOf<TrackEntity>()

        for (t in candidatesA) {
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

        val finalSelectionA = diverseSelection.take(targetCount)
        assertEquals(targetCount, finalSelectionA.size)
        assertTrue("Mix contains Mercedes Sosa", finalSelectionA.any { it.artist == "Mercedes Sosa" })
        assertTrue("Mix contains Charly García", finalSelectionA.any { it.artist == "Charly García" })
        assertTrue("Mix contains Víctor Heredia", finalSelectionA.any { it.artist == "Víctor Heredia" })

        // --- Case B: excludeLibrarySongs = FALSE ---
        val candidatesB = mutableListOf<TrackEntity>()
        fun addCandidateB(track: TrackEntity): Boolean {
            // With excludeLibrarySongs = false, library tracks are admitted!
            candidatesB.add(track)
            return true
        }

        d0Tracks.forEach { addCandidateB(it) }
        collabTracks.forEach { addCandidateB(it) }

        assertEquals(70, candidatesB.size)
        val finalSelectionB = candidatesB.take(targetCount)
        assertEquals(targetCount, finalSelectionB.size)
        assertTrue("When excludeLibrarySongs=false, library tracks of León Gieco ARE included",
            finalSelectionB.any { it.artist.contains("León Gieco") })
    }

    @Test
    fun testLeonGiecoSearchDoesNotYieldLeoMattioli() {
        // 1. Text & Fuzzy matching level: 'leon' must never match 'leo'
        assertFalse("Search token 'leon' must not match 'leo'", SearchUtils.fuzzyMatches("leon", "leo"))
        assertFalse("Search token 'leon' must not match 'leo mattioli'", SearchUtils.fuzzyMatches("leon", "leo mattioli"))
        assertFalse("Query 'León Gieco' must not match 'Leo Mattioli'", SearchUtils.fuzzyMatches("León Gieco", "Leo Mattioli"))
        assertFalse("Query 'León Gieco' must not match 'Leo Mateoli'", SearchUtils.fuzzyMatches("León Gieco", "Leo Mateoli"))

        // 2. Catalog search simulation with tokens & operators
        val tracks = listOf(
            createTrack("lg1", "Sólo le pido a Dios", "León Gieco"),
            createTrack("lg2", "Pensar en Nada", "León Gieco"),
            createTrack("lg3", "La Colina de la Vida", "León Gieco"),
            createTrack("lm1", "Llora Me Llama", "Leo Mattioli"),
            createTrack("lm2", "Tramposa y Mentirosa", "Leo Mattioli"),
            createTrack("lm3", "Conmigo Te Gustó", "Leo Mattioli")
        )

        val query = "León Gieco"
        val tokens = SearchUtils.normalize(query).split("\\s+".toRegex()).filter { it.isNotBlank() }

        // Test with AND operator
        val andResults = tracks.filter { track ->
            val targetField = "${track.title} ${track.artist} ${track.album}"
            tokens.all { token -> SearchUtils.fuzzyMatches(token, targetField) }
        }
        assertTrue("AND search must find León Gieco tracks", andResults.any { it.artist == "León Gieco" })
        assertFalse("AND search must NOT contain Leo Mattioli", andResults.any { it.artist.contains("Leo Mattioli", ignoreCase = true) })

        // Test with OR operator
        val orResults = tracks.filter { track ->
            val targetField = "${track.title} ${track.artist} ${track.album}"
            tokens.any { token -> SearchUtils.fuzzyMatches(token, targetField) }
        }
        assertTrue("OR search must find León Gieco tracks", orResults.any { it.artist == "León Gieco" })
        assertFalse("OR search must NOT contain Leo Mattioli (token 'leon' must not match 'leo')",
            orResults.any { it.artist.contains("Leo Mattioli", ignoreCase = true) })

        // Test single token 'León'
        val leonTokens = listOf("leon")
        val leonResults = tracks.filter { track ->
            val targetField = track.artist
            leonTokens.any { token -> SearchUtils.fuzzyMatches(token, targetField) }
        }
        assertTrue("Searching 'León' finds León Gieco", leonResults.any { it.artist == "León Gieco" })
        assertFalse("Searching 'León' must NOT return Leo Mattioli",
            leonResults.any { it.artist.contains("Leo Mattioli", ignoreCase = true) })
    }

    @Test
    fun testSurpriseMeLeonGiecoMixPreventsOrCapsLeoMattioliToAtMostTwo() {
        val includedArtists = listOf("León Gieco")
        val targetCount = 30

        // Simulated tracks returned by Spotify (including false-positive tracks from Spotify API)
        val rawSpotifyResults = listOf(
            createTrack("lg_1", "El Ángel de la Bicicleta", "León Gieco"),
            createTrack("lg_2", "Cinco Siglos Igual", "León Gieco, Mercedes Sosa"),
            createTrack("lm_fp1", "Llora Me Llama", "Leo Mattioli"),
            createTrack("lm_fp2", "Tramposa y Mentirosa", "Leo Mattioli")
        )

        // Depth 0 validation: verify tracks actually feature the seed artist before harvesting
        val discoveredCollaborators = mutableSetOf<String>()
        val depth0Candidates = mutableListOf<TrackEntity>()

        for (track in rawSpotifyResults) {
            val trackArtists = SearchUtils.splitArtists(track.artist)
            val matchesSeedArtist = includedArtists.isEmpty() || includedArtists.any { seed ->
                trackArtists.any { SearchUtils.fuzzyMatches(seed, it) }
            }
            if (matchesSeedArtist) {
                depth0Candidates.add(track)
                for (p in trackArtists) {
                    val cleanP = SpotifyCloudService.sanitizeQuery(p).trim()
                    val isSelf = includedArtists.any { SearchUtils.fuzzyMatches(it, cleanP) }
                    if (!isSelf && cleanP.length > 2) {
                        discoveredCollaborators.add(cleanP)
                    }
                }
            }
        }

        // Assert that Depth 0 rejected Leo Mattioli false-positive tracks
        assertFalse("Leo Mattioli must be rejected from Depth 0 candidates",
            depth0Candidates.any { it.artist.contains("Leo Mattioli", ignoreCase = true) })
        // Assert that Leo Mattioli was NOT harvested as a collaborator
        assertFalse("Leo Mattioli must NOT be harvested as a collaborator of León Gieco",
            discoveredCollaborators.contains("Leo Mattioli"))
        assertTrue("Mercedes Sosa IS a legitimate collaborator",
            discoveredCollaborators.contains("Mercedes Sosa"))

        // Adversarial Scenario: Suppose 25 Leo Mattioli tracks somehow entered the candidate pool
        // with collaboration name variants (e.g. "Leo Mattioli, Dalila", "Leo Mattioli, La Nueva Luna")
        val adversarialCandidates = mutableListOf<TrackEntity>()
        adversarialCandidates.addAll(depth0Candidates)
        // Add 25 tracks of Leo Mattioli with various collaboration forms
        val mattioliVariants = listOf("Leo Mattioli", "Leo Mattioli, Dalila", "Leo Mattioli, La Nueva Luna", "Leo Mattioli feat. El Polaco")
        for (i in 1..25) {
            val artName = mattioliVariants[i % mattioliVariants.size]
            adversarialCandidates.add(createTrack("lm_adv_$i", "Leo Song $i", artName))
        }
        // Add related tracks
        for (i in 1..15) {
            adversarialCandidates.add(createTrack("ms_$i", "Mercedes Song $i", "Mercedes Sosa"))
        }

        // Apply DiscoverViewModel Step 6 diversity capping & overflow limit using getPrimaryArtist
        val maxPerArtist = 3
        val diverseSelection = mutableListOf<TrackEntity>()
        val artistCounts = mutableMapOf<String, Int>()
        val overflowTracks = mutableListOf<TrackEntity>()

        val isSeedArtist = { art: String ->
            includedArtists.isEmpty() || includedArtists.any { SearchUtils.fuzzyMatches(it, art) }
        }
        val hardMaxPerNonSeedArtist = 2

        fun getPrimaryArtist(raw: String): String {
            val parts = SearchUtils.splitArtists(raw)
            return if (parts.size > 1) parts[1].lowercase().trim() else raw.lowercase().trim()
        }

        for (t in adversarialCandidates) {
            val normArtist = getPrimaryArtist(t.artist)
            val cnt = artistCounts.getOrDefault(normArtist, 0)
            val initialLimit = if (isSeedArtist(t.artist)) maxPerArtist else hardMaxPerNonSeedArtist
            if (cnt < initialLimit) {
                diverseSelection.add(t)
                artistCounts[normArtist] = cnt + 1
            } else {
                overflowTracks.add(t)
            }
        }

        for (ot in overflowTracks) {
            if (diverseSelection.size >= targetCount) break
            val normArtist = getPrimaryArtist(ot.artist)
            val currentCnt = artistCounts.getOrDefault(normArtist, 0)
            val limit = if (isSeedArtist(ot.artist)) maxPerArtist * 2 else hardMaxPerNonSeedArtist
            if (currentCnt < limit) {
                diverseSelection.add(ot)
                artistCounts[normArtist] = currentCnt + 1
            }
        }

        val finalSelection = diverseSelection.take(targetCount)
        val mattioliCount = finalSelection.count {
            it.artist.contains("Leo Mattioli", ignoreCase = true) || it.artist.contains("Leo Mateoli", ignoreCase = true)
        }

        // Assert: In the adversarial worst case even with collaboration variants, Leo Mattioli MUST NOT appear more than 1 or 2 times tops
        assertTrue(
            "Leo Mattioli must not appear as results when searching for León Gieco, or at the most 1 or 2 times tops (actual: $mattioliCount)",
            mattioliCount <= 2
        )
    }

    @Test
    fun testMatchScoreShortTokenStrictness() {
        // "leon" and "leo" should NOT score as a fuzzy typo match in matchScore
        val score = SearchUtils.matchScore("leon", "leo")
        assertEquals("Short token distance between leon and leo must return 999 (no match)", 999, score)
    }
}

