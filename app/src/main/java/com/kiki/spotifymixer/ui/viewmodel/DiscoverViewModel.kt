package com.kiki.spotifymixer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kiki.spotifymixer.auth.SpotifyPkceAuthManager
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.data.remote.SpotifyCloudService
import com.kiki.spotifymixer.data.repository.SpotifyMixerRepository
import com.kiki.spotifymixer.domain.GenreCatalog
import com.kiki.spotifymixer.domain.SearchUtils
import com.kiki.spotifymixer.domain.ShuffleEngine
import com.kiki.spotifymixer.ui.theme.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

enum class ChipModifier {
    INCLUDE, // [+ AND / + Y]
    EXCLUDE  // [- NOT / - NO]
}

data class ModifierChip(
    val term: String,
    val modifier: ChipModifier
)

enum class RecentlyHeardFilter(val days: Int, val label: String) {
    NONE(0, "Ninguno"),
    LAST_7_DAYS(7, "7 días"),
    LAST_30_DAYS(30, "30 días")
}

enum class CatalogSearchType {
    ALL,    // 🌟 Todo
    ARTIST, // 👤 Artista
    TRACK,  // 🎵 Canción
    LYRICS  // 📜 Letra
}

enum class SearchLogicOperator {
    AND, // Y (Todas las palabras)
    OR   // O (Cualquiera de las palabras)
}

data class DiscoverUiState(
    val activeSubTab: Int = 0, // 0 = Surprise Me!, 1 = Catalog Search
    val searchQuery: String = "",
    val searchResults: List<TrackEntity> = emptyList(),
    val catalogSearchType: CatalogSearchType = CatalogSearchType.ALL,
    val catalogOperator: SearchLogicOperator = SearchLogicOperator.AND,
    val suggestedCatalogQueries: List<String> = emptyList(),
    val catalogModifiers: Set<ModifierChip> = emptySet(),
    val isSearchingCatalog: Boolean = false,
    val artistInputText: String = "",
    val suggestedArtists: List<String> = emptyList(),
    val artistModifiers: Set<ModifierChip> = emptySet(),
    val genreInputText: String = "",
    val suggestedGenres: List<String> = emptyList(),
    val selectedGenreCategory: String = "All",
    val genreModifiers: Set<ModifierChip> = emptySet(),
    val trackInputText: String = "",
    val suggestedTracks: List<TrackEntity> = emptyList(),
    val trackModifiers: Set<ModifierChip> = emptySet(),
    val selectedDecades: Set<String> = emptySet(),
    val excludeLibrarySongs: Boolean = true,
    val recentlyHeardFilter: RecentlyHeardFilter = RecentlyHeardFilter.NONE,
    val targetCount: Int = 30, // 30, 50, 75, 100 (matching Mac app)
    val lowPopularityOnly: Boolean = false, // 💎 Hidden Gems switch
    val hiddenGemTarget: String = "artist", // "artist", "track", "both"
    val isGeneratingMix: Boolean = false,
    val discoveredMix: List<TrackEntity> = emptyList(),
    val infoBannerMessage: String? = null
)

