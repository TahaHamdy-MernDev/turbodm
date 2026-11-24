package com.turbodm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.data.SegmentStatus
import com.turbodm.android.ui.components.SegmentByteMap
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters

@Composable
fun DownloadDetailSheetContent(
    item: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    val st = item.statusEnum()
    val segments = item.segments()
    val pct = Formatters.progressPercent(item.downloadedSize, item.totalSize)
    val isActive = st in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)
    val isDone = st == DownloadStatus.COMPLETED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TurboColors.SurfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(
                            when (st) {
                                DownloadStatus.DOWNLOADING, DownloadStatus.COMPLETED -> TurboColors.EmeraldGreen
                                DownloadStatus.PAUSED -> TurboColors.AmberWarning
                                DownloadStatus.ERROR -> TurboColors.ErrorRed
                                else -> TurboColors.AdwaitaBlue
                            }
                        )
                )
                Text(
                    text = item.filename,
                    color = TurboColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${"%.1f".format(pct)}%",
                color = TurboColors.EmeraldGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }

        // URL & Path
        Text(
            text = item.url,
            color = TurboColors.OnSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // 3-Column Recessed Telemetry Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TurboColors.SurfaceDim)
                .border(1.dp, TurboColors.OutlineSubtle, RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TelemetryCol(
                label = "Speed",
                value = if (isDone) "Done" else Formatters.formatSpeed(item.speedBytesPerSec),
                color = TurboColors.EmeraldGreen,
            )
            TelemetryCol(
                label = "Threads",
                value = "${(segments.size.takeIf { it > 0 } ?: item.maxConnections)} conns",
                color = TurboColors.OnSurface,
            )
            TelemetryCol(
                label = "Resume",
                value = if (item.resumable) "Yes" else "No",
                color = TurboColors.AdwaitaBlue,
            )
        }

        // Size & ETA line
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "${Formatters.formatBytes(item.downloadedSize)} / ${Formatters.formatBytes(item.totalSize)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TurboColors.OnSurface,
            )
            Text(
                text = "ETA: ${Formatters.formatEta(item.etaSeconds)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = TurboColors.OnSurfaceVariant,
            )
        }

        // Signature Segment Byte Map
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${(segments.size.takeIf { it > 0 } ?: 1)}-Segment Byte Map",
                    color = TurboColors.OnSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    text = when (st) {
                        DownloadStatus.DOWNLOADING -> if (item.resumable && !item.isMedia) "Dynamic Split Active" else item.platform
                        DownloadStatus.COMPLETED -> "Merged (0 MB Copy)"
                        DownloadStatus.ERROR -> item.errorMessage.take(36)
                        else -> st.name.lowercase().replaceFirstChar { it.uppercase() }
                    },
                    color = if (st == DownloadStatus.ERROR) TurboColors.ErrorRed else TurboColors.CyanTip,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            SegmentByteMap(item = item, height = 34.dp)
        }

        // Per-Segment Table
        if (segments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TurboColors.SurfaceDim)
                    .border(1.dp, TurboColors.OutlineSubtle, RoundedCornerShape(8.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Seg", modifier = Modifier.width(36.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TurboColors.OnSurfaceVariant)
                    Text("Downloaded", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TurboColors.OnSurfaceVariant)
                    Text("Speed", modifier = Modifier.width(74.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TurboColors.OnSurfaceVariant)
                    Text("State", modifier = Modifier.width(68.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TurboColors.OnSurfaceVariant)
                }
                HorizontalDivider(color = TurboColors.Outline)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                    items(segments, key = { it.index }) { seg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("#%02d".format(seg.index + 1), modifier = Modifier.width(36.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TurboColors.OnSurface)
                            Text(Formatters.formatBytes(seg.downloaded), modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TurboColors.OnSurfaceVariant)
                            Text(
                                text = if (seg.speedBytesPerSec > 0) Formatters.formatSpeed(seg.speedBytesPerSec) else "—",
                                modifier = Modifier.width(74.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (seg.speedBytesPerSec > 0) TurboColors.CyanTip else TurboColors.OnSurfaceVariant,
                            )
                            val (stateTxt, stateClr) = when (seg.status) {
                                SegmentStatus.COMPLETED -> "Done" to TurboColors.EmeraldGreen
                                SegmentStatus.RECEIVING -> "Active" to TurboColors.AdwaitaBlue
                                SegmentStatus.CONNECTING -> "Connect" to TurboColors.CyanTip
                                SegmentStatus.PAUSED -> "Paused" to TurboColors.AmberWarning
                                SegmentStatus.ERROR -> "Error" to TurboColors.ErrorRed
                                SegmentStatus.PENDING -> "Wait" to TurboColors.OnSurfaceVariant
                            }
                            Text(stateTxt, modifier = Modifier.width(68.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = stateClr)
                        }
                    }
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isDone) {
                Button(
                    onClick = onOpenFile,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Primary),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open File")
                }
            } else if (isActive) {
                Button(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.SurfaceContainerHigh),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, tint = TurboColors.AmberWarning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pause")
                }
            } else {
                Button(
                    onClick = onResume,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Primary),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Resume")
                }
            }

            OutlinedButton(
                onClick = { onDelete(true) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TurboColors.ErrorRed, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete", color = TurboColors.ErrorRed)
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun TelemetryCol(label: String, value: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TurboColors.OnSurfaceVariant, fontSize = 11.sp)
        Text(value, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
