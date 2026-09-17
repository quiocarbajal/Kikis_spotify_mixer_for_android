package com.kiki.spotifymixer.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kiki.spotifymixer.data.local.entity.PlaylistEntity
import com.kiki.spotifymixer.ui.theme.*

@Composable
fun SavePlaylistDialog(
    queueTrackCount: Int,
    existingPlaylists: List<PlaylistEntity>,
    onSaveAsNew: (name: String, description: String) -> Unit,
    onOverwriteExisting: (playlist: PlaylistEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var mode by remember { mutableStateOf(0) } // 0 = New, 1 = Existing
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var selectedPlaylistToOverwrite by remember { mutableStateOf<PlaylistEntity?>(null) }
    var showOverwriteConfirmation by remember { mutableStateOf(false) }

    // Overwrite Confirmation Dialog
    if (showOverwriteConfirmation && selectedPlaylistToOverwrite != null) {
        val target = selectedPlaylistToOverwrite!!
        AlertDialog(
            onDismissRequest = { showOverwriteConfirmation = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Confirmar Sobrescritura",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = "¿Estás seguro de que deseas reemplazar las canciones de \"${target.name}\" con las $queueTrackCount canciones de tu cola actual?\n\nEsta acción modificará la playlist en tu cuenta de Spotify.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showOverwriteConfirmation = false
                        onOverwriteExisting(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Sobrescribir Playlist", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverwriteConfirmation = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = BgSurface1,
            shape = RoundedCornerShape(16.dp)
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = BgSurface1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .border(1.dp, BorderSubtle, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Guardar Cola como Playlist",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "$queueTrackCount canciones listas para guardar",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                )

                // Tab Switcher (New vs Existing)
                TabRow(
                    selectedTabIndex = mode,
                    containerColor = BgSurface2,
                    contentColor = SpotifyGreen,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Tab(
                        selected = mode == 0,
                        onClick = { mode = 0 },
                        text = { Text("Nueva Playlist") }
                    )
                    Tab(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        text = { Text("Sobrescribir") }
                    )
                }

                if (mode == 0) {
                    // New Playlist Form
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nombre de la Playlist") },
                        placeholder = { Text("Ej: Mi Mezcla Favorita") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it },
                        label = { Text("Descripción (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SpotifyGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onSaveAsNew(newName.trim(), newDesc.trim())
                            }
                        },
                        enabled = newName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SpotifyGreen,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Crear y Guardar en Spotify", fontWeight = FontWeight.Bold)
                    }
                } else {
                    // Overwrite Existing Playlist Selection
                    Text(
                        text = "Selecciona una playlist para reemplazar su contenido:",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val customPlaylists = existingPlaylists.filter { it.id != "liked_songs" && it.id != "all_tracks" }

                    if (customPlaylists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tienes playlists personalizadas disponibles para sobrescribir.",
                                color = TextMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(customPlaylists) { playlist ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = BgSurface2),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedPlaylistToOverwrite = playlist
                                            // Big changes or overwrite guard (>3 songs requires confirmation)
                                            if (queueTrackCount > 3 || playlist.totalTracks > 3) {
                                                showOverwriteConfirmation = true
                                            } else {
                                                onOverwriteExisting(playlist)
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlaylistPlay,
                                            contentDescription = null,
                                            tint = SpotifyGreen,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.name,
                                                color = TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${playlist.totalTracks} canciones actuales",
                                                color = TextMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar", color = TextSecondary)
                }
            }
        }
    }
}
