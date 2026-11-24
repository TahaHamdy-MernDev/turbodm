package com.turbodm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodm.android.engine.DownloadCoordinator
import com.turbodm.android.ui.components.CompactStepper
import com.turbodm.android.ui.theme.TurboColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    coordinator: DownloadCoordinator,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs by coordinator.prefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var updatingYtDlp by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TurboColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader("MULTI-PART ENGINE")
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default Connections", color = TurboColors.OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Parallel segments per file (1–32)", color = TurboColors.OnSurfaceVariant, fontSize = 12.sp)
                }
                CompactStepper(
                    value = prefs.defaultConnections,
                    min = 1,
                    max = 32,
                    onValueChange = { v -> coordinator.prefs.update { it.copy(defaultConnections = v) } },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wi-Fi Only Mode", color = TurboColors.OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Avoid mobile data for large files", color = TurboColors.OnSurfaceVariant, fontSize = 12.sp)
                }
                Switch(
                    checked = prefs.wifiOnly,
                    onCheckedChange = { on -> coordinator.prefs.update { it.copy(wifiOnly = on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TurboColors.Primary,
                    ),
                )
            }
        }

        SectionHeader("SOCIAL & VIDEO ENGINE (YT-DLP + FFMPEG)")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Supported Platforms", color = TurboColors.OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = "Share links directly from YouTube, YouTube Playlists, TikTok, Facebook, Instagram, X/Twitter, Reddit, Vimeo, SoundCloud, and HLS (.m3u8) streams.",
                    color = TurboColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Engine Version", color = TurboColors.OnSurfaceVariant, fontSize = 12.sp)
                Text(prefs.ytdlpVersion, color = TurboColors.EmeraldGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Button(
                onClick = {
                    if (updatingYtDlp) return@Button
                    updatingYtDlp = true
                    scope.launch {
                        coordinator.updateYtDlp()
                            .onSuccess { onMessage("yt-dlp: $it") }
                            .onFailure { onMessage("Update failed: ${it.message}") }
                        updatingYtDlp = false
                    }
                },
                enabled = !updatingYtDlp,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Primary),
                shape = RoundedCornerShape(8.dp),
            ) {
                if (updatingYtDlp) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (updatingYtDlp) "Updating yt-dlp..." else "Update yt-dlp Engine")
            }
        }

        SectionHeader("STORAGE & MAINTENANCE")
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Save Location", color = TurboColors.OnSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = "Downloads/TurboDM/{Video, Music, Compressed, Documents, Programs}",
                    color = TurboColors.AdwaitaBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                )
            }
            OutlinedButton(
                onClick = {
                    coordinator.clearCompleted()
                    onMessage("Cleared completed download entries")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.CleaningServices, contentDescription = null, tint = TurboColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Clear Completed List", color = TurboColors.OnSurface)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = TurboColors.OnSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TurboColors.SurfaceContainer)
            .border(1.dp, TurboColors.Outline, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}
