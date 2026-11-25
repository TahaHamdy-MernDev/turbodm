package com.turbodm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodm.android.engine.DownloadCoordinator
import com.turbodm.android.ui.components.CompactStepper
import com.turbodm.android.ui.components.TurboSwitch
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
    var qualityMenuExpanded by remember { mutableStateOf(false) }

    val qualityOptions = remember {
        listOf(
            "1080" to "Best 1080p",
            "720" to "Best 720p",
            "480" to "Best 480p",
            "9999" to "Best Available",
            "mp3" to "Audio MP3",
            "m4a" to "Audio M4A",
        )
    }
    val selectedQualityLabel = qualityOptions.find { it.first == prefs.defaultVideoQuality }?.second ?: "Best 1080p"

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TurboColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 1. Download Engine Card
        SettingsGroup(title = "DOWNLOAD ENGINE") {
            SettingsRow(
                title = "Connections per file",
                subtitle = "Parallel HTTP range streams",
            ) {
                CompactStepper(
                    value = prefs.defaultConnections,
                    min = 4,
                    max = 32,
                    step = 4,
                    onValueChange = { v -> coordinator.prefs.update { it.copy(defaultConnections = v) } },
                )
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            SettingsRow(
                title = "Max concurrent",
                subtitle = "Active queue slots",
            ) {
                CompactStepper(
                    value = prefs.maxConcurrent,
                    min = 1,
                    max = 8,
                    step = 1,
                    onValueChange = { v -> coordinator.prefs.update { it.copy(maxConcurrent = v) } },
                )
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            SettingsRow(
                title = "Dynamic Segmentation",
                subtitle = "Bisect slow segments automatically",
            ) {
                TurboSwitch(
                    checked = prefs.dynamicSegmentation,
                    onCheckedChange = { on -> coordinator.prefs.update { it.copy(dynamicSegmentation = on) } },
                )
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            SettingsRow(
                title = "Wi-Fi only",
                subtitle = "Pause transfers on cellular data",
            ) {
                TurboSwitch(
                    checked = prefs.wifiOnly,
                    onCheckedChange = { on -> coordinator.prefs.update { it.copy(wifiOnly = on) } },
                )
            }
        }

        // 2. Social Media & Video Engine (yt-dlp)
        SettingsGroup(title = "SOCIAL MEDIA & VIDEO ENGINE (YT-DLP)") {
            SettingsRow(
                title = "Default quality",
                subtitle = "Preferred stream container",
            ) {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(TurboColors.SurfaceDim)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(999.dp))
                            .clickable { qualityMenuExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = selectedQualityLabel,
                            color = TurboColors.OnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        )
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = "Select quality",
                            tint = TurboColors.OnSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = qualityMenuExpanded,
                        onDismissRequest = { qualityMenuExpanded = false },
                        containerColor = TurboColors.SurfaceContainerHigh,
                    ) {
                        qualityOptions.forEach { (key, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (key == prefs.defaultVideoQuality) TurboColors.AdwaitaBlue else TurboColors.OnSurface,
                                        fontSize = 13.5.sp,
                                    )
                                },
                                onClick = {
                                    coordinator.prefs.update {
                                        it.copy(
                                            defaultVideoQuality = key,
                                            audioFormat = if (key == "m4a") "m4a" else "mp3",
                                        )
                                    }
                                    qualityMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            // Highlighted yt-dlp Engine Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TurboColors.SurfaceDim.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "yt-dlp Engine",
                        color = TurboColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(TurboColors.EmeraldGreen)
                        )
                        Text(
                            text = prefs.ytdlpVersion,
                            color = TurboColors.EmeraldGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TurboColors.SurfaceContainerHigh)
                        .border(1.dp, TurboColors.AdwaitaBlue, RoundedCornerShape(8.dp))
                        .clickable(enabled = !updatingYtDlp) {
                            updatingYtDlp = true
                            scope.launch {
                                coordinator.updateYtDlp()
                                    .onSuccess { onMessage("yt-dlp: $it") }
                                    .onFailure { onMessage("Update failed: ${it.message}") }
                                updatingYtDlp = false
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (updatingYtDlp) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 2.dp,
                            color = TurboColors.AdwaitaBlue,
                        )
                    }
                    Text(
                        text = if (updatingYtDlp) "Updating..." else "Update Engine",
                        color = TurboColors.OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 3. Storage & Organization
        SettingsGroup(title = "STORAGE & ORGANIZATION") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onMessage(
                            if (prefs.autoSortByCategory) "Saving inside Downloads/TurboDM/{Category}"
                            else "Saving directly inside Downloads/TurboDM"
                        )
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TurboColors.SurfaceDim)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = TurboColors.AdwaitaBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Save location",
                            color = TurboColors.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = if (prefs.autoSortByCategory) "Downloads/TurboDM/{Category}" else "Downloads/TurboDM",
                            color = TurboColors.OnSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TurboColors.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            SettingsRow(
                title = "Auto-sort by category",
                subtitle = "Video, Music, Documents, Compressed",
            ) {
                TurboSwitch(
                    checked = prefs.autoSortByCategory,
                    onCheckedChange = { on -> coordinator.prefs.update { it.copy(autoSortByCategory = on) } },
                )
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coordinator.clearCompleted()
                        onMessage("Cleared completed download entries")
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TurboColors.SurfaceDim)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = null,
                            tint = TurboColors.OnSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Clear completed list",
                            color = TurboColors.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = "Remove finished entries without deleting files",
                            color = TurboColors.OnSurfaceVariant,
                            fontSize = 12.5.sp,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TurboColors.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            color = TurboColors.OnSurfaceVariant,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(TurboColors.SurfaceContainer)
                .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp)),
            content = content,
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = TurboColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
            Text(
                text = subtitle,
                color = TurboColors.OnSurfaceVariant,
                fontSize = 12.5.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}
