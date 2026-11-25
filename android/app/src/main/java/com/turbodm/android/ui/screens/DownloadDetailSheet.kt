package com.turbodm.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.ui.components.SegmentByteMap
import com.turbodm.android.ui.components.SpeedSparkline
import com.turbodm.android.ui.components.StatusPill
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.delay

@Composable
fun DownloadDetailSheetContent(
    item: DownloadEntity,
    speedSamples: List<Double> = emptyList(),
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val st = item.statusEnum()
    val segments = item.segments()
    val pct = Formatters.progressPercent(item.downloadedSize, item.totalSize)
    val isActive = st in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)
    val isDone = st == DownloadStatus.COMPLETED

    var copiedUrl by remember { mutableStateOf(false) }
    LaunchedEffect(copiedUrl) {
        if (copiedUrl) {
            delay(1500)
            copiedUrl = false
        }
    }

    val headerIcon = when (item.category) {
        "Video" -> Icons.Default.Movie
        "Music" -> Icons.Default.AudioFile
        "Compressed" -> Icons.Default.FolderZip
        "Documents" -> Icons.Default.Description
        "Programs" -> Icons.Default.Terminal
        else -> Icons.Default.Download
    }
    val headerIconTint = when (st) {
        DownloadStatus.COMPLETED -> TurboColors.EmeraldGreen
        DownloadStatus.PAUSED -> TurboColors.AmberWarning
        DownloadStatus.ERROR -> TurboColors.ErrorRed
        else -> TurboColors.AdwaitaBlue
    }
    val peakSpeed = remember(speedSamples, item.speedBytesPerSec) {
        maxOf(speedSamples.maxOrNull() ?: 0.0, item.speedBytesPerSec)
    }
    val friendlyPath = remember(item.saveDir) {
        val marker = "/Download/"
        val idx = item.saveDir.indexOf(marker)
        if (idx >= 0) "Downloads/${item.saveDir.substring(idx + marker.length)}"
        else item.saveDir
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TurboColors.Surface)
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TurboColors.SurfaceContainerHigh)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = headerIcon,
                        contentDescription = null,
                        tint = headerIconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = item.filename,
                        color = TurboColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(item = item)
                }
            }

            Spacer(Modifier.width(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TurboColors.SurfaceContainerHigh)
                        .border(1.dp, TurboColors.Outline, CircleShape)
                        .clickable {
                            when {
                                isDone -> onOpenFile()
                                isActive -> onPause()
                                else -> onResume()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            isDone -> Icons.AutoMirrored.Filled.OpenInNew
                            isActive -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = if (isDone) "Open file" else if (isActive) "Pause download" else "Resume download",
                        tint = when {
                            isDone -> TurboColors.EmeraldGreen
                            isActive -> TurboColors.AdwaitaBlue
                            else -> TurboColors.AmberWarning
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TurboColors.SurfaceContainerHigh)
                        .border(1.dp, TurboColors.Outline, CircleShape)
                        .clickable { onDelete(true) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Close else Icons.Default.DeleteOutline,
                        contentDescription = if (isActive) "Cancel download" else "Delete download",
                        tint = TurboColors.ErrorRed,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

        // Scrollable Sheet Body
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 3-Tile Hero Metric Strip (#090C10, 1dp #262E3F, 12dp radius)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeroMetricTile(
                    label = "Speed",
                    value = if (isDone) "Done" else Formatters.formatSpeed(item.speedBytesPerSec),
                    valueColor = if (isDone) TurboColors.EmeraldGreen else TurboColors.CyanTip,
                    modifier = Modifier.weight(1f),
                )
                HeroMetricTile(
                    label = "Progress",
                    value = if (isDone) "100%" else "${"%.1f".format(pct)}%",
                    valueColor = TurboColors.OnSurface,
                    modifier = Modifier.weight(1f),
                )
                HeroMetricTile(
                    label = "ETA",
                    value = if (isDone) "00:00" else Formatters.formatEta(item.etaSeconds),
                    valueColor = TurboColors.OnSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            // Live 16-Segment Map + 60s Throughput Sparkline Card (#090C10)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(TurboColors.SurfaceDim)
                    .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Live ${(segments.size.takeIf { it > 0 } ?: 16)}-Segment Map",
                        color = TurboColors.OnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "${Formatters.formatBytes(item.downloadedSize)} / ${Formatters.formatBytes(item.totalSize)}",
                        color = TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }

                SegmentByteMap(
                    item = item,
                    height = 32.dp,
                    innerPadding = 6.dp,
                    trackColor = TurboColors.SurfaceContainer,
                    borderColor = TurboColors.OutlineSubtle,
                    cornerRadiusDp = 10.dp,
                    cellGap = 3.dp,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LegendDot("Done", TurboColors.EmeraldGreen)
                    LegendDot("Active", TurboColors.AdwaitaBlue)
                    LegendDot("Tip", TurboColors.CyanTip)
                    LegendDot("Queued", TurboColors.SurfaceContainerHigh, bordered = true)
                }

                HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Throughput (60s)",
                            color = TurboColors.OnSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = "Peak ${Formatters.formatSpeed(peakSpeed)}",
                            color = TurboColors.EmeraldGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                    SpeedSparkline(samples = speedSamples)
                }
            }

            if (item.errorMessage.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x28DE3730))
                        .border(1.dp, TurboColors.ErrorRed, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = item.errorMessage,
                        color = Color(0xFFFF9B96),
                        fontSize = 12.5.sp,
                    )
                }
            }

            // Compact Key-Value Info Card (#161B26)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(TurboColors.SurfaceContainer)
                    .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Source URL",
                            color = TurboColors.OnSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = item.url,
                            color = TurboColors.OnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TurboColors.SurfaceContainerHigh)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(6.dp))
                            .clickable {
                                copyTextToClipboard(context, item.url)
                                copiedUrl = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (copiedUrl) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy source URL",
                            tint = if (copiedUrl) TurboColors.EmeraldGreen else TurboColors.OnSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

                InfoKeyValueRow(
                    label = "Save Path",
                    value = friendlyPath,
                    valueColor = TurboColors.OnSurface,
                )

                HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

                InfoKeyValueRow(
                    label = "Connections",
                    value = if (item.isMedia) item.platform
                    else "${(segments.size.takeIf { it > 0 } ?: item.maxConnections)} dynamic",
                    valueColor = TurboColors.AdwaitaBlue,
                )

                HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

                val (resumeLabel, resumeColor) = when {
                    item.etag.isNotBlank() -> "Yes • ETag verified" to TurboColors.EmeraldGreen
                    item.resumable -> "Yes • Range supported" to TurboColors.EmeraldGreen
                    else -> "Single-stream" to TurboColors.OnSurfaceVariant
                }
                InfoKeyValueRow(
                    label = "Resume Support",
                    value = resumeLabel,
                    valueColor = resumeColor,
                )
            }

            if (isDone) {
                Button(
                    onClick = onOpenFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TurboColors.Primary,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open File", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun HeroMetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TurboColors.SurfaceDim)
            .border(1.dp, TurboColors.Outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = TurboColors.OnSurfaceVariant,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color, bordered: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .then(
                    if (bordered) Modifier.border(1.dp, TurboColors.Outline, RoundedCornerShape(2.dp))
                    else Modifier
                )
        )
        Text(
            text = label,
            color = TurboColors.OnSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun InfoKeyValueRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = TurboColors.OnSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Source URL", text))
    }
}
