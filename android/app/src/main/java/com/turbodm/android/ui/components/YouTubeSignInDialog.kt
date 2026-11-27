package com.turbodm.android.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.util.YouTubeCookieHelper

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInDialog(
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    var hintMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.stopLoading()
                webViewRef?.destroy()
            }
            webViewRef = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TurboColors.Surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TurboColors.SurfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Cancel",
                    color = TurboColors.OnSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )

                Text(
                    text = "Sign in to YouTube",
                    color = TurboColors.OnSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(TurboColors.Primary)
                        .clickable {
                            if (YouTubeCookieHelper.exportFromWebView(context)) {
                                onSignedIn()
                            } else {
                                hintMessage = "Finish signing in to YouTube first, then tap Save & Done."
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "Save & Done",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.Outline)

            // Helper strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (hintMessage != null) TurboColors.WarningContainer else TurboColors.SurfaceDim)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = hintMessage ?: "Sign in to your YouTube account, then tap Save & Done.",
                    color = if (hintMessage != null) TurboColors.OnWarningContainer else TurboColors.OnSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            HorizontalDivider(thickness = 1.dp, color = TurboColors.OutlineSubtle)

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                    val webView = this
                    webViewRef = webView
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webView, true)
                    }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val safeUrl = url.orEmpty()
                                val onYouTubeHome = (safeUrl.startsWith("https://m.youtube.com") ||
                                    safeUrl.startsWith("https://www.youtube.com")) &&
                                    !safeUrl.contains("accounts.google.com")
                                if (onYouTubeHome && YouTubeCookieHelper.exportFromWebView(ctx)) {
                                    onSignedIn()
                                }
                            }
                        }
                        loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://m.youtube.com/")
                    }
                },
            )
        }
    }
}