class DiscoverViewModel(
    private val repository: SpotifyMixerRepository,
    private val cloudService: SpotifyCloudService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private val allKnownArtists = listOf(
        "Soda Stereo", "Gustavo Cerati", "Charly García", "León Gieco", "Los Enanitos Verdes", "Luis Alberto Spinetta",
        "Babasónicos", "Fito Páez", "Virus", "Patricio Rey", "Andrés Calamaro", "Los Fabulosos Cadillacs",
        "The Cranberries", "Cranberries", "Daft Punk", "The Weeknd", "M83",
        "Arctic Monkeys", "Queen", "Fleetwood Mac", "Tame Impala", "Gorillaz",
        "New Order", "Depeche Mode", "Michael Jackson", "Nirvana", "Radiohead",
        "Dua Lipa", "Billie Eilish", "Coldplay", "The Beatles", "Pink Floyd",
        "Led Zeppelin", "David Bowie", "The Rolling Stones", "Red Hot Chili Peppers",
        "Oasis", "Blur", "The Cure", "The Smiths", "U2", "AC/DC",
        "Guns N' Roses", "Metallica", "Aerosmith", "The Police", "Stevie Wonder",
        "Prince", "Madonna", "ABBA", "Elton John", "Bee Gees", "Bob Marley",
        "The Strokes", "Phoenix", "The Killers", "Franz Ferdinand", "MGMT",
        "Foster The People", "The White Stripes", "Interpol", "Two Door Cinema Club", "Vampire Weekend",
        "Pearl Jam", "Smashing Pumpkins", "Tears for Fears", "Joy Division", "Talking Heads", "a-ha"
    )

    // Master Catalog with 160+ authentic, real songs
    private val masterCatalog = listOf(
        TrackEntity("0vrZoFXahwpthoKGWI4L96", "spotify:track:0vrZoFXahwpthoKGWI4L96", "Zombie", "The Cranberries", "20th Century Masters - The Millennium Collection: The Best Of The Cranberries", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2736a9239356b0d3d29f184aa66", durationMs = 307440L, popularity = 50, artistPopularity = 50),
        TrackEntity("3HHehSGzW9dhs2V7Sod4jX", "spotify:track:3HHehSGzW9dhs2V7Sod4jX", "Linger", "The Cranberries", "Stars: The Best Of The Cranberries 1992-2002", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2736c4efca9f5e949a2f9cc3ac4", durationMs = 274773L, popularity = 50, artistPopularity = 50),
        TrackEntity("5xRZQa1Cw33PLDMYwYJqWW", "spotify:track:5xRZQa1Cw33PLDMYwYJqWW", "Dreams - Remastered 2026", "The Cranberries", "Everybody Else Is Doing It, So Why Can't We? (Deluxe / Remastered 2026)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2731d4abdbd69dfd81d4a8fd551", durationMs = 273706L, popularity = 50, artistPopularity = 50),
        TrackEntity("3k8qSv5e8ALW6tA9cpY9mm", "spotify:track:3k8qSv5e8ALW6tA9cpY9mm", "Ode To My Family - 2025 Remastered", "The Cranberries", "No Need To Argue (2025 Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27320a28883cdd0fc7d5f85a9a9", durationMs = 270600L, popularity = 50, artistPopularity = 50),
        TrackEntity("4it4NYn9wNqGV54joA6oN0", "spotify:track:4it4NYn9wNqGV54joA6oN0", "De Música Ligera", "Soda Stereo", "Obras Cumbres (Parte 2)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2731bbe81fc1e928f05149e49ab", durationMs = 213453L, popularity = 50, artistPopularity = 50),
        TrackEntity("7J2885UBOaG6x3LLkp2YGf", "spotify:track:7J2885UBOaG6x3LLkp2YGf", "En La Ciudad De La Furia - Remasterizado 2007", "Soda Stereo", "Doble Vida (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27382cf3a9e2e2b6b1b3f994d38", durationMs = 346053L, popularity = 50, artistPopularity = 50),
        TrackEntity("71awpJoi5bqGMBrTkHDDoL", "spotify:track:71awpJoi5bqGMBrTkHDDoL", "Persiana Americana - Remasterizado 2007", "Soda Stereo", "Signos (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734a6f5598c41e77ae78cab725", durationMs = 292266L, popularity = 50, artistPopularity = 50),
        TrackEntity("1Tja3miBbbENpqIOAqGWXa", "spotify:track:1Tja3miBbbENpqIOAqGWXa", "Prófugos - Remasterizado 2007", "Soda Stereo", "Signos (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734a6f5598c41e77ae78cab725", durationMs = 317613L, popularity = 50, artistPopularity = 50),
        TrackEntity("65DBZofI0b79kfHTcWWDuU", "spotify:track:65DBZofI0b79kfHTcWWDuU", "Trátame Suavemente - Remasterizado 2007", "Soda Stereo", "Soda Stereo (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738fd9b16eb6bdd20c95717258", durationMs = 200800L, popularity = 50, artistPopularity = 50),
        TrackEntity("3uMYq07Kj5m564OQwdSCrD", "spotify:track:3uMYq07Kj5m564OQwdSCrD", "Cuando Pase El Temblor - Remasterizado 2007", "Soda Stereo", "Nada Personal (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e28947f6ad2f100af9c1965a", durationMs = 229160L, popularity = 50, artistPopularity = 50),
        TrackEntity("3oqWr0jDWNXxWufNogGREp", "spotify:track:3oqWr0jDWNXxWufNogGREp", "Crimen", "Gustavo Cerati", "Ahí Vamos", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273f89f01679779a2246c78bc7a", durationMs = 232026L, popularity = 50, artistPopularity = 50),
        TrackEntity("6gwaa6ElIixNTvu6RwkMyo", "spotify:track:6gwaa6ElIixNTvu6RwkMyo", "Puente", "Gustavo Cerati", "Bocanada", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2731152471596980e1bba03b6ab", durationMs = 274444L, popularity = 50, artistPopularity = 50),
        TrackEntity("1rh4kDY9T4fHVDum8Foi5k", "spotify:track:1rh4kDY9T4fHVDum8Foi5k", "Adiós", "Gustavo Cerati", "Ahí Vamos", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273f89f01679779a2246c78bc7a", durationMs = 233746L, popularity = 50, artistPopularity = 50),
        TrackEntity("29iIRrSuANyjnwag69PHOJ", "spotify:track:29iIRrSuANyjnwag69PHOJ", "Demoliendo Hoteles", "Charly García", "Piano Bar", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273a89f53c13537b933a43d2600", durationMs = 135973L, popularity = 50, artistPopularity = 50),
        TrackEntity("4VikOud5ZmdmHH6h7uQeDB", "spotify:track:4VikOud5ZmdmHH6h7uQeDB", "Nos Siguen Pegando Abajo", "Charly García", "Clics Modernos", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b14842a87b833bc0a9339f60", durationMs = 206720L, popularity = 50, artistPopularity = 50),
        TrackEntity("2MXqrO1RBfek6RoijghYYp", "spotify:track:2MXqrO1RBfek6RoijghYYp", "Promesas Sobre El Bidet", "Charly García", "Piano Bar", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273a89f53c13537b933a43d2600", durationMs = 164360L, popularity = 50, artistPopularity = 50),
        TrackEntity("3k9dGCm2R2Y70e87aMHYC3", "spotify:track:3k9dGCm2R2Y70e87aMHYC3", "Hablando a Tu Corazón", "Charly García, Pedro Aznar", "García 87/93", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27342542c948d51893462874c89", durationMs = 255266L, popularity = 50, artistPopularity = 50),
        TrackEntity("6dOzqs6o4DNzXs4jF2v1Yq", "spotify:track:6dOzqs6o4DNzXs4jF2v1Yq", "Rezo por Vos", "Charly García", "Parte De La Religion", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e4aaeab8b89233708ba856b9", durationMs = 269053L, popularity = 50, artistPopularity = 50),
        TrackEntity("6hmYi0E6EBEmDeztQHaH0C", "spotify:track:6hmYi0E6EBEmDeztQHaH0C", "Bajan", "Pescado Rabioso", "Artaud", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27350db5a166ea23d5d6c4cd387", durationMs = 204466L, popularity = 50, artistPopularity = 50),
        TrackEntity("6R8L42PFd2OGP32rpMZ1XN", "spotify:track:6R8L42PFd2OGP32rpMZ1XN", "Seguir Viviendo Sin Tu Amor - Remasterizado 2025", "Luis Alberto Spinetta", "Pelusón Of Milk (Remasterizado 2025)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b106e43073dbf04202879816", durationMs = 155035L, popularity = 50, artistPopularity = 50),
        TrackEntity("6GPkBUXMrGSkwK3TrrHHXN", "spotify:track:6GPkBUXMrGSkwK3TrrHHXN", "Muchacha (Ojos de Papel)", "Almendra", "Almendra (50 Años)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273ccf6d7a68e129185513921e8", durationMs = 184480L, popularity = 50, artistPopularity = 50),
        TrackEntity("62FQCSvSUSG3m9uajVkvWe", "spotify:track:62FQCSvSUSG3m9uajVkvWe", "Lamento Boliviano", "Los Enanitos Verdes", "Coleccion Aniversario", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738d527c66da6bf8cbdba78814", durationMs = 223146L, popularity = 50, artistPopularity = 50),
        TrackEntity("6OKhBvddAlWxxFnjbpilhu", "spotify:track:6OKhBvddAlWxxFnjbpilhu", "La Muralla Verde", "Los Enanitos Verdes", "Contrareloj", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273c3d72bdf184c8ca79d8a1d0b", durationMs = 161440L, popularity = 50, artistPopularity = 50),
        TrackEntity("3V9dPuQWZOUQY3KYJJWnP3", "spotify:track:3V9dPuQWZOUQY3KYJJWnP3", "Guitarras Blancas", "Los Enanitos Verdes", "Originales - 20 Exitos", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b73b130ac6b4b5bef099831a", durationMs = 266826L, popularity = 50, artistPopularity = 50),
        TrackEntity("0dsViRiDTIuexAL42Nc1Kh", "spotify:track:0dsViRiDTIuexAL42Nc1Kh", "Irresponsables", "Babasonicos", "Infame", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734febc01367fbd2d1ec9f1d60", durationMs = 156197L, popularity = 50, artistPopularity = 50),
        TrackEntity("0SaKhiBPgdRaQKA7Bxu3ek", "spotify:track:0SaKhiBPgdRaQKA7Bxu3ek", "Putita", "Babasonicos", "Infame", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734febc01367fbd2d1ec9f1d60", durationMs = 225706L, popularity = 50, artistPopularity = 50),
        TrackEntity("1devU3Tqs5bMqo1GtWQhoa", "spotify:track:1devU3Tqs5bMqo1GtWQhoa", "El Colmo", "Babasonicos", "Anoche", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2737f947995d409ba37ef11b82d", durationMs = 160786L, popularity = 50, artistPopularity = 50),
        TrackEntity("0FbilOaXvc1w16gPL096nb", "spotify:track:0FbilOaXvc1w16gPL096nb", "Mariposa tecknicolor", "Fito Paez", "ROCK NACIONAL Y MÁS ROCK VOL.II", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2736a81c14b3150fee00fe0df68", durationMs = 222346L, popularity = 50, artistPopularity = 50),
        TrackEntity("2PkTjm1QAJCZbJ9MqC4RqA", "spotify:track:2PkTjm1QAJCZbJ9MqC4RqA", "11 Y 6", "Fito Paez", "Giros", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273921417baf700cc9da1d43b80", durationMs = 178266L, popularity = 50, artistPopularity = 50),
        TrackEntity("1PQzZbitOJ6XPFg7FFzsKQ", "spotify:track:1PQzZbitOJ6XPFg7FFzsKQ", "El amor después del amor", "Fito Paez", "El Amor Después del Amor", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b80f37385bd537d17f9be93a", durationMs = 311893L, popularity = 50, artistPopularity = 50),
        TrackEntity("1g02x46MYE1gclJfGQiQbA", "spotify:track:1g02x46MYE1gclJfGQiQbA", "Una Luna de Miel en la Mano - En Vivo", "Virus, Ale Sergi", "Caja Negra", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273281627614f72c23f402f59a5", durationMs = 335240L, popularity = 50, artistPopularity = 50),
        TrackEntity("6hiXQhwO98tEbNNbWtNS1Y", "spotify:track:6hiXQhwO98tEbNNbWtNS1Y", "Imágenes Paganas", "Virus", "Vivo", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273c19f681b4af6195db4ced77b", durationMs = 269093L, popularity = 50, artistPopularity = 50),
        TrackEntity("4UXE0ZLGIoLUOFqTECzoQe", "spotify:track:4UXE0ZLGIoLUOFqTECzoQe", "Pronta Entrega", "Virus", "Locura", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273292c1ca632a1e120d1208c95", durationMs = 273306L, popularity = 50, artistPopularity = 50),
        TrackEntity("2Foc5Q5nqNiosCNqttzHof", "spotify:track:2Foc5Q5nqNiosCNqttzHof", "Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]", "Daft Punk, Pharrell Williams, Nile Rodgers", "Get Lucky (Radio Edit) [feat. Pharrell Williams and Nile Rodgers]", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27319a88dd5c7118e87d7b1619e", durationMs = 247632L, popularity = 50, artistPopularity = 50),
        TrackEntity("2cGxRwrMyEAp8dEbuZaVv6", "spotify:track:2cGxRwrMyEAp8dEbuZaVv6", "Instant Crush (feat. Julian Casablancas)", "Daft Punk, Julian Casablancas", "Random Access Memories", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2739b9b36b0e22870b9f542d937", durationMs = 337560L, popularity = 50, artistPopularity = 50),
        TrackEntity("0DiWol3AO6WpXZgp0goxAV", "spotify:track:0DiWol3AO6WpXZgp0goxAV", "One More Time", "Daft Punk", "Discovery", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2731e81bff9807a9e629fce5ade", durationMs = 320357L, popularity = 50, artistPopularity = 50),
        TrackEntity("5W3cjX2J3tjhG8zb6u0qHn", "spotify:track:5W3cjX2J3tjhG8zb6u0qHn", "Harder, Better, Faster, Stronger", "Daft Punk", "Discovery", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2731e81bff9807a9e629fce5ade", durationMs = 226413L, popularity = 50, artistPopularity = 50),
        TrackEntity("1pKYYY0dkg23sQQXi0Q5zN", "spotify:track:1pKYYY0dkg23sQQXi0Q5zN", "Around the World", "Daft Punk", "Homework", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738ac778cc7d88779f74d33311", durationMs = 429533L, popularity = 50, artistPopularity = 50),
        TrackEntity("7MXVkk9YMctZqd1Srtv4MB", "spotify:track:7MXVkk9YMctZqd1Srtv4MB", "Starboy", "The Weeknd, Daft Punk", "Starboy", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734718e2b124f79258be7bc452", durationMs = 230453L, popularity = 50, artistPopularity = 50),
        TrackEntity("0VjIjW4GlUZAMYd2vXMi3b", "spotify:track:0VjIjW4GlUZAMYd2vXMi3b", "Blinding Lights", "The Weeknd", "After Hours", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36", durationMs = 200040L, popularity = 50, artistPopularity = 50),
        TrackEntity("5QO79kh1waicV47BqGRL3g", "spotify:track:5QO79kh1waicV47BqGRL3g", "Save Your Tears", "The Weeknd", "After Hours", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36", durationMs = 215626L, popularity = 50, artistPopularity = 50),
        TrackEntity("5FVd6KXrgO9B3JPmC8OPst", "spotify:track:5FVd6KXrgO9B3JPmC8OPst", "Do I Wanna Know?", "Arctic Monkeys", "AM", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734ae1c4c5c45aabe565499163", durationMs = 272394L, popularity = 50, artistPopularity = 50),
        TrackEntity("2AT8iROs4FQueDv2c8q2KE", "spotify:track:2AT8iROs4FQueDv2c8q2KE", "R U Mine?", "Arctic Monkeys", "AM", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734ae1c4c5c45aabe565499163", durationMs = 201726L, popularity = 50, artistPopularity = 50),
        TrackEntity("0BxE4FqsDD1Ot4YuBXwAPp", "spotify:track:0BxE4FqsDD1Ot4YuBXwAPp", "505", "Arctic Monkeys", "Favourite Worst Nightmare", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b1f8da74f225fa1225cdface", durationMs = 253586L, popularity = 50, artistPopularity = 50),
        TrackEntity("2x8evxqUlF0eRabbW2JBJd", "spotify:track:2x8evxqUlF0eRabbW2JBJd", "Fluorescent Adolescent", "Arctic Monkeys", "Favourite Worst Nightmare", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b1f8da74f225fa1225cdface", durationMs = 183893L, popularity = 50, artistPopularity = 50),
        TrackEntity("2JiDi0qAXsPwhPqA2qaKGt", "spotify:track:2JiDi0qAXsPwhPqA2qaKGt", "Bohemian Rhapsody", "Queen", "A Night At The Opera", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273fdab4a163ab9f6db72c952ee", durationMs = 355154L, popularity = 50, artistPopularity = 50),
        TrackEntity("1NHWG8zxSEypSRF3UufrnO", "spotify:track:1NHWG8zxSEypSRF3UufrnO", "Don't Stop Me Now", "Queen", "Jazz", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2736c03b757ece416e014feef5e", durationMs = 209413L, popularity = 50, artistPopularity = 50),
        TrackEntity("40HGoSHn9gELCcJu8TLakq", "spotify:track:40HGoSHn9gELCcJu8TLakq", "Under Pressure - Single Version; 2017 Remaster", "Queen, David Bowie", "A New Career in a New Town (1977 - 1982)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27310498de46a76d45a3b982a5c", durationMs = 248720L, popularity = 50, artistPopularity = 50),
        TrackEntity("1KPuohcXPmJYQfdyg9zKmR", "spotify:track:1KPuohcXPmJYQfdyg9zKmR", "Another One Bites The Dust", "Queen", "The Game", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273a4e6eac83c0fe38bf682f643", durationMs = 214653L, popularity = 50, artistPopularity = 50),
        TrackEntity("7Jh1bpe76CNTCgdgAdBw4Z", "spotify:track:7Jh1bpe76CNTCgdgAdBw4Z", "\"Heroes\" - 2017 Remaster", "David Bowie", "\"Heroes\" (2017 Remaster)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273204f41d52743c6a9efd62985", durationMs = 371413L, popularity = 50, artistPopularity = 50),
        TrackEntity("3ZE3wv8V3w2T2f7nOCjV0N", "spotify:track:3ZE3wv8V3w2T2f7nOCjV0N", "Life on Mars? - 2015 Remaster", "David Bowie", "Hunky Dory (2015 Remaster)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e464904cc3fed2b40fc55120", durationMs = 235986L, popularity = 50, artistPopularity = 50),
        TrackEntity("0pQskrTITgmCMyr85tb9qq", "spotify:track:0pQskrTITgmCMyr85tb9qq", "Starman - 2012 Remaster", "David Bowie", "The Rise and Fall of Ziggy Stardust and the Spiders from Mars (2012 Remaster)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273c41f4e1133b0e6c5fcf58680", durationMs = 254293L, popularity = 50, artistPopularity = 50),
        TrackEntity("0ofHAoxe9vBkTCp2UQIavz", "spotify:track:0ofHAoxe9vBkTCp2UQIavz", "Dreams - 2004 Remaster", "Fleetwood Mac", "Rumours (Super Deluxe)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e52a59a28efa4773dd2bfe1b", durationMs = 257800L, popularity = 50, artistPopularity = 50),
        TrackEntity("4xh7W7tlNMIczFhupCPniY", "spotify:track:4xh7W7tlNMIczFhupCPniY", "Go Your Own Way - 2004 Remaster", "Fleetwood Mac", "Rumours (Super Deluxe)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e52a59a28efa4773dd2bfe1b", durationMs = 223613L, popularity = 50, artistPopularity = 50),
        TrackEntity("7Dm3dV3WPNdTgxoNY7YFnc", "spotify:track:7Dm3dV3WPNdTgxoNY7YFnc", "The Chain - 2004 Remaster", "Fleetwood Mac", "Rumours", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27357df7ce0eac715cf70e519a7", durationMs = 269813L, popularity = 50, artistPopularity = 50),
        TrackEntity("5ihS6UUlyQAfmp48eSkxuQ", "spotify:track:5ihS6UUlyQAfmp48eSkxuQ", "Landslide", "Fleetwood Mac", "Fleetwood Mac", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734fb043195e8d07e72edc7226", durationMs = 199493L, popularity = 50, artistPopularity = 50),
        TrackEntity("6K4t31amVTZDgR3sKmwUJJ", "spotify:track:6K4t31amVTZDgR3sKmwUJJ", "The Less I Know The Better", "Tame Impala", "Currents", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2739e1cfc756886ac782e363d79", durationMs = 216320L, popularity = 50, artistPopularity = 50),
        TrackEntity("0LtOwyZoSNZKJWHqjzADpW", "spotify:track:0LtOwyZoSNZKJWHqjzADpW", "Feels Like We Only Go Backwards", "Tame Impala", "Lonerism", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273370c12f82872c9cfaee80193", durationMs = 192960L, popularity = 50, artistPopularity = 50),
        TrackEntity("37mKgJHxfdyt3B6JODoYuj", "spotify:track:37mKgJHxfdyt3B6JODoYuj", "Feel Good Inc.", "Gorillaz, De La Soul", "Mood Booster", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273aeb31f894ba9768b91a5d9a3", durationMs = 222640L, popularity = 50, artistPopularity = 50),
        TrackEntity("1RKUoGiLEbcXN4GY4spQDx", "spotify:track:1RKUoGiLEbcXN4GY4spQDx", "Clint Eastwood", "Gorillaz, Del The Funky Homosapien", "Gorillaz", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273f6c46838e4425ea96e2562fe", durationMs = 340920L, popularity = 50, artistPopularity = 50),
        TrackEntity("7kzKAuUzOITUauHAhoMoxA", "spotify:track:7kzKAuUzOITUauHAhoMoxA", "Last Nite", "The Strokes", "Is This It", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273a388a3f20d1bf2123249cc79", durationMs = 193506L, popularity = 50, artistPopularity = 50),
        TrackEntity("57Xjny5yNzAcsxnusKmAfA", "spotify:track:57Xjny5yNzAcsxnusKmAfA", "Reptilia", "The Strokes", "Room On Fire", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2730f35726025e0f025da4c688f", durationMs = 219826L, popularity = 50, artistPopularity = 50),
        TrackEntity("56NkIxSZZiMpFP5ZNSxtnT", "spotify:track:56NkIxSZZiMpFP5ZNSxtnT", "Someday", "The Strokes", "Is This It", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273a388a3f20d1bf2123249cc79", durationMs = 183573L, popularity = 50, artistPopularity = 50),
        TrackEntity("68hYdj3GepWm2R202KhJT7", "spotify:track:68hYdj3GepWm2R202KhJT7", "1901", "Phoenix", "Wolfgang Amadeus Phoenix", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27362cbc442278eefdde1a93efd", durationMs = 193106L, popularity = 50, artistPopularity = 50),
        TrackEntity("20I8RduZC2PWMWTDCZuuAN", "spotify:track:20I8RduZC2PWMWTDCZuuAN", "Take Me Out", "Franz Ferdinand", "Franz Ferdinand", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273a5d1c06a8172d4861367953c", durationMs = 237026L, popularity = 50, artistPopularity = 50),
        TrackEntity("3n3Ppam7vgaVa1iaRUc9Lp", "spotify:track:3n3Ppam7vgaVa1iaRUc9Lp", "Mr. Brightside", "The Killers", "Hot Fuss", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73", durationMs = 222200L, popularity = 50, artistPopularity = 50),
        TrackEntity("3twNvmDtFQtAd5gMKedhLD", "spotify:track:3twNvmDtFQtAd5gMKedhLD", "Somebody Told Me", "The Killers", "Hot Fuss", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73", durationMs = 197280L, popularity = 50, artistPopularity = 50),
        TrackEntity("4CeeEOM32jQcH3eN9Q2dGj", "spotify:track:4CeeEOM32jQcH3eN9Q2dGj", "Smells Like Teen Spirit", "Nirvana", "Nevermind (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273fbc71c99f9c1296c56dd51b6", durationMs = 301920L, popularity = 50, artistPopularity = 50),
        TrackEntity("2RsAajgo0g7bMCHxwH3Sk0", "spotify:track:2RsAajgo0g7bMCHxwH3Sk0", "Come As You Are", "Nirvana", "Nevermind (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273fbc71c99f9c1296c56dd51b6", durationMs = 218920L, popularity = 50, artistPopularity = 50),
        TrackEntity("63OQupATfueTdZMWTxW03A", "spotify:track:63OQupATfueTdZMWTxW03A", "Karma Police", "Radiohead", "OK Computer", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273c8b444df094279e70d0ed856", durationMs = 264066L, popularity = 50, artistPopularity = 50),
        TrackEntity("70LcF31zb1H0PyJoS1Sx1r", "spotify:track:70LcF31zb1H0PyJoS1Sx1r", "Creep", "Radiohead", "Pablo Honey", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273ec548c00d3ac2f10be73366d", durationMs = 238640L, popularity = 50, artistPopularity = 50),
        TrackEntity("1L94M3KIu7QluZe63g64rv", "spotify:track:1L94M3KIu7QluZe63g64rv", "Alive", "Pearl Jam", "Ten", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2732d0e5ab5bd2e234fbcffa3e0", durationMs = 340974L, popularity = 50, artistPopularity = 50),
        TrackEntity("5Xak5fmy089t0FYmh3VJiY", "spotify:track:5Xak5fmy089t0FYmh3VJiY", "Black", "Pearl Jam", "Ten", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2732d0e5ab5bd2e234fbcffa3e0", durationMs = 342653L, popularity = 50, artistPopularity = 50),
        TrackEntity("5qqabIl2vWzo9ApSC317sa", "spotify:track:5qqabIl2vWzo9ApSC317sa", "Wonderwall - Remastered", "Oasis", "(What's The Story) Morning Glory? [Remastered]", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27385e5dcc05cc216a10f141480", durationMs = 258773L, popularity = 50, artistPopularity = 50),
        TrackEntity("0UvCh63URrLFcPkKt99hHd", "spotify:track:0UvCh63URrLFcPkKt99hHd", "Don't Look Back in Anger - Remastered", "Oasis", "(What's The Story) Morning Glory? [Remastered]", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27385e5dcc05cc216a10f141480", durationMs = 289560L, popularity = 50, artistPopularity = 50),
        TrackEntity("0Ky1rnbxYY41g4H9plxK6Q", "spotify:track:0Ky1rnbxYY41g4H9plxK6Q", "Boys Don't Cry", "The Cure", "Boys Don't Cry", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27381ba7ba31f0a93041730cfb4", durationMs = 154866L, popularity = 50, artistPopularity = 50),
        TrackEntity("4QlzkaRHtU8gAdwqjWmO8n", "spotify:track:4QlzkaRHtU8gAdwqjWmO8n", "Friday I'm In Love", "The Cure", "Wish", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2739c72b249fcaa04d074c1dfcd", durationMs = 215160L, popularity = 50, artistPopularity = 50),
        TrackEntity("0WQiDwKJclirSYG9v5tayI", "spotify:track:0WQiDwKJclirSYG9v5tayI", "There Is a Light That Never Goes Out - 2011 Remaster", "The Smiths", "The Queen Is Dead", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2736236778a208a15eb71079601", durationMs = 244586L, popularity = 50, artistPopularity = 50),
        TrackEntity("6hHc7Pks7wtBIW8Z6A0iFq", "spotify:track:6hHc7Pks7wtBIW8Z6A0iFq", "Blue Monday", "New Order", "Substance", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273408739ba1ad5bccbfeda5ae1", durationMs = 449160L, popularity = 50, artistPopularity = 50),
        TrackEntity("0yp3TvJNlG50Q4tAHWNCRm", "spotify:track:0yp3TvJNlG50Q4tAHWNCRm", "Enjoy the Silence", "Depeche Mode", "Violator | The 12\" Singles", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273972ac79d249efed1c7b2c8c1", durationMs = 257630L, popularity = 50, artistPopularity = 50),
        TrackEntity("2YiCMmONQcoMPX2bV1LxE0", "spotify:track:2YiCMmONQcoMPX2bV1LxE0", "Everybody Wants To Rule The World", "Tears For Fears", "Songs From The Big Chair", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2735e12c35cb4c848a927ef4b69", durationMs = 251480L, popularity = 50, artistPopularity = 50),
        TrackEntity("2WfaOiMkCvy7F5fcp2zZ8L", "spotify:track:2WfaOiMkCvy7F5fcp2zZ8L", "Take on Me", "a-ha", "Hunting High and Low", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273e8dd4db47e7177c63b0b7d53", durationMs = 225280L, popularity = 50, artistPopularity = 50),
        TrackEntity("6mFkJmJqdDVQ1REhVfGgd1", "spotify:track:6mFkJmJqdDVQ1REhVfGgd1", "Wish You Were Here", "Pink Floyd", "Wish You Were Here", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273828e52cfb7bf22869349799e", durationMs = 338466L, popularity = 50, artistPopularity = 50),
        TrackEntity("5HNCy40Ni5BZJFw1TKzRsC", "spotify:track:5HNCy40Ni5BZJFw1TKzRsC", "Comfortably Numb", "Pink Floyd", "The Wall", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b694e89ba937dd2631ff584c", durationMs = 382280L, popularity = 50, artistPopularity = 50),
        TrackEntity("2EqlS6tkEnglzr7tkKAAYD", "spotify:track:2EqlS6tkEnglzr7tkKAAYD", "Come Together - Remastered 2009", "The Beatles", "Abbey Road (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273dc30583ba717007b00cceb25", durationMs = 259946L, popularity = 50, artistPopularity = 50),
        TrackEntity("6dGnYIeXmHdcikdzNNDMm2", "spotify:track:6dGnYIeXmHdcikdzNNDMm2", "Here Comes The Sun - Remastered 2009", "The Beatles", "Abbey Road (Remastered)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273dc30583ba717007b00cceb25", durationMs = 185733L, popularity = 50, artistPopularity = 50),
        TrackEntity("1RJeiAIwR9pZBgJA8ndZLL", "spotify:track:1RJeiAIwR9pZBgJA8ndZLL", "Paint It, Black - Mono", "The Rolling Stones", "The Rolling Stones In Mono (Remastered 2016)", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738d1570a03b9354518f0b618b", durationMs = 204480L, popularity = 50, artistPopularity = 50),
        TrackEntity("08mG3Y1vljYA6bvDt4Wqkj", "spotify:track:08mG3Y1vljYA6bvDt4Wqkj", "Back In Black", "AC/DC", "Back In Black", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273ff191d7fbdb5a13eaf84132b", durationMs = 256000L, popularity = 50, artistPopularity = 50),
        TrackEntity("2zYzyRzz6pRmhPzyfMEC8s", "spotify:track:2zYzyRzz6pRmhPzyfMEC8s", "Highway to Hell", "AC/DC", "Highway to Hell", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27343058ea096fa35ac33c43587", durationMs = 208110L, popularity = 50, artistPopularity = 50),
        TrackEntity("7snQQk1zcKl8gZ92AnueZW", "spotify:track:7snQQk1zcKl8gZ92AnueZW", "Sweet Child O' Mine", "Guns N' Roses", "Appetite For Destruction", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27321ebf49b3292c3f0f575f0f5", durationMs = 356066L, popularity = 50, artistPopularity = 50),
        TrackEntity("4U3Ggqyv2XgnS1u82HOGQX", "spotify:track:4U3Ggqyv2XgnS1u82HOGQX", "Every Breath You Take", "The Police", "The Very Best Of Sting And The Police", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273cf390065f5a3336f12143e16", durationMs = 251333L, popularity = 50, artistPopularity = 50),
        TrackEntity("7J1uxwnxfQLu4APicE5Rnj", "spotify:track:7J1uxwnxfQLu4APicE5Rnj", "Billie Jean", "Michael Jackson", "Thriller", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27332a7d87248d1b75463483df5", durationMs = 293802L, popularity = 50, artistPopularity = 50),
        TrackEntity("1OOtq8tRnDM8kG2gqUPjAj", "spotify:track:1OOtq8tRnDM8kG2gqUPjAj", "Beat It", "Michael Jackson", "Thriller 25 Super Deluxe Edition", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2734121faee8df82c526cbab2be", durationMs = 258040L, popularity = 50, artistPopularity = 50),
        TrackEntity("1uvyZBs4IZYRebHIB1747m", "spotify:track:1uvyZBs4IZYRebHIB1747m", "Purple Rain", "Prince", "Purple Rain", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738a2ce3f148f57584269c3782", durationMs = 521866L, popularity = 50, artistPopularity = 50),
        TrackEntity("1ujxjsoNvh4XgS2fUNwkZ2", "spotify:track:1ujxjsoNvh4XgS2fUNwkZ2", "Space Song", "Beach House", "Depression Cherry", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27302859310b61e59756abb90ad", durationMs = 320466L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "track"),
        TrackEntity("1Snko28yJOihcRVXs9dAo9", "spotify:track:1Snko28yJOihcRVXs9dAo9", "Chamber Of Reflection", "Mac DeMarco", "Salad Days", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2738b221f2ccf777ae0d4b0db50", durationMs = 231723L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "track"),
        TrackEntity("3z6CZiS2I89YQ2N0FMtGlG", "spotify:track:3z6CZiS2I89YQ2N0FMtGlG", "El Mundo Extraño", "El Mató a un Policía Motorizado", "La Sintesis O'Konor", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b952b049193b56a4dc5c449b", durationMs = 238311L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "track"),
        TrackEntity("4zdEcOj9IEAE1cWG7zzYxB", "spotify:track:4zdEcOj9IEAE1cWG7zzYxB", "A 1200 Km", "Las Ligas Menores", "Las Ligas Menores", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27304364593fe145a2068fbfa64", durationMs = 229842L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "both"),
        TrackEntity("1tyI6Sq6oBLsMmIgvBfQrI", "spotify:track:1tyI6Sq6oBLsMmIgvBfQrI", "Vámonos De Viaje", "Bandalos Chinos", "BACH", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b2736287160ee04a0f09669354d8", durationMs = 236160L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "track"),
        TrackEntity("0fQbLONJQqFSePMz1I4UNT", "spotify:track:0fQbLONJQqFSePMz1I4UNT", "Cabildo y Juramento", "Conociendo Rusia", "Cabildo y Juramento", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b760d33bf5c940e64dccf56e", durationMs = 203826L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "track"),
        TrackEntity("3WRQUvzRvBDr4AxMWhXc5E", "spotify:track:3WRQUvzRvBDr4AxMWhXc5E", "Sunset Lover", "Petit Biscuit", "Presence", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b27344bb39545f5d7176080d17ae", durationMs = 238906L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "both"),
        TrackEntity("2aDk1KkyB7ieSwwEDXCHJg", "spotify:track:2aDk1KkyB7ieSwwEDXCHJg", "The Mind Electric", "Miracle Musical", "Hawaii: Part II", albumArtUrl = "https://i.scdn.co/image/ab67616d0000b273b3d0bf02a5fb2f676c748432", durationMs = 373359L, popularity = 40, artistPopularity = 40, isHiddenGem = true, hiddenGemType = "both"),
    )

    fun setSubTab(index: Int) {
        _uiState.update { it.copy(activeSubTab = index) }
    }

    private var searchJob: Job? = null
    private var artistJob: Job? = null
    private var trackJob: Job? = null
    private var catalogQueryJob: Job? = null

    private fun normalize(text: String): String {
        return SearchUtils.normalize(text)
    }

    private suspend fun getValidAccessToken(): String? {
        val cloudToken = cloudService?.getValidToken(accessToken)
        if (!cloudToken.isNullOrBlank()) {
            accessToken = cloudToken
            return cloudToken
        }
        val refreshToken = withContext(Dispatchers.IO) { repository.getSetting("spotify_refresh_token") }
        val expiresAtStr = withContext(Dispatchers.IO) { repository.getSetting("spotify_token_expires_at") }
        val expiresAt = expiresAtStr?.toLongOrNull() ?: 0L
        var token = withContext(Dispatchers.IO) { repository.getSetting("spotify_access_token") }
        if (token.isNullOrBlank()) {
            token = accessToken
        }

        if (!refreshToken.isNullOrBlank() && (expiresAt == 0L || System.currentTimeMillis() >= (expiresAt - 60_000L))) {
            val refreshResult = withContext(Dispatchers.IO) { SpotifyPkceAuthManager.refreshAccessToken(refreshToken) }
            if (refreshResult.isSuccess) {
                val (newToken, newRefresh) = refreshResult.getOrThrow()
                token = newToken
                accessToken = newToken
                withContext(Dispatchers.IO) {
                    repository.setSetting("spotify_access_token", newToken)
                    if (!newRefresh.isNullOrBlank()) {
                        repository.setSetting("spotify_refresh_token", newRefresh)
                    }
                    val newExpiresAt = System.currentTimeMillis() + 3600_000L
                    repository.setSetting("spotify_token_expires_at", newExpiresAt.toString())
                }
            }
        }
        return token
    }

    fun setCatalogSearchType(type: CatalogSearchType) {
        _uiState.update { it.copy(catalogSearchType = type) }
        executeCatalogSearch()
    }

    fun setCatalogOperator(operator: SearchLogicOperator) {
        _uiState.update { it.copy(catalogOperator = operator) }
        executeCatalogSearch()
    }

    fun addCatalogModifier(term: String, modifier: ChipModifier) {
        val trimmed = term.trim()
        if (trimmed.isBlank()) return
        _uiState.update { state ->
            val updated = state.catalogModifiers.toMutableSet()
            updated.add(ModifierChip(trimmed, modifier))
            state.copy(catalogModifiers = updated, searchQuery = "")
        }
        executeCatalogSearch()
    }

    fun removeCatalogModifier(chip: ModifierChip) {
        _uiState.update { state ->
            val updated = state.catalogModifiers.toMutableSet()
            updated.remove(chip)
            state.copy(catalogModifiers = updated)
        }
        executeCatalogSearch()
    }

    fun removeCatalogModifier(term: String) {
        _uiState.update { state ->
            val updated = state.catalogModifiers.toMutableSet()
            updated.removeIf { it.term.equals(term, ignoreCase = true) }
            state.copy(catalogModifiers = updated)
        }
        executeCatalogSearch()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            catalogQueryJob?.cancel()
            _uiState.update { it.copy(suggestedCatalogQueries = emptyList()) }
        } else {
            catalogQueryJob?.cancel()
            catalogQueryJob = viewModelScope.launch(Dispatchers.IO) {
                delay(60)
                val localTracks = repository.getAllTracksSync()
                val artistMatches = (allKnownArtists + localTracks.flatMap { SearchUtils.splitArtists(it.artist) })
                    .distinct()
                    .filter { SearchUtils.fuzzyMatches(trimmed, it) }
                    .sortedWith(
                        compareBy<String> { SearchUtils.matchScore(trimmed, it) }
                            .thenBy { it.lowercase() }
                    )
                    .take(4)
                val trackMatches = (localTracks.map { it.title } + masterCatalog.map { it.title })
                    .distinct()
                    .filter { SearchUtils.fuzzyMatches(trimmed, it) }
                    .sortedWith(
                        compareBy<String> { SearchUtils.matchScore(trimmed, it) }
                            .thenBy { it.lowercase() }
                    )
                    .take(4)
                val suggestions = (artistMatches + trackMatches).distinct().take(6)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(suggestedCatalogQueries = suggestions) }
                }
            }
        }
        executeCatalogSearch()
    }

    private fun executeCatalogSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(200)
            val state = _uiState.value
            val query = state.searchQuery.trim()
            val modifiers = state.catalogModifiers

            if (query.isBlank() && modifiers.isEmpty()) {
                _uiState.update { it.copy(searchResults = emptyList(), isSearchingCatalog = false) }
                return@launch
            }

            _uiState.update { it.copy(isSearchingCatalog = true) }

            val candidates = mutableListOf<TrackEntity>()
            val seenIds = mutableSetOf<String>()

            // 1. Search Spotify Cloud Service if token and service are available
            val token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null && query.isNotBlank()) {
                try {
                    val spotifyQuery = when (state.catalogSearchType) {
                        CatalogSearchType.ALL -> query
                        CatalogSearchType.ARTIST -> "artist:$query"
                        CatalogSearchType.TRACK -> "track:$query"
                        CatalogSearchType.LYRICS -> query
                    }
                    val queryVariants = mutableListOf(spotifyQuery)
                    if (state.catalogSearchType == CatalogSearchType.ARTIST) {
                        queryVariants.add("artist:\"$query\"")
                        queryVariants.add(query)
                    }
                    for (qVar in queryVariants.distinct()) {
                        for (offset in listOf(0, 10, 20)) {
                            val cloudResults = cloudService.searchTracks(token, qVar, limit = 10, offset = offset)
                            for (t in cloudResults) {
                                if (seenIds.add(t.id)) {
                                    candidates.add(t)
                                }
                            }
                            if (cloudResults.size < 10) break
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("DiscoverVM", "Cloud catalog search error", e)
                }
            } else if (!token.isNullOrBlank() && cloudService != null && query.isBlank() && modifiers.isNotEmpty()) {
                try {
                    for (mod in modifiers) {
                        if (mod.modifier == ChipModifier.INCLUDE) {
                            for (offset in listOf(0, 10, 20)) {
                                val cloudResults = cloudService.searchTracks(token, mod.term, limit = 10, offset = offset)
                                for (t in cloudResults) {
                                    if (seenIds.add(t.id)) {
                                        candidates.add(t)
                                    }
                                }
                                if (cloudResults.size < 10) break
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("DiscoverVM", "Cloud modifier search error", e)
                }
            }

            // 2. Query Local Library & Master Catalog
            val localTracks = withContext(Dispatchers.IO) { repository.getAllTracksSync() }
            for (t in (localTracks + masterCatalog)) {
                if (seenIds.add(t.id)) {
                    candidates.add(t)
                }
            }

            // 3. Filter candidates based on catalogSearchType, catalogOperator, and catalogModifiers
            val tokens = SearchUtils.normalize(query).split("\\s+".toRegex()).filter { it.isNotBlank() }

            val filtered = candidates.filter { track ->
                if (tokens.isNotEmpty()) {
                    val targetField = when (state.catalogSearchType) {
                        CatalogSearchType.ALL -> "${track.title} ${track.artist} ${track.album}"
                        CatalogSearchType.ARTIST -> track.artist
                        CatalogSearchType.TRACK -> track.title
                        CatalogSearchType.LYRICS -> "${track.title} ${track.artist} ${track.album}"
                    }
                    val matches = when (state.catalogOperator) {
                        SearchLogicOperator.AND -> tokens.all { token -> SearchUtils.fuzzyMatches(token, targetField) }
                        SearchLogicOperator.OR -> tokens.any { token -> SearchUtils.fuzzyMatches(token, targetField) }
                    }
                    if (!matches) return@filter false
                }

                if (modifiers.isNotEmpty()) {
                    val fullTrackText = "${track.title} ${track.artist} ${track.album}"
                    for (mod in modifiers) {
                        val containsTerm = SearchUtils.fuzzyMatches(mod.term, fullTrackText)
                        if (mod.modifier == ChipModifier.INCLUDE && !containsTerm) {
                            return@filter false
                        }
                        if (mod.modifier == ChipModifier.EXCLUDE && containsTerm) {
                            return@filter false
                        }
                    }
                }
                true
            }.sortedWith(
                compareBy<TrackEntity> { track ->
                    minOf(
                        SearchUtils.matchScore(query, track.title),
                        SearchUtils.matchScore(query, track.artist)
                    )
                }.thenBy { it.title.lowercase() }
            )

            _uiState.update { it.copy(searchResults = filtered, isSearchingCatalog = false) }
        }
    }

    fun setArtistInputText(text: String) {
        _uiState.update { it.copy(artistInputText = text) }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            artistJob?.cancel()
            _uiState.update { it.copy(suggestedArtists = emptyList()) }
            return
        }
        artistJob?.cancel()
        artistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(60)
            val localArtists = repository.getAllTracksSync()
                .flatMap { SearchUtils.splitArtists(it.artist) }
                .filter { SearchUtils.fuzzyMatches(trimmed, it) }
                .distinct()
            val catalogArtists = allKnownArtists
                .flatMap { SearchUtils.splitArtists(it) }
                .filter { SearchUtils.fuzzyMatches(trimmed, it) }
                .distinct()
            val combined = (catalogArtists + localArtists)
                .distinct()
                .sortedWith(
                    compareBy<String> { SearchUtils.matchScore(trimmed, it) }
                        .thenBy { it.lowercase() }
                )
                .take(10)
            withContext(Dispatchers.Main) {
                android.util.Log.d("DiscoverVM", "setArtistInputText: trimmed='$trimmed', found=${combined.size}: $combined")
                _uiState.update { it.copy(suggestedArtists = combined) }
            }
        }
    }

    fun addArtistModifier(artist: String, modifier: ChipModifier) {
        val trimmed = artist.trim()
        if (trimmed.isBlank()) return
        _uiState.update { state ->
            val updated = state.artistModifiers.toMutableSet()
            updated.add(ModifierChip(trimmed, modifier))
            state.copy(artistModifiers = updated, artistInputText = "", suggestedArtists = emptyList())
        }
    }

    fun removeArtistModifier(chip: ModifierChip) {
        _uiState.update { state ->
            val updated = state.artistModifiers.toMutableSet()
            updated.remove(chip)
            state.copy(artistModifiers = updated)
        }
    }

    fun removeArtistModifier(artist: String) {
        _uiState.update { state ->
            val updated = state.artistModifiers.toMutableSet()
            updated.removeIf { it.term.equals(artist, ignoreCase = true) }
            state.copy(artistModifiers = updated)
        }
    }

    fun setGenreInputText(text: String) {
        val trimmed = text.trim()
        val suggestions = if (trimmed.isNotEmpty()) {
            GenreCatalog.searchGenres(trimmed).take(10).map { it.name }
        } else {
            emptyList()
        }
        _uiState.update { it.copy(genreInputText = text, suggestedGenres = suggestions) }
    }

    fun setSelectedGenreCategory(category: String) {
        _uiState.update { it.copy(selectedGenreCategory = category) }
    }

    fun addGenreModifier(genre: String, modifier: ChipModifier) {
        val trimmed = genre.trim()
        if (trimmed.isBlank()) return
        _uiState.update { state ->
            val updated = state.genreModifiers.toMutableSet()
            updated.add(ModifierChip(trimmed, modifier))
            state.copy(genreModifiers = updated, genreInputText = "", suggestedGenres = emptyList())
        }
    }

    fun removeGenreModifier(chip: ModifierChip) {
        _uiState.update { state ->
            val updated = state.genreModifiers.toMutableSet()
            updated.remove(chip)
            state.copy(genreModifiers = updated)
        }
    }

    fun removeGenreModifier(genre: String) {
        _uiState.update { state ->
            val updated = state.genreModifiers.toMutableSet()
            updated.removeIf { it.term.equals(genre, ignoreCase = true) }
            state.copy(genreModifiers = updated)
        }
    }

    // --- Song Seed Methods (Similar to Song) ---

    fun setTrackInputText(text: String) {
        _uiState.update { it.copy(trackInputText = text) }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            trackJob?.cancel()
            _uiState.update { it.copy(suggestedTracks = emptyList()) }
            return
        }
        trackJob?.cancel()
        trackJob = viewModelScope.launch(Dispatchers.IO) {
            delay(60)
            val localMatches = repository.getAllTracksSync().filter {
                SearchUtils.fuzzyMatches(trimmed, it.title) || SearchUtils.fuzzyMatches(trimmed, it.artist)
            }
            val catalogMatches = masterCatalog.filter {
                SearchUtils.fuzzyMatches(trimmed, it.title) || SearchUtils.fuzzyMatches(trimmed, it.artist)
            }
            val combined = (localMatches + catalogMatches)
                .distinctBy { "${SearchUtils.normalize(it.title)}-${SearchUtils.normalize(it.artist)}" }
                .sortedWith(
                    compareBy<TrackEntity> { track ->
                        minOf(
                            SearchUtils.matchScore(trimmed, track.title),
                            SearchUtils.matchScore(trimmed, track.artist)
                        )
                    }.thenBy { it.title.lowercase() }
                )
                .take(8)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(suggestedTracks = combined) }
            }
        }
    }

    fun addTrackModifier(trackName: String, modifier: ChipModifier) {
        val trimmed = trackName.trim()
        if (trimmed.isBlank()) return
        _uiState.update { state ->
            val updated = state.trackModifiers.toMutableSet()
            updated.add(ModifierChip(trimmed, modifier))
            state.copy(trackModifiers = updated, trackInputText = "", suggestedTracks = emptyList())
        }
    }

    fun removeTrackModifier(chip: ModifierChip) {
        _uiState.update { state ->
            val updated = state.trackModifiers.toMutableSet()
            updated.remove(chip)
            state.copy(trackModifiers = updated)
        }
    }

    fun removeTrackModifier(trackName: String) {
        _uiState.update { state ->
            val updated = state.trackModifiers.toMutableSet()
            updated.removeIf { it.term.equals(trackName, ignoreCase = true) }
            state.copy(trackModifiers = updated)
        }
    }

    /**
     * Add-Only saving to Liked Songs from Discover results.
     * Enforces strict safety: never unlikes.
     */
    fun saveTrackToLiked(track: TrackEntity, onFeedback: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            if (repository.isTrackInLiked(track.id)) {
                onFeedback?.invoke("Ya está en tus Canciones que te gustan")
                return@launch
            }
            val token = getValidAccessToken()
            if (!token.isNullOrBlank() && cloudService != null) {
                cloudService.saveTrackToLiked(token, track.id)
            }
            repository.saveTrackToLiked(track)
            onFeedback?.invoke("¡Agregada a Canciones que te gustan!")
        }
    }

    fun toggleGenreModifier(genre: String) {
        _uiState.update { state ->
            val updated = state.genreModifiers.toMutableSet()
            val hasInclude = updated.any { it.term.equals(genre, ignoreCase = true) && it.modifier == ChipModifier.INCLUDE }
            val hasExclude = updated.any { it.term.equals(genre, ignoreCase = true) && it.modifier == ChipModifier.EXCLUDE }
            if (!hasInclude && !hasExclude) {
                updated.add(ModifierChip(genre, ChipModifier.INCLUDE))
            } else if (hasInclude && !hasExclude) {
                updated.removeIf { it.term.equals(genre, ignoreCase = true) }
                updated.add(ModifierChip(genre, ChipModifier.EXCLUDE))
            } else {
                updated.removeIf { it.term.equals(genre, ignoreCase = true) }
            }
            state.copy(genreModifiers = updated)
        }
    }

    fun toggleDecade(decade: String) {
        _uiState.update { state ->
            val updated = state.selectedDecades.toMutableSet()
            if (updated.contains(decade)) {
                updated.remove(decade)
            } else {
                updated.add(decade)
            }
            state.copy(selectedDecades = updated)
        }
    }

    private var accessToken: String? = null

    fun setAccessToken(token: String) {
        accessToken = token
    }

    fun setExcludeLibrary(exclude: Boolean) {
        _uiState.update { it.copy(excludeLibrarySongs = exclude) }
    }

    fun setRecentlyHeardFilter(filter: RecentlyHeardFilter) {
        _uiState.update { it.copy(recentlyHeardFilter = filter) }
    }

    fun setTargetCount(count: Int) {
        _uiState.update { it.copy(targetCount = count) }
    }

    fun setLowPopularityOnly(enabled: Boolean) {
        _uiState.update { it.copy(lowPopularityOnly = enabled) }
    }

    fun setHiddenGemTarget(target: String) {
        _uiState.update { it.copy(hiddenGemTarget = target) }
    }

    fun dismissInfoBanner() {
        _uiState.update { it.copy(infoBannerMessage = null) }
    }

    fun generateDiscoveryMix() {
        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingMix = true, infoBannerMessage = null) }

            val state = _uiState.value
            val targetCount = state.targetCount
            val token = getValidAccessToken()

            val includedArtists = state.artistModifiers
                .filter { it.modifier == ChipModifier.INCLUDE }
                .map { it.term }
            val excludedArtists = state.artistModifiers
                .filter { it.modifier == ChipModifier.EXCLUDE }
                .map { it.term.lowercase().trim() }

            val includedGenres = state.genreModifiers
                .filter { it.modifier == ChipModifier.INCLUDE }
                .map { it.term }
            val excludedGenres = state.genreModifiers
                .filter { it.modifier == ChipModifier.EXCLUDE }
                .map { it.term.lowercase().trim() }

            val includedTracks = state.trackModifiers
                .filter { it.modifier == ChipModifier.INCLUDE }
                .map { it.term }
            val excludedTracks = state.trackModifiers
                .filter { it.modifier == ChipModifier.EXCLUDE }
                .map { it.term.lowercase().trim() }

            val hasPositiveSeeds = includedArtists.isNotEmpty() || includedGenres.isNotEmpty() ||
                    includedTracks.isNotEmpty() || state.selectedDecades.isNotEmpty()

            // Pre-load library tracks and recently heard exclusion sets
            val libraryTracks = withContext(Dispatchers.IO) { repository.getAllTracksSync() }
            val libraryTrackIds = if (state.excludeLibrarySongs) {
                libraryTracks.map { it.id.lowercase().trim() }.toSet()
            } else emptySet()
            val libraryKeys = if (state.excludeLibrarySongs) {
                libraryTracks.map { "${it.title.lowercase().trim()} - ${it.artist.lowercase().trim()}" }.toSet()
            } else emptySet()

            val recentTrackIds: Set<String> = if (state.recentlyHeardFilter != RecentlyHeardFilter.NONE) {
                val localRecentIds = withContext(Dispatchers.IO) {
                    repository.getRecentlyPlayedTrackIds(days = state.recentlyHeardFilter.days)
                }.toSet()
                val cloudRecentIds = if (!token.isNullOrBlank() && cloudService != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            cloudService.fetchRecentlyPlayedTrackIds(token)
                        } catch (_: Exception) {
                            emptySet()
                        }
                    }
                } else emptySet()
                localRecentIds + cloudRecentIds
            } else emptySet()

            fun isAllowedCandidate(track: TrackEntity): Boolean {
                // Strictly enforce valid Spotify track format: must start with spotify:track: and have valid ID length
                if (!track.uri.startsWith("spotify:track:") || track.id.length < 15) return false

                // Strictly enforce Library Exclusion: zero library tracks admitted when enabled
                if (state.excludeLibrarySongs) {
                    val id = track.id.lowercase().trim()
                    val key = "${track.title.lowercase().trim()} - ${track.artist.lowercase().trim()}"
                    if (libraryTrackIds.contains(id) || libraryKeys.contains(key)) return false
                }

                // Strictly enforce Recently Heard Exclusion
                if (recentTrackIds.contains(track.id)) return false

                // Hard Prune: EXCLUDED artists (- NO)
                if (excludedArtists.isNotEmpty()) {
                    if (excludedArtists.any { SearchUtils.fuzzyMatches(it, track.artist) || track.artist.lowercase().contains(it) }) return false
                }

                // Hard Prune: EXCLUDED genres (- NO)
                if (excludedGenres.isNotEmpty()) {
                    if (excludedGenres.any { SearchUtils.fuzzyMatches(it, track.album) || track.album.lowercase().contains(it) }) return false
                }

                // Hard Prune: EXCLUDED song seeds (- NO)
                if (excludedTracks.isNotEmpty()) {
                    if (excludedTracks.any { SearchUtils.fuzzyMatches(it, track.title) || track.title.lowercase().contains(it) }) return false
                }

                return true
            }

            val candidates = mutableListOf<TrackEntity>()
            val seenKeys = mutableSetOf<String>()

            fun addCandidate(track: TrackEntity): Boolean {
                if (!isAllowedCandidate(track)) return false
                val key = "${track.title.lowercase().trim()} - ${track.artist.lowercase().trim()}"
                if (seenKeys.add(key)) {
                    candidates.add(track)
                    return true
                }
                return false
            }

            // A. Local Library Seeding (ONLY when NOT excluding library songs)
            if (!state.excludeLibrarySongs && hasPositiveSeeds) {
                for (t in libraryTracks) {
                    var matchesSeed = false
                    for (art in includedArtists) {
                        if (SearchUtils.fuzzyMatches(art, t.artist) || SearchUtils.splitArtists(t.artist).any { SearchUtils.fuzzyMatches(art, it) }) {
                            matchesSeed = true
                            break
                        }
                    }
                    if (!matchesSeed) {
                        for (tr in includedTracks) {
                            if (SearchUtils.fuzzyMatches(tr, t.title)) {
                                matchesSeed = true
                                break
                            }
                        }
                    }
                    if (!matchesSeed) {
                        for (gen in includedGenres) {
                            if (SearchUtils.fuzzyMatches(gen, t.album)) {
                                matchesSeed = true
                                break
                            }
                        }
                    }
                    if (matchesSeed) {
                        addCandidate(t)
                    }
                }
            }

            // B. Cloud Seeding with Iterative / Recursive Orbit Expansion (Depth 0 -> 1 -> 2)
            val visitedArtists = mutableSetOf<String>()
            for (a in excludedArtists) visitedArtists.add(a.lowercase().trim())

            withContext(Dispatchers.IO) {
                if (!token.isNullOrBlank() && cloudService != null) {
                    coroutineScope {
                        // 1. Initial Seeds Harvesting (Depth 0)
                        val initialJobs = mutableListOf<kotlinx.coroutines.Deferred<List<TrackEntity>>>()

                        // Artist Seeds (Depth 0: direct catalog tracks)
                        for (artist in includedArtists) {
                            val norm = artist.lowercase().trim()
                            if (visitedArtists.contains(norm)) continue
                            visitedArtists.add(norm)

                            for (offset in listOf(0, 10, 20, 30, 40)) {
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, "artist:\"$artist\"", limit = 10, offset = offset)
                                    } catch (_: Exception) { emptyList() }
                                })
                            }
                            for (offset in listOf(0, 10)) {
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, "artist:$artist", limit = 10, offset = offset)
                                    } catch (_: Exception) { emptyList() }
                                })
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, artist, limit = 10, offset = offset)
                                    } catch (_: Exception) { emptyList() }
                                })
                            }
                        }

                        // Genre Seeds
                        for (genre in includedGenres) {
                            for (offset in listOf(0, 10, 20)) {
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, "genre:\"$genre\"", limit = 10, offset = offset)
                                    } catch (_: Exception) { emptyList() }
                                })
                            }
                            for (offset in listOf(0, 10)) {
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, genre, limit = 10, offset = offset)
                                    } catch (_: Exception) { emptyList() }
                                })
                            }
                        }

                        // Song Seeds
                        for (trackSeed in includedTracks) {
                            for (offset in listOf(0, 10)) {
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, trackSeed, limit = 10, offset = offset)
                                    } catch (_: Exception) { emptyList() }
                                })
                            }
                        }

                        // Decade Seeds
                        val decadeQueryMap = mapOf(
                            "60s" to "year:1960-1969",
                            "70s" to "year:1970-1979",
                            "80s" to "year:1980-1989",
                            "90s" to "year:1990-1999",
                            "00s" to "year:2000-2009",
                            "10s" to "year:2010-2019"
                        )
                        for (dec in state.selectedDecades) {
                            val q = decadeQueryMap[dec] ?: "year:1980-1999"
                            for (page in 0..1) {
                                initialJobs.add(async {
                                    try {
                                        cloudService.searchTracks(token, q, limit = 10, offset = page * 10)
                                    } catch (_: Exception) { emptyList() }
                                })
                            }
                        }

                        // If no positive filters specified, explore broad discovery
                        if (!hasPositiveSeeds) {
                            val discoveryQueries = listOf(
                                "rock", "indie", "alternative", "synth-pop",
                                "latin rock", "classic rock", "pop", "disco", "year:1990-2023"
                            ).shuffled().take(6)
                            for (q in discoveryQueries) {
                                val randomOffset = (0..3).random() * 10
                                for (page in 0..1) {
                                    initialJobs.add(async {
                                        try {
                                            cloudService.searchTracks(token, q, limit = 10, offset = randomOffset + (page * 10))
                                        } catch (_: Exception) { emptyList() }
                                    })
                                }
                            }
                        }

                        // Await initial batch and add valid candidates
                        val initialTracks = initialJobs.awaitAll().flatten()
                        initialTracks.forEach { addCandidate(it) }

                        // 2. Depth 1 Orbit Expansion (1st degree related artists)
                        // Target candidate buffer to ensure enough variety for capping and anti-clumping
                        val targetBuffer = targetCount * 2
                        val seedArtists = if (includedArtists.isNotEmpty()) {
                            includedArtists
                        } else {
                            candidates.map { it.artist }.distinct().take(6)
                        }

                        val depth1Artists = mutableListOf<String>()
                        if (candidates.size < targetBuffer && seedArtists.isNotEmpty()) {
                            for (seed in seedArtists) {
                                try {
                                    val rel = cloudService.fetchRelatedArtists(token, seed).toMutableList()
                                    if (rel.size < 3) {
                                        val more = cloudService.searchArtists(token, seed, limit = 6)
                                        for (m in more) {
                                            if (!rel.any { it.equals(m, ignoreCase = true) } && !m.equals(seed, ignoreCase = true)) {
                                                rel.add(m)
                                            }
                                        }
                                    }
                                    for (r in rel) {
                                        val rNorm = r.lowercase().trim()
                                        if (visitedArtists.add(rNorm) && !excludedArtists.any { it.equals(rNorm, ignoreCase = true) || SearchUtils.fuzzyMatches(it, rNorm) }) {
                                            depth1Artists.add(r)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            android.util.Log.d("DiscoverVM", "Discovered ${depth1Artists.size} Depth-1 related artists: $depth1Artists")

                            val d1Jobs = mutableListOf<kotlinx.coroutines.Deferred<List<TrackEntity>>>()
                            for (rel in depth1Artists.take(12)) {
                                for (offset in listOf(0, 10, 20)) {
                                    d1Jobs.add(async {
                                        try {
                                            cloudService.searchTracks(token, "artist:\"$rel\"", limit = 10, offset = offset)
                                        } catch (_: Exception) { emptyList() }
                                    })
                                }
                            }
                            val d1Tracks = d1Jobs.awaitAll().flatten()
                            d1Tracks.forEach { addCandidate(it) }
                        }

                        // 3. Depth 2 Orbit Expansion (Peers of peers: 2nd degree related artists)
                        // If candidates are still below targetBuffer and we have depth1 artists
                        if (candidates.size < targetBuffer && depth1Artists.isNotEmpty()) {
                            val depth2Artists = mutableListOf<String>()
                            for (d1 in depth1Artists.take(8)) {
                                if (candidates.size >= targetBuffer) break
                                try {
                                    val rel2 = cloudService.fetchRelatedArtists(token, d1)
                                    for (r2 in rel2) {
                                        val r2Norm = r2.lowercase().trim()
                                        if (visitedArtists.add(r2Norm) && !excludedArtists.any { it.equals(r2Norm, ignoreCase = true) || SearchUtils.fuzzyMatches(it, r2Norm) }) {
                                            depth2Artists.add(r2)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            android.util.Log.d("DiscoverVM", "Discovered ${depth2Artists.size} Depth-2 related artists: $depth2Artists")

                            if (depth2Artists.isNotEmpty()) {
                                val d2Jobs = mutableListOf<kotlinx.coroutines.Deferred<List<TrackEntity>>>()
                                for (rel2 in depth2Artists.take(15)) {
                                    for (offset in listOf(0, 10, 20)) {
                                        d2Jobs.add(async {
                                            try {
                                                cloudService.searchTracks(token, "artist:\"$rel2\"", limit = 10, offset = offset)
                                            } catch (_: Exception) { emptyList() }
                                        })
                                    }
                                }
                                val d2Tracks = d2Jobs.awaitAll().flatten()
                                d2Tracks.forEach { addCandidate(it) }
                            }
                        }
                    }
                }
            }

            // 4. Master catalog additions (all pass through addCandidate which respects library exclusion)
            if (!hasPositiveSeeds) {
                masterCatalog.forEach { addCandidate(it) }
            } else {
                for (catTrack in masterCatalog) {
                    val matchesArtist = includedArtists.any { SearchUtils.fuzzyMatches(it, catTrack.artist) }
                    val matchesGenre = includedGenres.any { SearchUtils.fuzzyMatches(it, catTrack.album) }
                    if (matchesArtist || matchesGenre) {
                        addCandidate(catTrack)
                    }
                }
            }

            android.util.Log.d("DiscoverVM", "Total filtered non-library candidates harvested: ${candidates.size}")

            // 5. Hidden Gems / Low Popularity Only filter
            var workingPool = candidates.toList()
            if (state.lowPopularityOnly) {
                val gemFiltered = workingPool.filter { track ->
                    when (state.hiddenGemTarget) {
                        "track" -> track.popularity <= 45
                        "artist" -> track.artistPopularity <= 48
                        "both" -> track.popularity <= 45 && track.artistPopularity <= 48
                        else -> track.popularity <= 45 || track.artistPopularity <= 48
                    }
                }.map { track ->
                    track.copy(
                        isHiddenGem = true,
                        hiddenGemType = state.hiddenGemTarget
                    )
                }
                workingPool = if (gemFiltered.size >= targetCount / 2) gemFiltered else workingPool.map {
                    it.copy(isHiddenGem = true, hiddenGemType = state.hiddenGemTarget)
                }
            }

            // 6. Diversity Enforcement & Per-Artist Capping
            val maxPerArtist = maxOf(2, minOf(4, targetCount / 10))
            val diverseSelection = mutableListOf<TrackEntity>()
            val artistCounts = mutableMapOf<String, Int>()
            val overflowTracks = mutableListOf<TrackEntity>()

            for (t in workingPool) {
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

            // 7. Final Selection & Quota Shortage Check
            // No library songs are backfilled. If quota is not reached, show what was found and inform the user.
            val finalTracks = diverseSelection.take(targetCount)
            val finalNotice = if (finalTracks.size < targetCount) {
                Strings.MixQuotaNotice.format(finalTracks.size, targetCount)
            } else {
                null
            }

            android.util.Log.d("DiscoverVM", "Generated final mix of ${finalTracks.size} tracks (target: $targetCount, notice: $finalNotice)")

            // 8. True Shuffle with Anti-Clumping
            val shuffled = ShuffleEngine.shuffleTracks(finalTracks, avoidConsecutiveArtists = true)

            _uiState.update {
                it.copy(
                    isGeneratingMix = false,
                    discoveredMix = shuffled,
                    infoBannerMessage = finalNotice
                )
            }
        }
    }
}
