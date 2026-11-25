package com.turbodm.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.turbodm.android.ui.components.AddOrShareSheetContent
import com.turbodm.android.ui.theme.TurboColors
import com.turbodm.android.ui.theme.TurboDMTheme
import com.turbodm.android.util.Formatters

/**
 * Translucent floating Share Sheet receiver triggered when the user shares ANY link from
 * YouTube, YouTube Playlists, TikTok, Facebook, Instagram, X/Twitter, Reddit, Chrome, or Telegram.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedRaw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val extractedUrl = Formatters.extractUrl(sharedRaw).orEmpty()
        val coordinator = (application as TurboDMApplication).coordinator

        setContent {
            TurboDMTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finish() },
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .imePadding()
                            .navigationBarsPadding()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        color = TurboColors.Surface,
                        border = BorderStroke(1.dp, TurboColors.Outline),
                        tonalElevation = 12.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 10.dp, bottom = 2.dp)
                                    .size(width = 40.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(TurboColors.Outline)
                            )
                            AddOrShareSheetContent(
                                coordinator = coordinator,
                                initialUrl = extractedUrl,
                                onDismiss = { finish() },
                                onEnqueued = { message ->
                                    Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_SHORT).show()
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
