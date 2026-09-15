package com.kiki.spotifymixer.domain

data class GenreItem(
    val id: String,
    val name: String,
    val category: String
)

object GenreCatalog {

    val CATEGORIES = listOf(
        "All",
        "Popular",
        "Rock & Indie",
        "Electronic & Dance",
        "Latin & World",
        "Chill & Acoustic",
        "Hip-Hop & R&B",
        "Jazz & Classical",
        "Metal & Hard",
        "Asian Pop",
        "Decades & Roots"
    )

    val ALL_GENRES: List<GenreItem> = listOf(
        // Popular / Core
        GenreItem("pop", "Pop", "Popular"),
        GenreItem("rock", "Rock", "Popular"),
        GenreItem("indie", "Indie", "Popular"),
        GenreItem("hip-hop", "Hip-Hop", "Popular"),
        GenreItem("electronic", "Electronic", "Popular"),
        GenreItem("r-n-b", "R&B", "Popular"),
        GenreItem("latin", "Latin", "Popular"),
        GenreItem("dance", "Dance", "Popular"),
        GenreItem("house", "House", "Popular"),
        GenreItem("chill", "Chill", "Popular"),
        GenreItem("alternative", "Alternative", "Popular"),

        // Rock & Indie
        GenreItem("alt-rock", "Alt Rock", "Rock & Indie"),
        GenreItem("indie-pop", "Indie Pop", "Rock & Indie"),
        GenreItem("grunge", "Grunge", "Rock & Indie"),
        GenreItem("punk", "Punk", "Rock & Indie"),
        GenreItem("punk-rock", "Punk Rock", "Rock & Indie"),
        GenreItem("hard-rock", "Hard Rock", "Rock & Indie"),
        GenreItem("psych-rock", "Psych Rock", "Rock & Indie"),
        GenreItem("power-pop", "Power Pop", "Rock & Indie"),
        GenreItem("emo", "Emo", "Rock & Indie"),
        GenreItem("goth", "Goth", "Rock & Indie"),
        GenreItem("guitar", "Guitar", "Rock & Indie"),
        GenreItem("post-dubstep", "Post-Rock/Dub", "Rock & Indie"),

        // Electronic & Dance
        GenreItem("techno", "Techno", "Electronic & Dance"),
        GenreItem("deep-house", "Deep House", "Electronic & Dance"),
        GenreItem("synth-pop", "Synth-Pop", "Electronic & Dance"),
        GenreItem("disco", "Disco", "Electronic & Dance"),
        GenreItem("edm", "EDM", "Electronic & Dance"),
        GenreItem("electro", "Electro", "Electronic & Dance"),
        GenreItem("trance", "Trance", "Electronic & Dance"),
        GenreItem("dubstep", "Dubstep", "Electronic & Dance"),
        GenreItem("drum-and-bass", "Drum & Bass", "Electronic & Dance"),
        GenreItem("club", "Club", "Electronic & Dance"),
        GenreItem("breakbeat", "Breakbeat", "Electronic & Dance"),
        GenreItem("minimal-techno", "Minimal Techno", "Electronic & Dance"),
        GenreItem("progressive-house", "Progressive House", "Electronic & Dance"),
        GenreItem("idm", "IDM / Brainwave", "Electronic & Dance"),
        GenreItem("trip-hop", "Trip-Hop", "Electronic & Dance"),
        GenreItem("chicago-house", "Chicago House", "Electronic & Dance"),
        GenreItem("detroit-techno", "Detroit Techno", "Electronic & Dance"),
        GenreItem("garage", "UK Garage", "Electronic & Dance"),
        GenreItem("hardstyle", "Hardstyle", "Electronic & Dance"),

        // Hip-Hop & R&B
        GenreItem("soul", "Soul", "Hip-Hop & R&B"),
        GenreItem("funk", "Funk", "Hip-Hop & R&B"),
        GenreItem("groove", "Groove", "Hip-Hop & R&B"),
        GenreItem("gospel", "Gospel", "Hip-Hop & R&B"),

        // Latin & World
        GenreItem("latino", "Latino Hits", "Latin & World"),
        GenreItem("reggaeton", "Reggaeton", "Latin & World"),
        GenreItem("salsa", "Salsa", "Latin & World"),
        GenreItem("samba", "Samba", "Latin & World"),
        GenreItem("tango", "Tango", "Latin & World"),
        GenreItem("bossanova", "Bossa Nova", "Latin & World"),
        GenreItem("mpb", "MPB", "Latin & World"),
        GenreItem("pagode", "Pagode", "Latin & World"),
        GenreItem("sertanejo", "Sertanejo", "Latin & World"),
        GenreItem("forro", "Forró", "Latin & World"),
        GenreItem("brazil", "Brazil Vibe", "Latin & World"),
        GenreItem("afrobeat", "Afrobeat", "Latin & World"),
        GenreItem("reggae", "Reggae", "Latin & World"),
        GenreItem("dancehall", "Dancehall", "Latin & World"),
        GenreItem("ska", "Ska", "Latin & World"),
        GenreItem("world-music", "World Music", "Latin & World"),
        GenreItem("spanish", "Spanish Hits", "Latin & World"),

        // Chill & Acoustic
        GenreItem("acoustic", "Acoustic", "Chill & Acoustic"),
        GenreItem("ambient", "Ambient", "Chill & Acoustic"),
        GenreItem("folk", "Folk", "Chill & Acoustic"),
        GenreItem("singer-songwriter", "Singer-Songwriter", "Chill & Acoustic"),
        GenreItem("piano", "Piano", "Chill & Acoustic"),
        GenreItem("study", "Study Beats", "Chill & Acoustic"),
        GenreItem("sleep", "Sleep", "Chill & Acoustic"),
        GenreItem("rainy-day", "Rainy Day", "Chill & Acoustic"),
        GenreItem("road-trip", "Road Trip", "Chill & Acoustic"),
        GenreItem("summer", "Summer", "Chill & Acoustic"),
        GenreItem("romance", "Romance", "Chill & Acoustic"),
        GenreItem("sad", "Melancholy / Sad", "Chill & Acoustic"),
        GenreItem("new-age", "New Age", "Chill & Acoustic"),

        // Jazz & Classical
        GenreItem("jazz", "Jazz", "Jazz & Classical"),
        GenreItem("blues", "Blues", "Jazz & Classical"),
        GenreItem("classical", "Classical", "Jazz & Classical"),
        GenreItem("opera", "Opera", "Jazz & Classical"),

        // Metal & Hard
        GenreItem("metal", "Metal", "Metal & Hard"),
        GenreItem("heavy-metal", "Heavy Metal", "Metal & Hard"),
        GenreItem("metalcore", "Metalcore", "Metal & Hard"),
        GenreItem("black-metal", "Black Metal", "Metal & Hard"),
        GenreItem("death-metal", "Death Metal", "Metal & Hard"),
        GenreItem("grindcore", "Grindcore", "Metal & Hard"),
        GenreItem("hardcore", "Hardcore", "Metal & Hard"),
        GenreItem("industrial", "Industrial", "Metal & Hard"),

        // Asian Pop
        GenreItem("k-pop", "K-Pop", "Asian Pop"),
        GenreItem("j-pop", "J-Pop", "Asian Pop"),
        GenreItem("j-rock", "J-Rock", "Asian Pop"),
        GenreItem("j-dance", "J-Dance", "Asian Pop"),
        GenreItem("j-idol", "J-Idol", "Asian Pop"),
        GenreItem("cantopop", "Cantopop", "Asian Pop"),
        GenreItem("mandopop", "Mandopop", "Asian Pop"),
        GenreItem("anime", "Anime Soundtracks", "Asian Pop"),
        GenreItem("philippines-opm", "Pinoy OPM", "Asian Pop"),
        GenreItem("malay", "Malay Hits", "Asian Pop"),
        GenreItem("indian", "Indian / Bollywood", "Asian Pop"),

        // Decades & Roots
        GenreItem("rock-n-roll", "Rock & Roll", "Decades & Roots"),
        GenreItem("rockabilly", "Rockabilly", "Decades & Roots"),
        GenreItem("bluegrass", "Bluegrass", "Decades & Roots"),
        GenreItem("country", "Country", "Decades & Roots"),
        GenreItem("honky-tonk", "Honky-Tonk", "Decades & Roots"),
        GenreItem("show-tunes", "Show Tunes", "Decades & Roots"),
        GenreItem("soundtracks", "Movie Soundtracks", "Decades & Roots")
    )

    fun searchGenres(query: String = "", category: String? = null): List<GenreItem> {
        val q = query.trim()
        return ALL_GENRES.filter { item ->
            val matchCategory = category.isNullOrBlank() || category.equals("All", ignoreCase = true) || item.category.equals(category, ignoreCase = true)
            val matchQuery = q.isEmpty() || SearchUtils.fuzzyMatches(q, item.id) || SearchUtils.fuzzyMatches(q, item.name)
            matchCategory && matchQuery
        }.sortedWith(
            compareBy<GenreItem> { item ->
                minOf(
                    SearchUtils.matchScore(q, item.id),
                    SearchUtils.matchScore(q, item.name)
                )
            }.thenBy { it.name.lowercase() }
        )
    }
}
