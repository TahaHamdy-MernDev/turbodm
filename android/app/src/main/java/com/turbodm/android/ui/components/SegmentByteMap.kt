package com.turbodm.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * TurboDM lightning bolt brand badge matching Stitch header and sheet headers:
 * path M13 3L6 13H12L11 21L18 11H12L13 3Z inside a rounded #3584E4 square.
 */
@Composable
fun TurboBoltMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    cornerRadius: Dp = 8.dp,
    bgColor: Color = TurboColors.AdwaitaBlue,
    borderColor: Color? = null,
    iconTint: Color = Color.White,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(bgColor)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val iconScale = (this.size.minDimension * 0.5625f) / 24f
            val offsetX = (this.size.width - 24f * iconScale) / 2f
            val offsetY = (this.size.height - 24f * iconScale) / 2f
            fun pt(x: Float, y: Float) = Offset(offsetX + x * iconScale, offsetY + y * iconScale)

            val bolt = Path().apply {
                val p0 = pt(13f, 3f)
                moveTo(p0.x, p0.y)
                lineTo(pt(6f, 13f).x, pt(6f, 13f).y)
                lineTo(pt(12f, 13f).x, pt(12f, 13f).y)
                lineTo(pt(11f, 21f).x, pt(11f, 21f).y)
                lineTo(pt(18f, 11f).x, pt(18f, 11f).y)
                lineTo(pt(12f, 11f).x, pt(12f, 11f).y)
                close()
            }
            drawPath(path = bolt, color = iconTint)
        }
    }
}

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
    innerPadding: Dp = 6.dp,
    trackColor: Color = TurboColors.SurfaceDim,
    borderColor: Color = TurboColors.Outline,
    cornerRadiusDp: Dp = 8.dp,
    cellGap: Dp = 2.dp,
) {
    val segments = item.segments().sortedBy { it.start }
    val status = item.statusEnum()
    val total = item.totalSize

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(trackColor)
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadiusDp))
    ) {
        val pad = innerPadding.toPx()
        val innerW = (size.width - pad * 2f).coerceAtLeast(1f)
        val innerH = (size.height - pad * 2f).coerceAtLeast(1f)
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

        if (status == DownloadStatus.COMPLETED) {
            val cells = (segments.size.takeIf { it > 0 } ?: 16).coerceIn(1, 32)
            val gap = if (cells > 1) cellGap.toPx() else 0f
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
            val gap = if (segments.size > 1) cellGap.toPx() else 0f
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
                val barColor = when (status) {
                    DownloadStatus.PAUSED -> TurboColors.AmberWarning
                    DownloadStatus.ERROR -> TurboColors.ErrorRed
                    else -> TurboColors.AdwaitaBlue
                }
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(pad, pad),
                    size = Size(fillW, innerH),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * 60-second throughput area sparkline rendered with #38BDF8 gradient fill and live endpoint dot.
 */
@Composable
fun SpeedSparkline(
    samples: List<Double>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val topPad = 4.dp.toPx()
        val bottomPad = 4.dp.toPx()
        val usableH = (h - topPad - bottomPad).coerceAtLeast(1f)

        if (samples.size < 2) {
            val y = h - bottomPad - usableH * 0.15f
            drawLine(
                color = TurboColors.CyanTip.copy(alpha = 0.4f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = TurboColors.CyanTip.copy(alpha = 0.7f),
                radius = 3.dp.toPx(),
                center = Offset(w - 3.dp.toPx(), y),
            )
            return@Canvas
        }

        val maxVal = (samples.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
        val stepX = w / (samples.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()
        var lastPoint = Offset.Zero

        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val norm = (v / maxVal).toFloat().coerceIn(0f, 1f)
            val y = topPad + (1f - norm) * usableH
            val pt = Offset(x, y)
            if (i == 0) {
                linePath.moveTo(pt.x, pt.y)
                fillPath.moveTo(pt.x, h)
                fillPath.lineTo(pt.x, pt.y)
            } else {
                linePath.lineTo(pt.x, pt.y)
                fillPath.lineTo(pt.x, pt.y)
            }
            lastPoint = pt
        }
        fillPath.lineTo(lastPoint.x, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(TurboColors.CyanTip.copy(alpha = 0.28f), Color.Transparent),
                startY = 0f,
                endY = h,
            ),
        )
        drawPath(
            path = linePath,
            color = TurboColors.CyanTip,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(
            color = TurboColors.CyanTip,
            radius = 3.dp.toPx(),
            center = Offset(lastPoint.x.coerceAtMost(w - 3.dp.toPx()), lastPoint.y),
        )
    }
}

/** Pill [ − | N | + ] stepper matching Stitch settings & add sheet. */
@Composable
fun CompactStepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .clip(pillShape)
            .background(TurboColors.SurfaceDim)
            .border(1.dp, TurboColors.Outline, pillShape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (value > min) TurboColors.SurfaceContainerHigh else Color.Transparent)
                .clickable(enabled = value > min) { onValueChange((value - step).coerceAtLeast(min)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Decrease",
                tint = if (value > min) TurboColors.OnSurface else TurboColors.Outline,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = value.toString(),
            modifier = Modifier
                .widthIn(min = 34.dp)
                .padding(horizontal = 8.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = TurboColors.OnSurface,
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (value < max) TurboColors.SurfaceContainerHigh else Color.Transparent)
                .clickable(enabled = value < max) { onValueChange((value + step).coerceAtMost(max)) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Increase",
                tint = if (value < max) TurboColors.OnSurface else TurboColors.Outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Stitch 44dp x 24dp crisp pill switch (#3584E4 active, #090C10 recessed inactive). */
@Composable
fun TurboSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) TurboColors.AdwaitaBlue else TurboColors.SurfaceDim,
        animationSpec = tween(durationMillis = 120),
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color.White else TurboColors.OnSurfaceVariant,
        animationSpec = tween(durationMillis = 120),
        label = "switchThumb",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = tween(durationMillis = 120),
        label = "switchOffset",
    )
    val trackShape = RoundedCornerShape(999.dp)

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(trackShape)
            .background(trackColor)
            .then(if (!checked) Modifier.border(1.dp, TurboColors.Outline, trackShape) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
fun StatusPill(item: DownloadEntity) {
    val st = item.statusEnum()
    val parts = (item.segments().size.takeIf { it > 0 } ?: item.maxConnections).coerceAtLeast(1)
    val pct = Formatters.progressPercent(item.downloadedSize, item.totalSize)

    val (bg, fg, label, dotColor) = when (st) {
        DownloadStatus.DOWNLOADING -> StatusStyle(
            TurboColors.SuccessContainer,
            TurboColors.OnSuccessContainer,
            if (item.isMedia) "Downloading • ${"%.0f".format(pct)}%" else "${"%.1f".format(pct)}% • ${parts}x",
            TurboColors.EmeraldGreen,
        )
        DownloadStatus.PROBING -> StatusStyle(TurboColors.PrimaryContainer, TurboColors.OnPrimaryContainer, "Probing", TurboColors.AdwaitaBlue)
        DownloadStatus.MERGING -> StatusStyle(TurboColors.PrimaryContainer, TurboColors.CyanTip, "Merging", TurboColors.CyanTip)
        DownloadStatus.COMPLETED -> StatusStyle(TurboColors.SuccessContainer, TurboColors.OnSuccessContainer, "Completed", TurboColors.EmeraldGreen)
        DownloadStatus.PAUSED -> StatusStyle(TurboColors.WarningContainer, TurboColors.OnWarningContainer, "Paused • ${"%.0f".format(pct)}%", TurboColors.AmberWarning)
        DownloadStatus.ERROR -> StatusStyle(Color(0x38DE3730), Color(0xFFFF9B96), "Error", TurboColors.ErrorRed)
        DownloadStatus.CANCELLED -> StatusStyle(TurboColors.SurfaceContainerHigh, TurboColors.OnSurfaceVariant, "Cancelled", null)
        DownloadStatus.QUEUED -> StatusStyle(TurboColors.SurfaceContainerHigh, TurboColors.OnSurfaceVariant, "Queued", null)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = label,
            color = fg,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private data class StatusStyle(
    val bg: Color,
    val fg: Color,
    val label: String,
    val dot: Color?,
)
