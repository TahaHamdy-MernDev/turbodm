package com.turbodm.android

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.ui.components.AddOrShareSheetContent
import com.turbodm.android.ui.components.TurboBoltMark
import com.turbodm.android.ui.screens.DownloadDetailSheetContent
import com.turbodm.android.ui.screens.DownloadsScreen
import com.turbodm.android.ui.screens.SettingsScreen
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.ui.theme.TurboDMTheme
import com.turbodm.android.util.Formatters
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF0E1117.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF090C10.toInt()),
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val coordinator = (application as TurboDMApplication).coordinator

        setContent {
            TurboDMTheme {
                val downloads by coordinator.downloadsFlow.collectAsState(initial = emptyList())
                val activeList by coordinator.activeSummary.collectAsState()
                val speedHistory by coordinator.speedHistory.collectAsState()
                val totalSpeed = activeList.sumOf { it.speedBytesPerSec }

                var currentTab by remember { mutableIntStateOf(0) }
                var showSearchBar by remember { mutableStateOf(false) }
                var searchQuery by remember { mutableStateOf("") }

                var showAddSheet by remember { mutableStateOf(false) }
                var addSheetInitialUrl by remember { mutableStateOf("") }
                var detailItemId by remember { mutableStateOf<String?>(null) }

                var clipboardUrl by remember { mutableStateOf<String?>(null) }
                var dismissedClipboardUrl by remember { mutableStateOf<String?>(null) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val detected = readClipboardUrl(this@MainActivity)
                            if (detected != clipboardUrl) {
                                clipboardUrl = detected
                                if (detected != null && detected != dismissedClipboardUrl) {
                                    dismissedClipboardUrl = null
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val selectedDetail = downloads.find { it.id == detailItemId }
                val visibleClipboardUrl = clipboardUrl?.takeIf { it != dismissedClipboardUrl }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TurboColors.Surface)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Sticky Top Header Bar
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TurboColors.Surface)
                                .statusBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    TurboBoltMark(size = 32.dp, cornerRadius = 8.dp)
                                    Text(
                                        text = when (currentTab) {
                                            0 -> "TurboDM"
                                            1 -> "Queue"
                                            else -> "Settings"
                                        },
                                        color = TurboColors.OnSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 18.sp,
                                        letterSpacing = (-0.2).sp,
                                    )
                                }

                                if (currentTab == 2) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(TurboColors.SurfaceContainer)
                                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(999.dp))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "v${BuildConfig.VERSION_NAME}",
                                            color = TurboColors.OnSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(TurboColors.SurfaceContainer)
                                                .border(1.dp, TurboColors.Outline, RoundedCornerShape(999.dp))
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (totalSpeed > 0.0 || activeList.isNotEmpty()) TurboColors.EmeraldGreen
                                                        else TurboColors.OnSurfaceVariant.copy(alpha = 0.5f)
                                                    )
                                            )
                                            Text(
                                                text = Formatters.formatSpeed(totalSpeed),
                                                color = TurboColors.OnSurface,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (showSearchBar) TurboColors.PrimaryContainer
                                                    else TurboColors.SurfaceContainer
                                                )
                                                .border(
                                                    1.dp,
                                                    if (showSearchBar) TurboColors.AdwaitaBlue else TurboColors.Outline,
                                                    CircleShape,
                                                )
                                                .clickable {
                                                    showSearchBar = !showSearchBar
                                                    if (!showSearchBar) searchQuery = ""
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Search downloads",
                                                tint = if (showSearchBar) TurboColors.OnPrimaryContainer else TurboColors.OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(TurboColors.SurfaceContainer)
                                                .border(1.dp, TurboColors.Outline, CircleShape)
                                                .clickable { currentTab = 2 },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = "Settings",
                                                tint = TurboColors.OnSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)
                        }

                        // Active Tab Body
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (currentTab) {
                                0, 1 -> DownloadsScreen(
                                    downloads = downloads,
                                    queueOnly = currentTab == 1,
                                    searchQuery = searchQuery,
                                    showSearchBar = showSearchBar,
                                    onSearchQueryChange = { searchQuery = it },
                                    clipboardUrl = visibleClipboardUrl,
                                    onQuickPasteDownload = { url ->
                                        dismissedClipboardUrl = url
                                        addSheetInitialUrl = url
                                        showAddSheet = true
                                    },
                                    onDismissClipboard = {
                                        dismissedClipboardUrl = clipboardUrl
                                    },
                                    onPauseAll = {
                                        coordinator.pauseAll()
                                        scope.launch { snackbarHostState.showSnackbar("Paused active downloads") }
                                    },
                                    onResumeAll = {
                                        coordinator.resumeAll()
                                        scope.launch { snackbarHostState.showSnackbar("Resumed queued downloads") }
                                    },
                                    onSelectItem = { detailItemId = it.id },
                                    onTogglePauseResume = { item ->
                                        val st = item.statusEnum()
                                        if (st in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING)) {
                                            coordinator.pauseDownload(item.id)
                                        } else {
                                            coordinator.startDownload(item.id)
                                        }
                                    },
                                    onOpenFile = { openDownloadedFile(it) },
                                )
                                else -> SettingsScreen(
                                    coordinator = coordinator,
                                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                )
                            }
                        }
                    }

                    // Snackbar overlay cleanly above floating controls
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 140.dp),
                    )

                    // Floating "+ Add Link" Extended Pill FAB on Downloads & Queue
                    if (currentTab != 2) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(end = 16.dp, bottom = 82.dp)
                                .shadow(12.dp, RoundedCornerShape(999.dp))
                                .clip(RoundedCornerShape(999.dp))
                                .background(TurboColors.Primary)
                                .border(1.dp, TurboColors.AdwaitaBlue, RoundedCornerShape(999.dp))
                                .clickable {
                                    addSheetInitialUrl = ""
                                    showAddSheet = true
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Link",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "Add Link",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                            )
                        }
                    }

                    // Floating Sleek Rounded Bottom Navigation Bar
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth()
                            .height(56.dp)
                            .shadow(16.dp, RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(TurboColors.SurfaceContainer)
                            .border(1.dp, TurboColors.Outline, RoundedCornerShape(16.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BottomNavItem(
                            selected = currentTab == 0,
                            icon = Icons.Default.Download,
                            label = "Downloads",
                            onClick = { currentTab = 0 },
                            modifier = Modifier.weight(1f),
                        )
                        BottomNavItem(
                            selected = currentTab == 1,
                            icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                            label = "Queue",
                            onClick = { currentTab = 1 },
                            modifier = Modifier.weight(1f),
                        )
                        BottomNavItem(
                            selected = currentTab == 2,
                            icon = Icons.Default.Tune,
                            label = "Settings",
                            onClick = { currentTab = 2 },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (showAddSheet) {
                    ModalBottomSheet(
                        onDismissRequest = {
                            showAddSheet = false
                            addSheetInitialUrl = ""
                        },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = TurboColors.Surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        dragHandle = {
                            Box(
                                modifier = Modifier
                                    .padding(top = 10.dp, bottom = 4.dp)
                                    .size(width = 40.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(TurboColors.Outline)
                            )
                        },
                    ) {
                        Box(modifier = Modifier.imePadding().navigationBarsPadding()) {
                            AddOrShareSheetContent(
                                coordinator = coordinator,
                                initialUrl = addSheetInitialUrl,
                                onDismiss = {
                                    showAddSheet = false
                                    addSheetInitialUrl = ""
                                },
                                onEnqueued = { msg ->
                                    showAddSheet = false
                                    addSheetInitialUrl = ""
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                            )
                        }
                    }
                }

                if (selectedDetail != null) {
                    ModalBottomSheet(
                        onDismissRequest = { detailItemId = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = TurboColors.Surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        dragHandle = {
                            Box(
                                modifier = Modifier
                                    .padding(top = 10.dp, bottom = 4.dp)
                                    .size(width = 40.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(TurboColors.Outline)
                            )
                        },
                    ) {
                        Box(modifier = Modifier.navigationBarsPadding()) {
                            DownloadDetailSheetContent(
                                item = selectedDetail,
                                speedSamples = speedHistory[selectedDetail.id].orEmpty(),
                                onPause = { coordinator.pauseDownload(selectedDetail.id) },
                                onResume = { coordinator.startDownload(selectedDetail.id) },
                                onOpenFile = { openDownloadedFile(selectedDetail) },
                                onDelete = { deleteFile ->
                                    coordinator.deleteDownload(selectedDetail.id, deleteFile)
                                    detailItemId = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun readClipboardUrl(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching null
        if (!cm.hasPrimaryClip()) return@runCatching null
        val item = cm.primaryClip?.getItemAt(0) ?: return@runCatching null
        val raw = item.coerceToText(context)?.toString() ?: return@runCatching null
        Formatters.extractUrl(raw)
    }.getOrNull()

    private fun openDownloadedFile(item: DownloadEntity) {
        val file = File(item.filePath.ifBlank { File(item.saveDir, item.filename).absolutePath })
        if (!file.exists()) {
            Toast.makeText(this, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, "Open ${file.name}")) }
            .onFailure { Toast.makeText(this, "No app available to open $ext files", Toast.LENGTH_SHORT).show() }
    }
}

@Composable
private fun BottomNavItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) TurboColors.AdwaitaBlue else TurboColors.OnSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}
