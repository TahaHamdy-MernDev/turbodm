package com.turbodm.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.data.SegmentStatus
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters

/**
 * TurboDM signature rounded multi-segment byte map rendered on Compose Canvas.
 * Completed segments glow Emerald (#2EC27E), active segments fill Adwaita Blue (#3584E4)
 * with bright Cyan tips (#38BDF8), and paused segments render Amber (#E5A50A).
 */
@Composable
fun SegmentByteMap(
    item: DownloadEntity,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
) {
    val segments = item.segments().sortedBy { it.start }
    val status = item.statusEnum()
    val total = item.totalSize

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(TurboColors.SurfaceDim)
            .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp))
    ) {
        val pad = 6.dp.toPx()
        val innerW = (size.width - pad * 2f).coerceAtLeast(1f)
        val innerH = (size.height - pad * 2f).coerceAtLeast(1f)
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

        if (status == DownloadStatus.COMPLETED) {
            val cells = (segments.size.takeIf { it > 0 } ?: 16).coerceIn(1, 32)
            val gap = if (cells > 1) 2.5.dp.toPx() else 0f
            val cellW = ((innerW - gap * (cells - 1)) / cells).coerceAtLeast(2f)
            for (i in 0 until cells) {
                drawRoundRect(
                    color = TurboColors.EmeraldGreen,
                    topLeft = Offset(pad + i * (cellW + gap), pad),
                    size = Size(cellW, innerH),
                    cornerRadius = radius,
                )
            }
            return@Canvas
        }

        if (total != null && total > 0L && segments.isNotEmpty()) {
            val gap = if (segments.size > 1) 2.dp.toPx() else 0f
            segments.forEachIndexed { idx, seg ->
                val startX = pad + (seg.start.toFloat() / total.toFloat()) * innerW
                val endX = if (seg.end >= 0L) {
                    pad + ((seg.end + 1L).toFloat() / total.toFloat()) * innerW
                } else {
                    pad + innerW
                }
                val cellX = startX + if (idx > 0) gap / 2f else 0f
                val cellRight = endX - if (idx < segments.lastIndex) gap / 2f else 0f
                val cellW = (cellRight - cellX).coerceAtLeast(2f)

                drawRoundRect(
                    color = TurboColors.SurfaceContainerHigh,
                    topLeft = Offset(cellX, pad),
                    size = Size(cellW, innerH),
                    cornerRadius = radius,
                )

                val segTotal = seg.totalBytes.takeIf { it > 0L } ?: 1L
                val frac = (seg.downloaded.toFloat() / segTotal.toFloat()).coerceIn(0f, 1f)
                val fillW = cellW * frac
                if (fillW > 0.8f) {
                    val fillColor = when {
                        seg.status == SegmentStatus.COMPLETED || frac >= 0.999f -> TurboColors.EmeraldGreen
                        seg.status == SegmentStatus.ERROR -> TurboColors.ErrorRed
                        seg.status == SegmentStatus.PAUSED -> TurboColors.AmberWarning
                        else -> TurboColors.AdwaitaBlue
                    }
                    drawRoundRect(
                        color = fillColor,
                        topLeft = Offset(cellX, pad),
                        size = Size(fillW, innerH),
                        cornerRadius = radius,
                    )
                    if (seg.status == SegmentStatus.RECEIVING && frac < 0.999f) {
                        val tipW = minOf(3.dp.toPx(), fillW)
                        drawRect(
                            color = TurboColors.CyanTip,
                            topLeft = Offset(cellX + fillW - tipW, pad),
                            size = Size(tipW, innerH),
                        )
                    }
                }
            }
        } else {
            drawRoundRect(
                color = TurboColors.SurfaceContainerHigh,
                topLeft = Offset(pad, pad),
                size = Size(innerW, innerH),
                cornerRadius = radius,
            )
            val frac = Formatters.progressFraction(item.downloadedSize, item.totalSize, item.status)
            if (frac > 0f) {
                val fillW = innerW * frac
                drawRoundRect(
                    color = TurboColors.AdwaitaBlue,
                    topLeft = Offset(pad, pad),
                    size = Size(fillW, innerH),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/** Crisp [- | N | +] stepper matching our Fedora desktop UI. */
@Composable
fun CompactStepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(TurboColors.SurfaceDim)
            .border(1.dp, TurboColors.Outline, shape)
            .height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(TurboColors.SurfaceContainer)
                .clickable(enabled = value > min) { onValueChange((value - step).coerceAtLeast(min)) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "−",
                color = if (value > min) TurboColors.OnSurface else TurboColors.Outline,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(34.dp)
                .background(TurboColors.Outline)
        )
        Text(
            text = value.toString(),
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = TurboColors.OnSurface,
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(34.dp)
                .background(TurboColors.Outline)
        )
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(TurboColors.SurfaceContainer)
                .clickable(enabled = value < max) { onValueChange((value + step).coerceAtMost(max)) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                color = if (value < max) TurboColors.OnSurface else TurboColors.Outline,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun StatusPill(item: DownloadEntity) {
    val st = item.statusEnum()
    val parts = (item.segments().size.takeIf { it > 0 } ?: item.maxConnections).coerceAtLeast(1)
    val pct = Formatters.progressPercent(item.downloadedSize, item.totalSize)

    val (bg, fg, label) = when (st) {
        DownloadStatus.DOWNLOADING -> Triple(
            TurboColors.PrimaryContainer,
            TurboColors.OnPrimaryContainer,
            if (item.isMedia) "${"%.1f".format(pct)}%" else "${"%.1f".format(pct)}% • ${parts}x"
        )
        DownloadStatus.PROBING -> Triple(TurboColors.PrimaryContainer, TurboColors.OnPrimaryContainer, "Probing")
        DownloadStatus.MERGING -> Triple(TurboColors.PrimaryContainer, TurboColors.CyanTip, "Merging")
        DownloadStatus.COMPLETED -> Triple(TurboColors.SuccessContainer, TurboColors.OnSuccessContainer, "✓ Done")
        DownloadStatus.PAUSED -> Triple(TurboColors.WarningContainer, TurboColors.OnWarningContainer, "Paused")
        DownloadStatus.ERROR -> Triple(Color(0x38DE3730), Color(0xFFFF8A85), "Error")
        DownloadStatus.CANCELLED -> Triple(TurboColors.SurfaceContainerHigh, TurboColors.OnSurfaceVariant, "Cancelled")
        DownloadStatus.QUEUED -> Triple(TurboColors.SurfaceContainerHigh, TurboColors.OnSurfaceVariant, "Queued")
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}
