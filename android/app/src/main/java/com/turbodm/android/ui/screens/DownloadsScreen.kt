package com.turbodm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.ui.components.StatusPill
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters

@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    onSelectItem: (DownloadEntity) -> Unit,
    onTogglePauseResume: (DownloadEntity) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = listOf("All", "Active", "Done", "Video", "Music", "Compressed", "Documents", "Programs")
    var selectedFilter by remember { mutableStateOf("All") }

    val filtered = remember(downloads, selectedFilter) {
        when (selectedFilter) {
            "All" -> downloads
            "Active" -> downloads.filter { it.statusEnum() != DownloadStatus.COMPLETED }
            "Done" -> downloads.filter { it.statusEnum() == DownloadStatus.COMPLETED }
            else -> downloads.filter { it.category.equals(selectedFilter, ignoreCase = true) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TurboColors.Surface)
    ) {
        // Horizontal Filter Chips Bar (matching TurboDM mobile design)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TurboColors.SurfaceDim)
                .border(0.5.dp, TurboColors.Outline)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            filters.forEach { f ->
                val count = when (f) {
                    "All" -> downloads.size
                    "Active" -> downloads.count { it.statusEnum() != DownloadStatus.COMPLETED }
                    "Done" -> downloads.count { it.statusEnum() == DownloadStatus.COMPLETED }
                    else -> downloads.count { it.category.equals(f, true) }
                }
                val isSelected = selectedFilter == f
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) TurboColors.PrimaryContainer else TurboColors.SurfaceContainer)
                        .border(
                            1.dp,
                            if (isSelected) TurboColors.AdwaitaBlue else TurboColors.OutlineSubtle,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable { selectedFilter = f }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (count > 0) "$f ($count)" else f,
                        color = if (isSelected) TurboColors.OnPrimaryContainer else TurboColors.OnSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TurboColors.SurfaceContainer)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = TurboColors.AdwaitaBlue, modifier = Modifier.size(28.dp))
                    }
                    Text("No Downloads Here", color = TurboColors.OnSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = "Tap + to paste a link, or tap Share → TurboDM from YouTube, Playlists, TikTok, Facebook, X, or any browser.",
                        color = TurboColors.OnSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onClick = { onSelectItem(item) },
                        onToggle = { onTogglePauseResume(item) },
                        onOpen = { onOpenFile(item) },
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    item: DownloadEntity,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val st = item.statusEnum()
    val isActive = st in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)
    val isDone = st == DownloadStatus.COMPLETED
    val frac = Formatters.progressFraction(item.downloadedSize, item.totalSize, item.status)

    val catIcon = when (item.category) {
        "Video" -> Icons.Default.Movie
        "Music" -> Icons.Default.AudioFile
        "Compressed" -> Icons.Default.FolderZip
        "Documents" -> Icons.Default.Description
        "Programs" -> Icons.Default.Terminal
        else -> Icons.Default.Download
    }
    val iconColor = when (st) {
        DownloadStatus.COMPLETED -> TurboColors.EmeraldGreen
        DownloadStatus.PAUSED -> TurboColors.AmberWarning
        DownloadStatus.ERROR -> TurboColors.ErrorRed
        else -> TurboColors.AdwaitaBlue
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) TurboColors.PrimaryContainer.copy(alpha = 0.22f) else TurboColors.SurfaceContainer)
            .border(
                1.dp,
                if (isActive) TurboColors.AdwaitaBlue.copy(alpha = 0.65f) else TurboColors.Outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(catIcon, contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.filename,
                    color = TurboColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.platform} • ${item.category}",
                    color = TurboColors.OnSurfaceVariant,
                    fontSize = 11.sp,
                )
            }

            StatusPill(item = item)

            IconButton(
                onClick = { if (isDone) onOpen() else onToggle() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = when {
                        isDone -> Icons.AutoMirrored.Filled.OpenInNew
                        isActive -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = if (isDone) "Open" else if (isActive) "Pause" else "Resume",
                    tint = when {
                        isDone -> TurboColors.EmeraldGreen
                        isActive -> TurboColors.AmberWarning
                        else -> TurboColors.AdwaitaBlue
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (!isDone) {
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = if (st == DownloadStatus.PAUSED) TurboColors.AmberWarning else TurboColors.AdwaitaBlue,
                trackColor = TurboColors.SurfaceContainerHigh,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isDone) Formatters.formatBytes(item.totalSize)
                else "${Formatters.formatBytes(item.downloadedSize)} / ${Formatters.formatBytes(item.totalSize)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = TurboColors.OnSurfaceVariant,
            )

            Text(
                text = when {
                    isActive && item.speedBytesPerSec > 0 ->
                        "${Formatters.formatSpeed(item.speedBytesPerSec)} • ${Formatters.formatEta(item.etaSeconds)}"
                    st == DownloadStatus.ERROR -> item.errorMessage.take(32)
                    isDone -> "Saved in Downloads/TurboDM"
                    else -> "Tap to inspect segments"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = when {
                    isActive -> TurboColors.EmeraldGreen
                    st == DownloadStatus.ERROR -> TurboColors.ErrorRed
                    else -> TurboColors.OnSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
