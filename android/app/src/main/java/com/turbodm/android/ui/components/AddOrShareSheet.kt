package com.turbodm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.turbodm.android.data.MediaFormatOption
import com.turbodm.android.data.ProbeResult
import com.turbodm.android.engine.DownloadCoordinator
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.launch

/**
 * Universal Add URL / Share Sheet UI:
 * Automatically handles:
 *  - Direct HTTP files (size, resume capability, [ - | 16 | + ] connections)
 *  - Single Videos (YouTube, TikTok, Facebook, Instagram, X/Twitter, Reddit, HLS) with thumbnail & quality chips
 *  - YouTube Playlists (multi-select video checklist + Select All + quality chips + Download Selected!)
 */
@Composable
fun AddOrShareSheetContent(
    coordinator: DownloadCoordinator,
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onEnqueued: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf(initialUrl) }
    var isProbing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<ProbeResult?>(null) }

    var customFilename by remember { mutableStateOf("") }
    var connections by remember { mutableIntStateOf(coordinator.prefs.state.value.defaultConnections) }
    var selectedFormat by remember { mutableStateOf<MediaFormatOption?>(null) }
    val selectedPlaylistIds = remember { mutableStateListOf<String>() }

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
                        is ProbeResult.SingleMedia -> selectedFormat = res.formats.firstOrNull()
                        is ProbeResult.Playlist -> {
                            selectedFormat = res.formats.firstOrNull()
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

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank()) {
            runProbe(initialUrl)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TurboColors.SurfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(TurboColors.AdwaitaBlue)
                )
                Text(
                    text = "Download with TurboDM",
                    color = TurboColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TurboColors.OnSurfaceVariant)
            }
        }

        // URL Input + Probe button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        "YouTube, Playlist, TikTok, FB, X, or file URL",
                        color = TurboColors.OnSurfaceVariant,
                        fontSize = 13.sp,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TurboColors.SurfaceDim,
                    unfocusedContainerColor = TurboColors.SurfaceDim,
                    focusedBorderColor = TurboColors.AdwaitaBlue,
                    unfocusedBorderColor = TurboColors.Outline,
                    focusedTextColor = TurboColors.OnSurface,
                    unfocusedTextColor = TurboColors.OnSurface,
                ),
                shape = RoundedCornerShape(8.dp),
            )
            Button(
                onClick = { runProbe(urlInput) },
                enabled = !isProbing && urlInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = TurboColors.SurfaceContainerHigh),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(52.dp),
            ) {
                Icon(Icons.Default.Search, contentDescription = "Inspect", tint = TurboColors.AdwaitaBlue)
            }
        }

        // Loading indicator
        if (isProbing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TurboColors.SurfaceDim)
                    .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp))
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

        // Error box
        probeError?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x28DE3730))
                    .border(1.dp, TurboColors.ErrorRed, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(text = msg, color = Color(0xFFFF9B96), fontSize = 12.sp)
            }
        }

        // --- Case 1: Direct File ---
        when (val res = probeResult) {
            is ProbeResult.DirectFile -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TurboColors.SurfaceDim)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Size: ${Formatters.formatBytes(res.totalSize)}",
                            fontFamily = FontFamily.Monospace,
                            color = TurboColors.EmeraldGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Resume: ${if (res.resumable) "Yes" else "No"} • ${res.category}",
                            fontFamily = FontFamily.Monospace,
                            color = TurboColors.AdwaitaBlue,
                            fontSize = 12.sp,
                        )
                    }

                    OutlinedTextField(
                        value = customFilename,
                        onValueChange = { customFilename = it },
                        label = { Text("File Name", color = TurboColors.OnSurfaceVariant, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = TurboColors.Surface,
                            unfocusedContainerColor = TurboColors.Surface,
                            focusedBorderColor = TurboColors.AdwaitaBlue,
                            unfocusedBorderColor = TurboColors.Outline,
                            focusedTextColor = TurboColors.OnSurface,
                            unfocusedTextColor = TurboColors.OnSurface,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Parallel Segments", color = TurboColors.OnSurfaceVariant, fontSize = 13.sp)
                        CompactStepper(
                            value = connections,
                            min = 1,
                            max = 32,
                            onValueChange = { connections = it },
                        )
                    }
                }

                Button(
                    onClick = {
                        coordinator.enqueueDirectDownload(res, customFilename, connections)
                        onEnqueued("Started: ${customFilename.ifBlank { res.filename }}")
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Primary),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start Multi-Part Download", fontWeight = FontWeight.SemiBold)
                }
            }

            // --- Case 2: Single Social / Video / Stream ---
            is ProbeResult.SingleMedia -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TurboColors.SurfaceDim)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (res.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = res.thumbnailUrl,
                            contentDescription = res.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 96.dp, height = 60.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(TurboColors.SurfaceContainerHigh),
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = res.title,
                            color = TurboColors.OnSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val dur = Formatters.formatDuration(res.durationSec)
                        Text(
                            text = listOfNotNull(
                                res.platform,
                                res.uploader.takeIf { it.isNotBlank() },
                                dur.takeIf { it.isNotBlank() }
                            ).joinToString(" • "),
                            color = TurboColors.OnSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text("Select Quality / Format", color = TurboColors.OnSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FormatChipRow(
                    formats = res.formats,
                    selected = selectedFormat,
                    onSelect = { selectedFormat = it },
                )

                Button(
                    onClick = {
                        val fmt = selectedFormat ?: res.formats.first()
                        coordinator.enqueueSingleMedia(res, fmt, connections)
                        onEnqueued("Downloading: ${res.title}")
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Primary),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (selectedFormat?.audioOnly == true) "Download Audio (${selectedFormat?.ext?.uppercase()})"
                        else "Download Video",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // --- Case 3: YouTube Playlist Multi-Select ---
            is ProbeResult.Playlist -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = TurboColors.EmeraldGreen)
                        Column {
                            Text(
                                text = res.title,
                                color = TurboColors.OnSurface,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${selectedPlaylistIds.size} of ${res.entries.size} videos selected",
                                color = TurboColors.EmeraldGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            if (selectedPlaylistIds.size == res.entries.size) {
                                selectedPlaylistIds.clear()
                            } else {
                                selectedPlaylistIds.clear()
                                selectedPlaylistIds.addAll(res.entries.map { it.id })
                            }
                        }
                    ) {
                        Text(
                            if (selectedPlaylistIds.size == res.entries.size) "Clear" else "Select All",
                            color = TurboColors.AdwaitaBlue,
                            fontSize = 12.sp,
                        )
                    }
                }

                FormatChipRow(
                    formats = res.formats,
                    selected = selectedFormat,
                    onSelect = { selectedFormat = it },
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TurboColors.SurfaceDim)
                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(8.dp)),
                ) {
                    itemsIndexed(res.entries, key = { _, e -> e.id }) { index, entry ->
                        val checked = entry.id in selectedPlaylistIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedPlaylistIds.remove(entry.id)
                                    else selectedPlaylistIds.add(entry.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) {
                                        if (entry.id !in selectedPlaylistIds) selectedPlaylistIds.add(entry.id)
                                    } else {
                                        selectedPlaylistIds.remove(entry.id)
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = TurboColors.Primary,
                                    uncheckedColor = TurboColors.OnSurfaceVariant,
                                ),
                            )
                            Text(
                                text = "${index + 1}. ${entry.title}",
                                modifier = Modifier.weight(1f),
                                color = TurboColors.OnSurface,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val d = Formatters.formatDuration(entry.durationSec)
                            if (d.isNotBlank()) {
                                Text(
                                    text = d,
                                    color = TurboColors.OnSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val chosen = res.entries.filter { it.id in selectedPlaylistIds }
                        val fmt = selectedFormat ?: res.formats.first()
                        coordinator.enqueuePlaylistEntries(res.title, chosen, fmt, connections)
                        onEnqueued("Queued ${chosen.size} videos from playlist")
                    },
                    enabled = selectedPlaylistIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TurboColors.Primary),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download Selected (${selectedPlaylistIds.size})", fontWeight = FontWeight.SemiBold)
                }
            }

            null -> {
                if (!isProbing && urlInput.isNotBlank() && probeError != null) {
                    // Quick direct fallback even if probe failed
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
                                    formats = com.turbodm.android.engine.YtDlpMediaEngine.defaultFormatOptions(),
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
                    ) {
                        Text("Download Directly Anyway", color = TurboColors.AdwaitaBlue)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FormatChipRow(
    formats: List<MediaFormatOption>,
    selected: MediaFormatOption?,
    onSelect: (MediaFormatOption) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        formats.forEach { opt ->
            val isSelected = selected?.id == opt.id && selected.ext == opt.ext
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) TurboColors.PrimaryContainer else TurboColors.SurfaceDim)
                    .border(
                        1.dp,
                        if (isSelected) TurboColors.AdwaitaBlue else TurboColors.Outline,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = TurboColors.EmeraldGreen,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = opt.label,
                    color = if (isSelected) TurboColors.OnPrimaryContainer else TurboColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
