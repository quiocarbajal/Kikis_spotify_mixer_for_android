package com.kiki.spotifymixer.domain

import com.kiki.spotifymixer.data.local.entity.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchUtilsTest {

    @Test
    fun testNormalizeDiacritics() {
        assertEquals("leon gieco", SearchUtils.normalize("León Gieco"))
        assertEquals("charly garcia", SearchUtils.normalize("Charly García"))
        assertEquals("gustavo cerati", SearchUtils.normalize("Gustavo Cerati"))
        assertEquals("motley crue", SearchUtils.normalize("Mötley Crüe"))
        assertEquals("beyonce", SearchUtils.normalize("Beyoncé"))
        assertEquals("caetano veloso", SearchUtils.normalize("Caetano Veloso"))
        assertEquals("cancion", SearchUtils.normalize("Canción"))
        assertEquals("nino", SearchUtils.normalize("Niño"))
    }

    @Test
    fun testLevenshteinDistance() {
        assertEquals(0, SearchUtils.levenshteinDistance("leon", "leon"))
        assertEquals(1, SearchUtils.levenshteinDistance("leon", "leom")) // substitution
        assertEquals(1, SearchUtils.levenshteinDistance("leon", "len"))  // deletion
        assertEquals(1, SearchUtils.levenshteinDistance("leon", "leono")) // insertion
        assertEquals(2, SearchUtils.levenshteinDistance("leon", "leomm"))
    }

    @Test
    fun testFuzzyMatchesExactAndAccents() {
        // "leon" should match "León Gieco"
        assertTrue(SearchUtils.fuzzyMatches("leon", "León Gieco"))
        assertTrue(SearchUtils.fuzzyMatches("León", "Leon Gieco"))
        assertTrue(SearchUtils.fuzzyMatches("gieco", "León Gieco"))
        assertTrue(SearchUtils.fuzzyMatches("garcia", "Charly García"))
    }

    @Test
    fun testFuzzyMatchesOneCharacterTypo() {
        // 1-character typo tolerance for tokens with length >= 4
        assertTrue("leom should match León Gieco", SearchUtils.fuzzyMatches("leom", "León Gieco"))
        assertTrue("gieko should match León Gieco", SearchUtils.fuzzyMatches("gieko", "León Gieco"))
        assertTrue("giek should match León Gieco", SearchUtils.fuzzyMatches("giek", "León Gieco"))
        assertTrue("charly gartia should match Charly García", SearchUtils.fuzzyMatches("charly gartia", "Charly García"))

        // Short tokens (<= 3 chars) must NOT fuzzy match words of different lengths
        assertFalse("len (3 chars) should not match León Gieco", SearchUtils.fuzzyMatches("len", "León Gieco"))
        assertFalse("leon should not match leo", SearchUtils.fuzzyMatches("leon", "leo"))
        assertFalse("leo should not match leon via typo distance", SearchUtils.matchesToken("leon", "leo"))
        assertFalse("León Gieco should not match Leo Mattioli", SearchUtils.fuzzyMatches("León Gieco", "Leo Mattioli"))
        assertFalse("Leo Mattioli should not match León Gieco", SearchUtils.fuzzyMatches("Leo Mattioli", "León Gieco"))
    }

    @Test
    fun testFuzzyMatchesShortQueryStrictness() {
        // 1 or 2 character queries should only match if they are exact prefix or substring
        assertTrue(SearchUtils.fuzzyMatches("le", "León Gieco"))
        assertFalse("zx should not match León Gieco", SearchUtils.fuzzyMatches("zx", "León Gieco"))
        assertFalse("l should not match Daft Punk", SearchUtils.fuzzyMatches("l", "Daft Punk"))
    }

    @Test
    fun testThreeLetterQueryDoesNotFuzzyMatchArbitraryWords() {
        // "and" shouldn't match "around", "harder", "weeknd", "random" via typo sliding window
        assertFalse("and should not match Around The World", SearchUtils.fuzzyMatches("and", "Around The World"))
        assertFalse("and should not match Harder, Better, Faster, Stronger", SearchUtils.fuzzyMatches("and", "Harder, Better, Faster, Stronger"))
        assertFalse("and should not match The Weeknd", SearchUtils.fuzzyMatches("and", "The Weeknd"))
        assertFalse("and should not match Daft Punk", SearchUtils.fuzzyMatches("and", "Daft Punk"))
        assertFalse("and should not match Discovery", SearchUtils.fuzzyMatches("and", "Discovery"))
        // But "and" should match actual exact substrings: "Simon and Garfunkel", "Andrés Calamaro"
        assertTrue("and should match Simon and Garfunkel", SearchUtils.fuzzyMatches("and", "Simon and Garfunkel"))
        assertTrue("and should match Andrés Calamaro", SearchUtils.fuzzyMatches("and", "Andrés Calamaro"))
    }

    @Test
    fun testFuzzyMatchesMultipleTokens() {
        assertTrue(SearchUtils.fuzzyMatches("leon gieco", "León Gieco"))
        assertTrue(SearchUtils.fuzzyMatches("gieco leon", "León Gieco"))
        assertTrue(SearchUtils.fuzzyMatches("leom gieco", "León Gieco"))
        assertTrue(SearchUtils.fuzzyMatches("leon gieko", "León Gieco"))
    }

    @Test
    fun testMatchScoringOrdersExactBeforeFuzzy() {
        val exactScore = SearchUtils.matchScore("leon", "León Gieco")
        val fuzzyScore = SearchUtils.matchScore("leom", "León Gieco")

        assertTrue("Exact/normalized prefix match score should be better than fuzzy typo score", exactScore < fuzzyScore)
    }

    @Test
    fun testSplitArtists() {
        val artists = SearchUtils.splitArtists("Mercedes Sosa, León Gieco")
        assertTrue(artists.contains("Mercedes Sosa, León Gieco"))
        assertTrue(artists.contains("Mercedes Sosa"))
        assertTrue(artists.contains("León Gieco"))

        val feat = SearchUtils.splitArtists("Charly García feat. Pedro Aznar")
        assertTrue(feat.contains("Charly García"))
        assertTrue(feat.contains("Pedro Aznar"))
    }

    @Test
    fun testGetPrimaryArtist() {
        assertEquals("Drake", SearchUtils.getPrimaryArtist("Drake feat. 21 Savage"))
        assertEquals("Calvin Harris", SearchUtils.getPrimaryArtist("Calvin Harris ft. Rihanna"))
        assertEquals("Daft Punk", SearchUtils.getPrimaryArtist("Daft Punk featuring Pharrell Williams"))
        assertEquals("Daft Punk", SearchUtils.getPrimaryArtist("Daft Punk"))
        // Band names must not be split
        assertEquals("Simon & Garfunkel", SearchUtils.getPrimaryArtist("Simon & Garfunkel"))
        assertEquals("Tyler, The Creator", SearchUtils.getPrimaryArtist("Tyler, The Creator"))
        assertEquals("AC/DC", SearchUtils.getPrimaryArtist("AC/DC"))
        assertEquals("Earth, Wind & Fire", SearchUtils.getPrimaryArtist("Earth, Wind & Fire"))
    }

    @Test
    fun testPrimaryArtistOfPrefersSpotifyValue() {
        val withSpotify = track(artist = "Tyler, The Creator, Kali Uchis", primaryArtist = "Tyler, The Creator")
        assertEquals("Tyler, The Creator", SearchUtils.primaryArtistOf(withSpotify))
        val legacy = track(artist = "Future feat. Drake", primaryArtist = null)
        assertEquals("Future", SearchUtils.primaryArtistOf(legacy))
    }

    @Test
    fun testIsBlacklistedMainArtist() {
        val blacklist = setOf("Drake", "Bad Bunny")

        assertTrue("Solo Drake track must be blocked",
            SearchUtils.isBlacklistedMainArtist(track("Drake", "Drake"), blacklist))
        assertTrue("Collab with Drake as lead must be blocked",
            SearchUtils.isBlacklistedMainArtist(track("Drake, 21 Savage", "Drake"), blacklist))
        assertFalse("Collab with Drake only as guest must NOT be blocked",
            SearchUtils.isBlacklistedMainArtist(track("Travis Scott, Drake", "Travis Scott"), blacklist))

        // Legacy rows without a Spotify primary artist: lead is the first ", "-joined credit
        assertTrue("Legacy row led by Drake must be blocked",
            SearchUtils.isBlacklistedMainArtist(track("Drake, 21 Savage", null), blacklist))
        assertFalse("Legacy row with Drake as guest must NOT be blocked",
            SearchUtils.isBlacklistedMainArtist(track("Travis Scott, Drake", null), blacklist))
        assertFalse("Legacy feat. credit with Drake as guest must NOT be blocked",
            SearchUtils.isBlacklistedMainArtist(track("Future feat. Drake", null), blacklist))

        // Band names stay whole
        val bands = setOf("Simon & Garfunkel", "Tyler, The Creator")
        assertTrue(SearchUtils.isBlacklistedMainArtist(track("Simon & Garfunkel", "Simon & Garfunkel"), bands))
        assertTrue(SearchUtils.isBlacklistedMainArtist(track("Tyler, The Creator", null), bands))
        assertFalse("Blocking a band must not block a member's solo name",
            SearchUtils.isBlacklistedMainArtist(track("Paul Simon", "Paul Simon"), bands))

        // Diacritics and casing tolerance
        val latinBlacklist = setOf("leon gieco")
        assertTrue("Diacritics must match normalized blacklist entry",
            SearchUtils.isBlacklistedMainArtist(track("León Gieco", "León Gieco"), latinBlacklist))
        assertFalse("Unrelated artist must not match",
            SearchUtils.isBlacklistedMainArtist(track("Charly García", "Charly García"), latinBlacklist))
    }

    private fun track(artist: String, primaryArtist: String?) = TrackEntity(
        id = "t", uri = "spotify:track:t", title = "Song", artist = artist, album = "Album",
        durationMs = 0L, primaryArtist = primaryArtist
    )
}
