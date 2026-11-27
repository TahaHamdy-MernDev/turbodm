package com.turbodm.android.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.turbodm.android.data.MediaFormatOption
import com.turbodm.android.data.ProbeResult
import com.turbodm.android.engine.DownloadCoordinator
import com.turbodm.android.engine.YtDlpMediaEngine
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.launch

/**
 * Stitch-styled Add URL / Android Share Sheet UI:
 * - Direct HTTP files (size, resume capability, pill [- | 16 | +] connections)
 * - Single Videos (YouTube, TikTok, Facebook, Instagram, X/Twitter, Reddit, HLS)
 * - YouTube Playlists (multi-select video checklist + Select All + pill formats + sticky CTA)
 */
@Composable
fun AddOrShareSheetContent(
    coordinator: DownloadCoordinator,
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onEnqueued: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs by coordinator.prefs.state.collectAsState()
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf(initialUrl) }
    var urlFocused by remember { mutableStateOf(false) }
    var isProbing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }

    var customFilename by remember { mutableStateOf("") }
    var connections by remember { mutableIntStateOf(prefs.defaultConnections) }
    var selectedFormat by remember { mutableStateOf<MediaFormatOption?>(null) }
    val selectedPlaylistIds = remember { mutableStateListOf<String>() }

    fun pickPreferredFormat(formats: List<MediaFormatOption>): MediaFormatOption? {
        val pref = prefs.defaultVideoQuality
        return when (pref) {
            "mp3" -> formats.find { it.audioOnly && it.ext.equals("mp3", true) }
            "m4a" -> formats.find { it.audioOnly && it.ext.equals("m4a", true) }
            "9999" -> formats.firstOrNull { !it.audioOnly }
            else -> {
                val h = pref.toIntOrNull()
                if (h != null) formats.find { !it.audioOnly && it.height == h } else null
            }
        } ?: formats.firstOrNull()
    }

    fun runProbe(targetUrl: String) {
        val clean = Formatters.extractUrl(targetUrl) ?: targetUrl.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            probeError = "Enter a valid http:// or https:// link"
            return
        }
        urlInput = clean
        isProbing = true
        probeError = null
        probeResult = null
        scope.launch {
            runCatching { coordinator.probeUrl(clean) }
                .onSuccess { res ->
                    probeResult = res
                    when (res) {
                        is ProbeResult.DirectFile -> customFilename = res.filename
                        is ProbeResult.SingleMedia -> selectedFormat = pickPreferredFormat(res.formats)
                        is ProbeResult.Playlist -> {
                            selectedFormat = pickPreferredFormat(res.formats)
                            selectedPlaylistIds.clear()
                            selectedPlaylistIds.addAll(res.entries.map { it.id })
                        }
                    }
                    isProbing = false
                }
                .onFailure { err ->
                    probeError = err.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Could not inspect URL"
                    isProbing = false
                }
        }
    }

    fun runPlaylistProbe(targetUrl: String) {
        val clean = Formatters.extractUrl(targetUrl) ?: targetUrl.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return
        urlInput = clean
        isProbing = true
        probeError = null
        probeResult = null
        scope.launch {
            runCatching { coordinator.probePlaylistExplicit(clean) }
                .onSuccess { res ->
                    probeResult = res
                    when (res) {
                        is ProbeResult.DirectFile -> customFilename = res.filename
                        is ProbeResult.SingleMedia -> selectedFormat = pickPreferredFormat(res.formats)
                        is ProbeResult.Playlist -> {
                            selectedFormat = pickPreferredFormat(res.formats)
                            selectedPlaylistIds.clear()
                            selectedPlaylistIds.addAll(res.entries.map { it.id })
                        }
                    }
                    isProbing = false
                }
                .onFailure { err ->
                    probeError = err.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "Could not load playlist"
                    isProbing = false
                }
        }
    }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            runProbe(initialUrl)
        }
    }

    val platformChipText = when (val res = probeResult) {
        is ProbeResult.Playlist -> "YouTube Playlist • ${res.entries.size} videos"
        is ProbeResult.SingleMedia -> res.platform
        is ProbeResult.DirectFile -> "Direct File"
        null -> if (urlInput.isNotBlank()) Formatters.detectPlatformLabel(urlInput) else "Paste Any Link"
    }

    val targetSavePath = remember(probeResult, selectedFormat, customFilename, prefs.autoSortByCategory) {
        if (!prefs.autoSortByCategory) {
            "Downloads/TurboDM"
        } else {
            val cat = when (val res = probeResult) {
                is ProbeResult.DirectFile -> Formatters.detectCategory(customFilename.ifBlank { res.filename })
                is ProbeResult.SingleMedia, is ProbeResult.Playlist ->
                    if (selectedFormat?.audioOnly == true) "Music" else "Video"
                null -> ""
            }
            if (cat.isBlank() || cat == "General") "Downloads/TurboDM" else "Downloads/TurboDM/$cat"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TurboColors.Surface)
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Add to TurboDM",
                color = TurboColors.OnSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = (-0.2).sp,
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TurboColors.PrimaryContainer)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (probeResult is ProbeResult.Playlist || Formatters.isExplicitPlaylistUrl(urlInput)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = TurboColors.AdwaitaBlue,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = platformChipText,
                    color = TurboColors.OnPrimaryContainer,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

        // Scrollable Sheet Body
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Recessed URL Input Field (#090C10, 1dp #262E3F)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(TurboColors.SurfaceDim)
                    .border(
                        1.dp,
                        if (urlFocused) TurboColors.AdwaitaBlue else TurboColors.Outline,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = TurboColors.OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    cursorBrush = SolidColor(TurboColors.AdwaitaBlue),
                    textStyle = TextStyle(
                        color = TurboColors.OnSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { urlFocused = it.isFocused },
                    decorationBox = { inner ->
                        if (urlInput.isEmpty()) {
                            Text(
                                text = "YouTube, Playlist, TikTok, FB, X, or file URL",
                                color = TurboColors.OnSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    },
                )
                SmallSquareAction(
                    icon = Icons.Default.ContentPaste,
                    contentDescription = "Paste URL",
                    onClick = {
                        val pasted = readClipboardText(context)
                        if (!pasted.isNullOrBlank()) {
                            val extracted = Formatters.extractUrl(pasted) ?: pasted.trim()
                            urlInput = extracted
                            runProbe(extracted)
                        }
                    },
                )
                SmallSquareAction(
                    icon = Icons.Default.Search,
                    contentDescription = "Inspect URL",
                    tint = if (urlInput.isNotBlank() && !isProbing) TurboColors.AdwaitaBlue else TurboColors.OnSurfaceVariant,
                    onClick = { if (!isProbing && urlInput.isNotBlank()) runProbe(urlInput) },
                )
                if (urlInput.isNotEmpty()) {
                    SmallSquareAction(
                        icon = Icons.Default.Close,
                        contentDescription = "Clear URL",
                        onClick = {
                            urlInput = ""
                            probeResult = null
                            probeError = null
                        },
                    )
                }
            }

            if (isProbing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TurboColors.SurfaceContainer)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = TurboColors.AdwaitaBlue,
                    )
                    Text(
                        text = "Inspecting ${Formatters.detectPlatformLabel(urlInput)}...",
                        color = TurboColors.OnSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            probeError?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x28DE3730))
                        .border(1.dp, TurboColors.ErrorRed, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(text = msg, color = Color(0xFFFF9B96), fontSize = 12.5.sp)
                }
            }

            when (val res = probeResult) {
                is ProbeResult.DirectFile -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(TurboColors.SurfaceContainer)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(TurboColors.SuccessContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "Size • ${Formatters.formatBytes(res.totalSize)}",
                                    fontFamily = FontFamily.Monospace,
                                    color = TurboColors.OnSuccessContainer,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(TurboColors.PrimaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (res.resumable) "Resume: Yes • ${res.category}" else "Single Stream • ${res.category}",
                                    fontFamily = FontFamily.Monospace,
                                    color = TurboColors.OnPrimaryContainer,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "File Name",
                                color = TurboColors.OnSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TurboColors.SurfaceDim)
                                    .border(1.dp, TurboColors.Outline, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                BasicTextField(
                                    value = customFilename,
                                    onValueChange = { customFilename = it },
                                    singleLine = true,
                                    cursorBrush = SolidColor(TurboColors.AdwaitaBlue),
                                    textStyle = TextStyle(color = TurboColors.OnSurface, fontSize = 13.5.sp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = "Parallel Segments",
                                    color = TurboColors.OnSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = "HTTP byte-range connections",
                                    color = TurboColors.OnSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                            CompactStepper(
                                value = connections,
                                min = 1,
                                max = 32,
                                onValueChange = { connections = it },
                            )
                        }
                    }
                }

                is ProbeResult.SingleMedia -> {
                    MediaPreviewCard(
                        thumbnailUrl = res.thumbnailUrl,
                        badgeText = Formatters.formatDuration(res.durationSec),
                        title = res.title,
                        subtitle = listOfNotNull(
                            res.uploader.takeIf { it.isNotBlank() },
                            res.platform,
                        ).joinToString(" • "),
                        formats = res.formats,
                        selectedFormat = selectedFormat,
                        onSelectFormat = { selectedFormat = it },
                    )
                    if (!isProbing && Formatters.hasDownloadablePlaylistParam(res.url)) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(TurboColors.PrimaryContainer)
                                .border(1.dp, TurboColors.AdwaitaBlue.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
                                .clickable { runPlaylistProbe(res.url) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                contentDescription = null,
                                tint = TurboColors.AdwaitaBlue,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Load full playlist",
                                color = TurboColors.OnPrimaryContainer,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                is ProbeResult.Playlist -> {
                    MediaPreviewCard(
                        thumbnailUrl = res.entries.firstOrNull { it.thumbnailUrl.isNotBlank() }?.thumbnailUrl.orEmpty(),
                        badgeText = "${res.entries.size}",
                        title = res.title,
                        subtitle = listOfNotNull(
                            res.uploader.takeIf { it.isNotBlank() },
                            "${res.entries.size} videos",
                        ).joinToString(" • "),
                        formats = res.formats,
                        selectedFormat = selectedFormat,
                        onSelectFormat = { selectedFormat = it },
                    )

                    // Select Videos checklist header + container
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Select Videos (${selectedPlaylistIds.size} / ${res.entries.size} selected)",
                                color = TurboColors.OnSurface,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = if (selectedPlaylistIds.size == res.entries.size) "Clear" else "Select All",
                                color = TurboColors.AdwaitaBlue,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    if (selectedPlaylistIds.size == res.entries.size) {
                                        selectedPlaylistIds.clear()
                                    } else {
                                        selectedPlaylistIds.clear()
                                        selectedPlaylistIds.addAll(res.entries.map { it.id })
                                    }
                                },
                            )
                        }

                        val listHeight = (res.entries.size * 46).coerceIn(92, 220).dp
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(listHeight)
                                .clip(RoundedCornerShape(16.dp))
                                .background(TurboColors.SurfaceContainer)
                                .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp)),
                        ) {
                            itemsIndexed(res.entries, key = { _, e -> e.id }) { index, entry ->
                                val checked = entry.id in selectedPlaylistIds
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (checked) selectedPlaylistIds.remove(entry.id)
                                                else selectedPlaylistIds.add(entry.id)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { on ->
                                                if (on) {
                                                    if (entry.id !in selectedPlaylistIds) selectedPlaylistIds.add(entry.id)
                                                } else {
                                                    selectedPlaylistIds.remove(entry.id)
                                                }
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = TurboColors.AdwaitaBlue,
                                                uncheckedColor = TurboColors.Outline,
                                                checkmarkColor = Color.White,
                                            ),
                                            modifier = Modifier.size(24.dp),
                                        )
                                        Text(
                                            text = "%02d. %s".format(index + 1, entry.title),
                                            modifier = Modifier.weight(1f),
                                            color = if (checked) TurboColors.OnSurface else TurboColors.OnSurfaceVariant,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val d = Formatters.formatDuration(entry.durationSec)
                                        if (d.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(TurboColors.SurfaceDim)
                                                    .border(1.dp, TurboColors.OutlineSubtle, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = d,
                                                    color = TurboColors.OnSurfaceVariant,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                                    if (index < res.entries.lastIndex) {
                                        HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)
                                    }
                                }
                            }
                        }
                    }
                }

                null -> {
                    if (!isProbing && urlInput.isNotBlank() && probeError != null) {
                        OutlinedButton(
                            onClick = {
                                val clean = Formatters.extractUrl(urlInput) ?: urlInput.trim()
                                val isMedia = Formatters.isMediaOrSocialUrl(clean)
                                if (isMedia) {
                                    val fallback = ProbeResult.SingleMedia(
                                        url = clean,
                                        title = Formatters.filenameFromUrl(clean, "video"),
                                        uploader = "",
                                        platform = Formatters.detectPlatformLabel(clean),
                                        durationSec = null,
                                        thumbnailUrl = "",
                                        formats = YtDlpMediaEngine.defaultFormatOptions(),
                                    )
                                    coordinator.enqueueSingleMedia(fallback, fallback.formats.first(), connections)
                                } else {
                                    val fallback = ProbeResult.DirectFile(
                                        url = clean,
                                        finalUrl = clean,
                                        filename = Formatters.filenameFromUrl(clean),
                                        totalSize = null,
                                        resumable = false,
                                    )
                                    coordinator.enqueueDirectDownload(fallback, "", connections)
                                }
                                onEnqueued("Download queued directly")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Download Directly Anyway", color = TurboColors.AdwaitaBlue)
                        }
                    }
                }
            }
        }

        // Sticky Footer when a probe result is ready
        val res = probeResult
        if (res != null) {
            HorizontalDivider(thickness = 1.dp, color = TurboColors.Outline)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TurboColors.SurfaceContainer)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TurboColors.SurfaceDim)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = TurboColors.AdwaitaBlue,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = targetSavePath,
                            color = TurboColors.OnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TurboColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }

                val ctaEnabled = when (res) {
                    is ProbeResult.Playlist -> selectedPlaylistIds.isNotEmpty()
                    else -> true
                }
                val ctaLabel = when (res) {
                    is ProbeResult.Playlist -> "Download ${selectedPlaylistIds.size} Videos"
                    is ProbeResult.SingleMedia ->
                        if (selectedFormat?.audioOnly == true) "Download Audio (${selectedFormat?.ext?.uppercase()})"
                        else "Download Video"
                    is ProbeResult.DirectFile -> "Start Download"
                }

                Button(
                    onClick = {
                        when (res) {
                            is ProbeResult.DirectFile -> {
                                coordinator.enqueueDirectDownload(res, customFilename, connections)
                                onEnqueued("Started: ${customFilename.ifBlank { res.filename }}")
                            }
                            is ProbeResult.SingleMedia -> {
                                val fmt = selectedFormat ?: res.formats.first()
                                coordinator.enqueueSingleMedia(res, fmt, connections)
                                onEnqueued("Downloading: ${res.title}")
                            }
                            is ProbeResult.Playlist -> {
                                val chosen = res.entries.filter { it.id in selectedPlaylistIds }
                                val fmt = selectedFormat ?: res.formats.first()
                                coordinator.enqueuePlaylistEntries(res.title, chosen, fmt, connections)
                                onEnqueued("Queued ${chosen.size} videos from playlist")
                            }
                        }
                    },
                    enabled = ctaEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TurboColors.Primary,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ctaLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun MediaPreviewCard(
    thumbnailUrl: String,
    badgeText: String,
    title: String,
    subtitle: String,
    formats: List<MediaFormatOption>,
    selectedFormat: MediaFormatOption?,
    onSelectFormat: (MediaFormatOption) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TurboColors.SurfaceContainer)
            .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                if (thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = title,
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
                if (badgeText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(TurboColors.SurfaceDim.copy(alpha = 0.9f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badgeText,
                            color = TurboColors.OnSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    color = TurboColors.OnSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = TurboColors.OnSurfaceVariant,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Format selector pill row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            formats.forEach { opt ->
                val isSelected = selectedFormat?.id == opt.id && selectedFormat.ext == opt.ext
                val pillLabel = when {
                    opt.audioOnly && opt.ext.equals("mp3", true) -> "MP3 Audio"
                    opt.audioOnly && opt.ext.equals("m4a", true) -> "M4A"
                    opt.height >= 9000 -> "Best MP4"
                    opt.height > 0 -> "${opt.height}p MP4"
                    else -> opt.label
                }
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
                        .clickable { onSelectFormat(opt) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = pillLabel,
                        color = if (isSelected) Color.White else TurboColors.OnSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallSquareAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = TurboColors.OnSurfaceVariant,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(TurboColors.SurfaceContainer)
            .border(1.dp, TurboColors.Outline, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

private fun readClipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching null
    if (!cm.hasPrimaryClip()) return@runCatching null
    cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()
