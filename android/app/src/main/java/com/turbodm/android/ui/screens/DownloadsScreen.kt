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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.ui.components.SegmentByteMap
import com.turbodm.android.ui.components.StatusPill
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters

@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntity>,
    queueOnly: Boolean,
    searchQuery: String,
    showSearchBar: Boolean,
    onSearchQueryChange: (String) -> Unit,
    clipboardUrl: String?,
    onQuickPasteDownload: (String) -> Unit,
    onDismissClipboard: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onSelectItem: (DownloadEntity) -> Unit,
    onTogglePauseResume: (DownloadEntity) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = listOf("All", "Active", "Videos", "Files", "Completed")
    var selectedFilter by remember { mutableStateOf("All") }

    val activeStatuses = remember {
        setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING, DownloadStatus.QUEUED)
    }

    val filtered = remember(downloads, queueOnly, selectedFilter, searchQuery) {
        val base = if (queueOnly) {
            downloads.filter { it.statusEnum() != DownloadStatus.COMPLETED }
        } else {
            when (selectedFilter) {
                "All" -> downloads
                "Active" -> downloads.filter { it.statusEnum() in activeStatuses }
                "Videos" -> downloads.filter {
                    it.category.equals("Video", true) || (it.isMedia && !it.mediaAudioOnly)
                }
                "Files" -> downloads.filter { !it.isMedia }
                "Completed" -> downloads.filter { it.statusEnum() == DownloadStatus.COMPLETED }
                else -> downloads
            }
        }
        val q = searchQuery.trim()
        if (q.isBlank()) base
        else base.filter {
            it.filename.contains(q, ignoreCase = true) ||
                it.platform.contains(q, ignoreCase = true) ||
                it.category.contains(q, ignoreCase = true) ||
                it.url.contains(q, ignoreCase = true)
        }
    }

    val activeCount = remember(downloads) {
        downloads.count { it.statusEnum() in activeStatuses }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(TurboColors.Surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showSearchBar) {
            item(key = "search_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TurboColors.SurfaceDim)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TurboColors.OnSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        cursorBrush = SolidColor(TurboColors.AdwaitaBlue),
                        textStyle = TextStyle(color = TurboColors.OnSurface, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search files, videos, or platforms...",
                                    color = TurboColors.OnSurfaceVariant,
                                    fontSize = 13.5.sp,
                                )
                            }
                            inner()
                        },
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = TurboColors.OnSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onSearchQueryChange("") },
                        )
                    }
                }
            }
        }

        if (!queueOnly && !clipboardUrl.isNullOrBlank()) {
            item(key = "quick_paste_banner") {
                val preview = remember(clipboardUrl) {
                    clipboardUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TurboColors.SurfaceContainer)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = TurboColors.AdwaitaBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Link detected • $preview",
                        color = TurboColors.OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TurboColors.Primary)
                            .clickable { onQuickPasteDownload(clipboardUrl) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "Download",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss link banner",
                        tint = TurboColors.OnSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onDismissClipboard),
                    )
                }
            }
        }

        if (queueOnly) {
            item(key = "queue_header_controls") {
                val pendingCount = downloads.count { it.statusEnum() != DownloadStatus.COMPLETED }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Active & Queued ($pendingCount)",
                        color = TurboColors.OnSurfaceVariant,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(TurboColors.SurfaceContainerHigh)
                                .border(1.dp, TurboColors.Outline, RoundedCornerShape(999.dp))
                                .clickable(onClick = onPauseAll)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Pause All",
                                color = TurboColors.OnSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(TurboColors.Primary)
                                .clickable(onClick = onResumeAll)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Resume All",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        } else {
            item(key = "filter_chips") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters.forEach { f ->
                        val isSelected = selectedFilter == f
                        val label = if (f == "Active" && activeCount > 0) "Active ($activeCount)" else f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (isSelected) TurboColors.Primary else TurboColors.SurfaceContainerHigh
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color.Transparent else TurboColors.Outline,
                                    RoundedCornerShape(999.dp),
                                )
                                .clickable { selectedFilter = f }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TurboColors.OnSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty_state") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(TurboColors.SurfaceContainer)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = TurboColors.AdwaitaBlue,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Text(
                        text = if (queueOnly) "Queue is Empty" else "No Downloads Here",
                        color = TurboColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "No matches found for \"$searchQuery\"."
                        } else {
                            "Tap + Add Link below or choose Share → TurboDM from YouTube, Playlists, TikTok, Facebook, Instagram, or X."
                        },
                        color = TurboColors.OnSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                DownloadCard(
                    item = item,
                    onClick = { onSelectItem(item) },
                    onToggle = { onTogglePauseResume(item) },
                    onOpen = { onOpenFile(item) },
                )
            }
        }

        item(key = "bottom_clearance") {
            Spacer(Modifier.height(148.dp))
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
    val cardShape = RoundedCornerShape(16.dp)

    when {
        isDone -> {
            // Card 4: Completed Video / File
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(TurboColors.SurfaceContainer)
                    .border(1.dp, TurboColors.Outline, cardShape)
                    .clickable(onClick = onClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TurboColors.SuccessContainer)
                        .border(1.dp, TurboColors.EmeraldGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = TurboColors.EmeraldGreen,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.filename,
                        color = TurboColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Completed • ${Formatters.formatBytes(item.totalSize)} • ${item.category}",
                        color = TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TurboColors.SurfaceContainerHigh)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "Open",
                        color = TurboColors.OnSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        item.isMedia && isActive -> {
            // Card 2: Active Social Video Download with thumbnail & platform badge
            val pct = Formatters.progressPercent(item.downloadedSize, item.totalSize)
            val frac = Formatters.progressFraction(item.downloadedSize, item.totalSize, item.status)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(TurboColors.SurfaceContainer)
                    .border(1.dp, TurboColors.Outline, cardShape)
                    .clickable(onClick = onClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TurboColors.SurfaceDim)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = item.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = TurboColors.AdwaitaBlue,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(TurboColors.SurfaceDim.copy(alpha = 0.9f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = item.platform,
                            color = TurboColors.OnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = item.filename,
                        color = TurboColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.5.sp,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TurboColors.PrimaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = if (item.mediaAudioOnly) "Audio" else item.filename.substringAfterLast('.', "MP4").uppercase(),
                                color = TurboColors.OnPrimaryContainer,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            text = "${"%.0f".format(pct)}% • ${Formatters.formatSpeed(item.speedBytesPerSec)}",
                            color = TurboColors.CyanTip,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = TurboColors.AdwaitaBlue,
                        trackColor = TurboColors.SurfaceDim,
                    )
                }

                CircleActionButton(
                    icon = Icons.Default.Pause,
                    contentDescription = "Pause download",
                    tint = TurboColors.AdwaitaBlue,
                    onClick = onToggle,
                )
            }
        }

        isActive -> {
            // Card 1: Active Direct Multi-Segment Download with 16-segment live byte map
            val pct = Formatters.progressPercent(item.downloadedSize, item.totalSize)
            val threadCount = (item.segments().size.takeIf { it > 0 } ?: item.maxConnections).coerceAtLeast(1)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(TurboColors.SurfaceContainer)
                    .border(1.dp, TurboColors.Outline, cardShape)
                    .clickable(onClick = onClick)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TurboColors.SurfaceContainerHigh)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = categoryIcon(item.category),
                            contentDescription = null,
                            tint = TurboColors.AdwaitaBlue,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.filename,
                            color = TurboColors.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${"%.1f".format(pct)}% • ${item.platform}",
                            color = TurboColors.EmeraldGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }

                    CircleActionButton(
                        icon = Icons.Default.Pause,
                        contentDescription = "Pause download",
                        tint = TurboColors.AdwaitaBlue,
                        onClick = onToggle,
                    )
                }

                SegmentByteMap(
                    item = item,
                    height = 18.dp,
                    innerPadding = 4.dp,
                    trackColor = TurboColors.SurfaceDim,
                    borderColor = TurboColors.OutlineSubtle,
                    cornerRadiusDp = 6.dp,
                    cellGap = 2.dp,
                )

                HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${Formatters.formatBytes(item.downloadedSize)} / ${Formatters.formatBytes(item.totalSize)}",
                        color = TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = Formatters.formatSpeed(item.speedBytesPerSec),
                        color = TurboColors.CyanTip,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "${Formatters.formatEta(item.etaSeconds)} left",
                        color = TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "$threadCount threads",
                        color = TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        else -> {
            // Card 3: Paused / Queued / Error Download
            val frac = Formatters.progressFraction(item.downloadedSize, item.totalSize, item.status)
            val isError = st == DownloadStatus.ERROR
            val accentColor = when {
                isError -> TurboColors.ErrorRed
                st == DownloadStatus.PAUSED -> TurboColors.AmberWarning
                else -> TurboColors.AdwaitaBlue
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(TurboColors.SurfaceContainer)
                    .border(1.dp, TurboColors.Outline, cardShape)
                    .clickable(onClick = onClick)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TurboColors.SurfaceContainerHigh)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isError) Icons.Default.ErrorOutline else categoryIcon(item.category),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.filename,
                            color = TurboColors.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(item = item)
                    }

                    CircleActionButton(
                        icon = if (isError) Icons.Default.Refresh else Icons.Default.PlayArrow,
                        contentDescription = if (isError) "Retry download" else "Resume download",
                        tint = accentColor,
                        onClick = onToggle,
                    )
                }

                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = TurboColors.SurfaceDim,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${Formatters.formatBytes(item.downloadedSize)} / ${Formatters.formatBytes(item.totalSize)}",
                        color = TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = if (isError && item.errorMessage.isNotBlank()) item.errorMessage.take(36) else "0 B/s",
                        color = if (isError) Color(0xFFFF9B96) else TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(TurboColors.SurfaceContainerHigh)
            .border(1.dp, TurboColors.Outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "Video" -> Icons.Default.Movie
    "Music" -> Icons.Default.AudioFile
    "Compressed" -> Icons.Default.FolderZip
    "Documents" -> Icons.Default.Description
    "Programs" -> Icons.Default.Terminal
    else -> Icons.Default.Download
}
