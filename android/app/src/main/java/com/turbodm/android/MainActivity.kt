package com.turbodm.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.turbodm.android.data.DownloadEntity
import com.turbodm.android.data.DownloadStatus
import com.turbodm.android.ui.components.AddOrShareSheetContent
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
                val totalSpeed = activeList.sumOf { it.speedBytesPerSec }

                var currentTab by remember { mutableIntStateOf(0) }
                var showAddSheet by remember { mutableStateOf(false) }
                var detailItemId by remember { mutableStateOf<String?>(null) }
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                val selectedDetail = downloads.find { it.id == detailItemId }

                Scaffold(
                    containerColor = TurboColors.Surface,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = TurboColors.SurfaceContainerHigh,
                                titleContentColor = TurboColors.OnSurface,
                            ),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(TurboColors.AdwaitaBlue),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Text("TurboDM", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            },
                            actions = {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(TurboColors.SurfaceDim)
                                        .border(1.dp, TurboColors.Outline, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 9.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = Formatters.formatSpeed(totalSpeed),
                                        color = if (totalSpeed > 0) TurboColors.EmeraldGreen else TurboColors.OnSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                    )
                                }
                            },
                        )
                    },
                    floatingActionButton = {
                        if (currentTab == 0) {
                            FloatingActionButton(
                                onClick = { showAddSheet = true },
                                containerColor = TurboColors.Primary,
                                contentColor = Color.White,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add URL")
                            }
                        }
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = TurboColors.SurfaceDim,
                            contentColor = TurboColors.OnSurface,
                        ) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Icon(Icons.Default.Download, contentDescription = "Downloads") },
                                label = { Text("Downloads") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TurboColors.OnPrimaryContainer,
                                    selectedTextColor = TurboColors.OnSurface,
                                    indicatorColor = TurboColors.PrimaryContainer,
                                    unselectedIconColor = TurboColors.OnSurfaceVariant,
                                    unselectedTextColor = TurboColors.OnSurfaceVariant,
                                ),
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Options") },
                                label = { Text("Options") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TurboColors.OnPrimaryContainer,
                                    selectedTextColor = TurboColors.OnSurface,
                                    indicatorColor = TurboColors.PrimaryContainer,
                                    unselectedIconColor = TurboColors.OnSurfaceVariant,
                                    unselectedTextColor = TurboColors.OnSurfaceVariant,
                                ),
                            )
                        }
                    },
                ) { padding ->
                    if (currentTab == 0) {
                        DownloadsScreen(
                            downloads = downloads,
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
                            modifier = Modifier.padding(padding),
                        )
                    } else {
                        SettingsScreen(
                            coordinator = coordinator,
                            onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }

                if (showAddSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showAddSheet = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = TurboColors.SurfaceContainer,
                    ) {
                        AddOrShareSheetContent(
                            coordinator = coordinator,
                            onDismiss = { showAddSheet = false },
                            onEnqueued = { msg ->
                                showAddSheet = false
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            },
                        )
                    }
                }

                if (selectedDetail != null) {
                    ModalBottomSheet(
                        onDismissRequest = { detailItemId = null },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = TurboColors.SurfaceContainer,
                    ) {
                        DownloadDetailSheetContent(
                            item = selectedDetail,
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
