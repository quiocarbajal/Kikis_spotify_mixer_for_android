package com.kiki.spotifymixer.domain

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
        // 1-character typo tolerance
        assertTrue("leom should match León Gieco", SearchUtils.fuzzyMatches("leom", "León Gieco"))
        assertTrue("len should match León Gieco", SearchUtils.fuzzyMatches("len", "León Gieco"))
        assertTrue("gieko should match León Gieco", SearchUtils.fuzzyMatches("gieko", "León Gieco"))
        assertTrue("giek should match León Gieco", SearchUtils.fuzzyMatches("giek", "León Gieco"))
        assertTrue("charly gartia should match Charly García", SearchUtils.fuzzyMatches("charly gartia", "Charly García"))
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
}
