package com.kiki.spotifymixer.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.kiki.spotifymixer.domain.GenreCatalog
import com.kiki.spotifymixer.ui.viewmodel.CatalogSearchType
import com.kiki.spotifymixer.ui.viewmodel.SearchLogicOperator
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiki.spotifymixer.data.local.entity.TrackEntity
import com.kiki.spotifymixer.ui.theme.BgMain
import com.kiki.spotifymixer.ui.theme.BgSurface1
import com.kiki.spotifymixer.ui.theme.BgSurface2
import com.kiki.spotifymixer.ui.theme.BorderSubtle
import com.kiki.spotifymixer.ui.theme.SpotifyGreen
import com.kiki.spotifymixer.ui.theme.StatusError
import com.kiki.spotifymixer.ui.theme.Strings
import com.kiki.spotifymixer.ui.theme.TextMuted
import com.kiki.spotifymixer.ui.theme.TextPrimary
import com.kiki.spotifymixer.ui.theme.TextSecondary
import com.kiki.spotifymixer.ui.viewmodel.ChipModifier
import com.kiki.spotifymixer.ui.viewmodel.DiscoverUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    onSubTabChange: (Int) -> Unit,
    onSearchChange: (String) -> Unit,
    onArtistInputChange: (String) -> Unit,
    onAddArtistModifier: (String, ChipModifier) -> Unit,
    onRemoveArtistModifier: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onGenreInputChange: (String) -> Unit = {},
    onAddGenreModifier: (String, ChipModifier) -> Unit = { _, _ -> },
    onRemoveGenreModifier: (String) -> Unit = {},
    onSelectGenreCategory: (String) -> Unit = {},
    onTrackInputChange: (String) -> Unit = {},
    onAddTrackModifier: (String, ChipModifier) -> Unit = { _, _ -> },
    onRemoveTrackModifier: (String) -> Unit = {},
    onToggleDecade: (String) -> Unit,
    onToggleExcludeLibrary: (Boolean) -> Unit,
    onSetTargetCount: (Int) -> Unit,
    onToggleLowPopularityOnly: (Boolean) -> Unit,
    onSetHiddenGemTarget: (String) -> Unit,
    onGenerateMix: () -> Unit,
    onPlayDiscoveredMix: (List<TrackEntity>) -> Unit,
    onReplaceQueueWithDiscovered: (List<TrackEntity>) -> Unit,
    onAppendDiscoveredToQueue: (List<TrackEntity>) -> Unit,
    onAddTrackToQueue: (TrackEntity) -> Unit,
    onPlayTrack: (TrackEntity) -> Unit = {},
    onLikeTrack: (TrackEntity) -> Unit = {},
    likedTrackIds: Set<String> = emptySet(),
    onSelectCatalogSearchType: (CatalogSearchType) -> Unit = {},
    onSelectCatalogOperator: (SearchLogicOperator) -> Unit = {},
    onAddCatalogModifier: (String, ChipModifier) -> Unit = { _, _ -> },
    onRemoveCatalogModifier: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Heading
        Text(
            text = Strings.DiscoveryStudio,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Sub-tabs: Catalog Search vs Surprise Me!
        TabRow(
            selectedTabIndex = state.activeSubTab,
            containerColor = BgMain,
            contentColor = SpotifyGreen,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[state.activeSubTab]),
                    color = SpotifyGreen
                )
            },
            divider = { Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BorderSubtle)) }
        ) {
            Tab(
                selected = state.activeSubTab == 0,
                onClick = { onSubTabChange(0) },
                text = {
                    Text(
                        text = Strings.SubTabSurpriseMe,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (state.activeSubTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (state.activeSubTab == 0) SpotifyGreen else TextSecondary
                    )
                }
            )
            Tab(
                selected = state.activeSubTab == 1,
                onClick = { onSubTabChange(1) },
                text = {
                    Text(
                        text = Strings.SubTabCatalogSearch,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (state.activeSubTab == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (state.activeSubTab == 1) SpotifyGreen else TextSecondary
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (state.activeSubTab == 0) {
            // Tab 0: Surprise Me! Discovery Mix Engine (Primary / First)
            SurpriseMeContent(
                state = state,
                onArtistInputChange = onArtistInputChange,
                onAddArtistModifier = onAddArtistModifier,
                onRemoveArtistModifier = onRemoveArtistModifier,
                onToggleGenre = onToggleGenre,
                onGenreInputChange = onGenreInputChange,
                onAddGenreModifier = onAddGenreModifier,
                onRemoveGenreModifier = onRemoveGenreModifier,
                onSelectGenreCategory = onSelectGenreCategory,
                onTrackInputChange = onTrackInputChange,
                onAddTrackModifier = onAddTrackModifier,
                onRemoveTrackModifier = onRemoveTrackModifier,
                onToggleDecade = onToggleDecade,
                onToggleExcludeLibrary = onToggleExcludeLibrary,
                onSetTargetCount = onSetTargetCount,
                onToggleLowPopularityOnly = onToggleLowPopularityOnly,
                onSetHiddenGemTarget = onSetHiddenGemTarget,
                onGenerateMix = onGenerateMix,
                onPlayMix = { onPlayDiscoveredMix(state.discoveredMix) },
                onReplaceQueue = { onReplaceQueueWithDiscovered(state.discoveredMix) },
                onAppendQueue = { onAppendDiscoveredToQueue(state.discoveredMix) },
                onPlayTrack = onPlayTrack,
                onAddTrack = onAddTrackToQueue,
                onLikeTrack = onLikeTrack,
                likedTrackIds = likedTrackIds
            )
        } else {
            // Tab 1: Catalog Search
            CatalogSearchContent(
                state = state,
                query = state.searchQuery,
                onQueryChange = onSearchChange,
                onSelectCatalogSearchType = onSelectCatalogSearchType,
                onSelectCatalogOperator = onSelectCatalogOperator,
                onAddCatalogModifier = onAddCatalogModifier,
                onRemoveCatalogModifier = onRemoveCatalogModifier,
                results = state.searchResults,
                onAddTrack = onAddTrackToQueue,
                onPlayTrack = onPlayTrack,
                onLikeTrack = onLikeTrack,
                likedTrackIds = likedTrackIds
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogSearchContent(
    state: DiscoverUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectCatalogSearchType: (CatalogSearchType) -> Unit,
    onSelectCatalogOperator: (SearchLogicOperator) -> Unit,
    onAddCatalogModifier: (String, ChipModifier) -> Unit,
    onRemoveCatalogModifier: (String) -> Unit,
    results: List<TrackEntity>,
    onAddTrack: (TrackEntity) -> Unit,
    onPlayTrack: (TrackEntity) -> Unit = {},
    onLikeTrack: (TrackEntity) -> Unit = {},
    likedTrackIds: Set<String> = emptySet()
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter Pills: 3 pills (Artista, Canción, Letra) + Todo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                CatalogSearchType.ALL to "🌟 Todo",
                CatalogSearchType.ARTIST to "👤 Artista",
                CatalogSearchType.TRACK to "🎵 Canción",
                CatalogSearchType.LYRICS to "📜 Letra"
            ).forEach { (type, label) ->
                val isSelected = state.catalogSearchType == type
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectCatalogSearchType(type) },
                    label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpotifyGreen,
                        selectedLabelColor = Color.Black,
                        containerColor = BgSurface1,
                        labelColor = TextSecondary
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Match Logic Selector (Y / O)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Coincidencia:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            FilterChip(
                selected = state.catalogOperator == SearchLogicOperator.AND,
                onClick = { onSelectCatalogOperator(SearchLogicOperator.AND) },
                label = { Text("Y (Todas)", style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SpotifyGreen,
                    selectedLabelColor = Color.Black,
                    containerColor = BgSurface2,
                    labelColor = TextSecondary
                ),
                shape = RoundedCornerShape(10.dp)
            )
            FilterChip(
                selected = state.catalogOperator == SearchLogicOperator.OR,
                onClick = { onSelectCatalogOperator(SearchLogicOperator.OR) },
                label = { Text("O (Cualquiera)", style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SpotifyGreen,
                    selectedLabelColor = Color.Black,
                    containerColor = BgSurface2,
                    labelColor = TextSecondary
                ),
                shape = RoundedCornerShape(10.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search Input Row with [+ Y] and [- NO] modifier buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                placeholder = {
                    val hint = when (state.catalogSearchType) {
                        CatalogSearchType.ALL -> "Buscar canciones, artistas..."
                        CatalogSearchType.ARTIST -> "Buscar por artista..."
                        CatalogSearchType.TRACK -> "Buscar por canción..."
                        CatalogSearchType.LYRICS -> "Buscar por letra o frases..."
                    }
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = TextMuted)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                ),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BgSurface1,
                    unfocusedContainerColor = BgSurface1,
                    focusedBorderColor = SpotifyGreen,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Button(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onAddCatalogModifier(query, ChipModifier.INCLUDE)
                },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text("+ Y", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onAddCatalogModifier(query, ChipModifier.EXCLUDE)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(48.dp)
            ) {
                Text("- NO", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Real-time Autocomplete Suggestions ("al igual que en esorprendeme")
        if (state.suggestedCatalogQueries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.suggestedCatalogQueries.forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onQueryChange(suggestion)
                        },
                        label = {
                            Text("💡 $suggestion", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = BgSurface2
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        // Active Modifier Chips Cloud
        if (state.catalogModifiers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.catalogModifiers.forEach { (term, mod) ->
                    val isInclude = mod == ChipModifier.INCLUDE
                    FilterChip(
                        selected = true,
                        onClick = { onRemoveCatalogModifier(term) },
                        label = {
                            Text(
                                text = if (isInclude) "+ $term ✕" else "- $term ✕",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = if (isInclude) SpotifyGreen else StatusError,
                            selectedLabelColor = if (isInclude) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.isSearchingCatalog) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen, modifier = Modifier.size(28.dp))
            }
        }

        if (results.isEmpty() && !state.isSearchingCatalog) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (query.isNotBlank() || state.catalogModifiers.isNotEmpty()) "No se encontraron canciones" else "Escribe arriba para buscar canciones y artistas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted
                )
            }
        } else {
            Text(
                text = "Resultados (${results.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { track ->
                    DiscoveredTrackRow(
                        track = track,
                        onPlay = { onPlayTrack(track) },
                        onAdd = { onAddTrack(track) },
                        onLike = { onLikeTrack(track) },
                        isLiked = likedTrackIds.contains(track.id)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurpriseMeContent(
    state: DiscoverUiState,
    onArtistInputChange: (String) -> Unit,
    onAddArtistModifier: (String, ChipModifier) -> Unit,
    onRemoveArtistModifier: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onGenreInputChange: (String) -> Unit = {},
    onAddGenreModifier: (String, ChipModifier) -> Unit = { _, _ -> },
    onRemoveGenreModifier: (String) -> Unit = {},
    onSelectGenreCategory: (String) -> Unit = {},
    onTrackInputChange: (String) -> Unit = {},
    onAddTrackModifier: (String, ChipModifier) -> Unit = { _, _ -> },
    onRemoveTrackModifier: (String) -> Unit = {},
    onToggleDecade: (String) -> Unit,
    onToggleExcludeLibrary: (Boolean) -> Unit,
    onSetTargetCount: (Int) -> Unit,
    onToggleLowPopularityOnly: (Boolean) -> Unit,
    onSetHiddenGemTarget: (String) -> Unit,
    onGenerateMix: () -> Unit,
    onPlayMix: () -> Unit,
    onReplaceQueue: () -> Unit,
    onAppendQueue: () -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onAddTrack: (TrackEntity) -> Unit,
    onLikeTrack: (TrackEntity) -> Unit = {},
    likedTrackIds: Set<String> = emptySet()
) {
    val decades = listOf("60s", "70s", "80s", "90s", "00s", "10s")

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section: Artists (AND / NOT)
        item {
            Text(
                text = Strings.ArtistsHeader,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = state.artistInputText,
                    onValueChange = onArtistInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    placeholder = {
                        Text(
                            Strings.ArtistInputPlaceholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BgSurface1,
                        unfocusedContainerColor = BgSurface1,
                        focusedBorderColor = SpotifyGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onAddArtistModifier(state.artistInputText, ChipModifier.INCLUDE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("+ Y", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onAddArtistModifier(state.artistInputText, ChipModifier.EXCLUDE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("- NO", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Real-time Artist Suggestions
            if (state.suggestedArtists.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    state.suggestedArtists.forEach { suggestion ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onArtistInputChange(suggestion)
                            },
                            label = {
                                Text("💡 $suggestion", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = BgSurface2
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Artist Chips Cloud
            if (state.artistModifiers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.artistModifiers.forEach { (artist, mod) ->
                        val isInclude = mod == ChipModifier.INCLUDE
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveArtistModifier(artist) },
                            label = {
                                Text(
                                    text = if (isInclude) "+ $artist ✕" else "- $artist ✕",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (isInclude) SpotifyGreen else StatusError,
                                selectedLabelColor = if (isInclude) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }
        }

        // Section: Genres (AND / NOT) with Mac-style Search, Suggestions & Category Tabs
        item {
            Text(
                text = Strings.GenresHeader,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Genre Search Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = state.genreInputText,
                    onValueChange = onGenreInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    placeholder = {
                        Text(
                            Strings.GenreInputPlaceholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BgSurface1,
                        unfocusedContainerColor = BgSurface1,
                        focusedBorderColor = SpotifyGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onAddGenreModifier(state.genreInputText, ChipModifier.INCLUDE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("+ Y", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onAddGenreModifier(state.genreInputText, ChipModifier.EXCLUDE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("- NO", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Real-time Genre Suggestions
            if (state.suggestedGenres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    state.suggestedGenres.forEach { suggestion ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onGenreInputChange(suggestion)
                            },
                            label = {
                                Text("💡 $suggestion", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = BgSurface2
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Active Genre Modifiers Chips Cloud
            if (state.genreModifiers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.genreModifiers.forEach { (genre, mod) ->
                        val isInclude = mod == ChipModifier.INCLUDE
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveGenreModifier(genre) },
                            label = {
                                Text(
                                    text = if (isInclude) "+ $genre ✕" else "- $genre ✕",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (isInclude) SpotifyGreen else StatusError,
                                selectedLabelColor = if (isInclude) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }
        }

        // Section: Similar to Song (AND / NOT) - Seed Engine
        item {
            Text(
                text = Strings.SongSeedsHeader,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Song Input Row with [+ Y] and [- NO] buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = state.trackInputText,
                    onValueChange = onTrackInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    placeholder = {
                        Text(
                            Strings.SongInputPlaceholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BgSurface1,
                        unfocusedContainerColor = BgSurface1,
                        focusedBorderColor = SpotifyGreen,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onAddTrackModifier(state.trackInputText, ChipModifier.INCLUDE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("+ Y", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        onAddTrackModifier(state.trackInputText, ChipModifier.EXCLUDE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("- NO", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Real-time Song Suggestions
            if (state.suggestedTracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    state.suggestedTracks.forEach { suggestion ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                onTrackInputChange("${suggestion.title} - ${suggestion.artist}")
                            },
                            label = {
                                Text(
                                    "🎵 ${suggestion.title} (${suggestion.artist})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = BgSurface2
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Active Track Modifiers Chips Cloud
            if (state.trackModifiers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.trackModifiers.forEach { (trackName, mod) ->
                        val isInclude = mod == ChipModifier.INCLUDE
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveTrackModifier(trackName) },
                            label = {
                                Text(
                                    text = if (isInclude) "+ $trackName ✕" else "- $trackName ✕",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (isInclude) SpotifyGreen else StatusError,
                                selectedLabelColor = if (isInclude) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }
        }

        // Section: Decades
        item {
            Text(
                text = Strings.DecadesHeader,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                decades.forEach { decade ->
                    val isSelected = state.selectedDecades.contains(decade)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggleDecade(decade) },
                        label = { Text(decade, style = MaterialTheme.typography.bodySmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SpotifyGreen,
                            selectedLabelColor = Color.Black,
                            containerColor = BgSurface1,
                            labelColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }
        }

        // Section: Strict Library Exclusion
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BgSurface1),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = Strings.ExcludeLibraryLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = Strings.ExcludeLibrarySubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Switch(
                        checked = state.excludeLibrarySongs,
                        onCheckedChange = onToggleExcludeLibrary,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SpotifyGreen,
                            checkedTrackColor = BgSurface2
                        )
                    )
                }
            }
        }

        // Section: Low Popularity Only (Hidden Gems - matching Mac app)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BgSurface1),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = Strings.HiddenGemsLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = Strings.HiddenGemsSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = state.lowPopularityOnly,
                            onCheckedChange = onToggleLowPopularityOnly,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SpotifyGreen,
                                checkedTrackColor = BgSurface2
                            )
                        )
                    }

                    if (state.lowPopularityOnly) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Alcance:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val targets = listOf(
                                "artist" to "👤 Artista",
                                "track" to "🎵 Canción",
                                "both" to "🌟 Ambos"
                            )
                            targets.forEach { (targetVal, label) ->
                                val isSelected = state.hiddenGemTarget == targetVal
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetHiddenGemTarget(targetVal) },
                                    label = {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = SpotifyGreen,
                                        selectedLabelColor = Color.Black,
                                        containerColor = BgSurface2,
                                        labelColor = TextSecondary
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Section: Target Track Count (30, 50, 75, 100 - matching Mac app)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = BgSurface1),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = Strings.TargetCountLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(30, 50, 75, 100).forEach { count ->
                            val isSelected = state.targetCount == count
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetTargetCount(count) },
                                label = {
                                    Text(
                                        text = "$count",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SpotifyGreen,
                                    selectedLabelColor = Color.Black,
                                    containerColor = BgSurface2,
                                    labelColor = TextSecondary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Generate Button
        item {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onGenerateMix()
                },
                enabled = !state.isGeneratingMix,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                shape = RoundedCornerShape(24.dp)
            ) {
                if (state.isGeneratingMix) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Strings.BtnGeneratingMix, color = Color.Black, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Casino, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Strings.BtnGenerateMix, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Discovered Mix Results
        if (state.discoveredMix.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = Strings.MixGeneratedHeader.format(state.discoveredMix.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onPlayMix,
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Strings.BtnPlayDiscovered, color = Color.Black, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onReplaceQueue,
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurface2),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.3f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Strings.BtnReplaceQueue, color = TextPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onAppendQueue,
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurface2),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.2f).height(42.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(Strings.BtnAddToQueue, color = TextPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            items(state.discoveredMix, key = { it.id }) { track ->
                DiscoveredTrackRow(
                    track = track,
                    onPlay = { onPlayTrack(track) },
                    onAdd = { onAddTrack(track) },
                    onLike = { onLikeTrack(track) },
                    isLiked = likedTrackIds.contains(track.id)
                )
            }
        }
    }
}

@Composable
fun DiscoveredTrackRow(
    track: TrackEntity,
    onPlay: () -> Unit = {},
    onAdd: () -> Unit = {},
    onLike: () -> Unit = {},
    isLiked: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = BgSurface1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (track.isHiddenGem || track.popularity <= 42) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF1DB954).copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "💎 Joya",
                                style = MaterialTheme.typography.labelSmall,
                                color = SpotifyGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = "${track.artist} • ${track.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Single Play Action + Heart Action + Add to Queue
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Me gusta",
                        tint = if (isLiked) SpotifyGreen else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = Strings.BtnPlayDiscovered,
                        tint = SpotifyGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = Strings.BtnAddToQueue,
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
