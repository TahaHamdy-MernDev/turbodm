package com.turbodm.android.util

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Manages optional WebView YouTube session cookies exported in Netscape format for yt-dlp.
 * Only used if the user signs in via Settings -> YouTube Account.
 */
object YouTubeCookieHelper {

    private val AUTH_COOKIE_NAMES = setOf(
        "SAPISID",
        "SID",
        "HSID",
        "SSID",
        "APISID",
        "__Secure-1PSID",
        "__Secure-3PSID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
        "LOGIN_INFO",
    )

    private val COOKIE_SOURCES = listOf(
        "https://www.youtube.com" to ".youtube.com",
        "https://m.youtube.com" to ".youtube.com",
        "https://youtube.com" to ".youtube.com",
        "https://www.google.com" to ".google.com",
        "https://accounts.google.com" to ".google.com",
    )

    fun cookieFile(context: Context): File =
        File(context.filesDir, "youtube_cookies.txt")

    fun hasSavedCookies(context: Context): Boolean {
        val file = cookieFile(context)
        return file.exists() && file.length() > 64L
    }

    fun exportFromWebView(context: Context): Boolean {
        return runCatching {
            val cm = CookieManager.getInstance()
            cm.flush()

            val deduped = LinkedHashMap<Pair<String, String>, String>()
            var hasAuthCookie = false

            for ((url, domain) in COOKIE_SOURCES) {
                val raw = cm.getCookie(url).orEmpty()
                if (raw.isBlank()) continue
                for (entry in raw.split(';')) {
                    val eq = entry.indexOf('=')
                    if (eq <= 0) continue
                    val name = entry.substring(0, eq).trim().replace("\t", "").replace("\n", "").replace("\r", "")
                    val value = entry.substring(eq + 1).trim().replace("\t", "").replace("\n", "").replace("\r", "")
                    if (name.isEmpty() || value.isEmpty()) continue
                    if (name in AUTH_COOKIE_NAMES) hasAuthCookie = true
                    deduped[domain to name] = value
                }
            }

            if (!hasAuthCookie || deduped.isEmpty()) {
                return@runCatching false
            }

            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            for ((key, value) in deduped) {
                val (domain, name) = key
                sb.append(domain)
                    .append('\t')
                    .append("TRUE")
                    .append('\t')
                    .append('/')
                    .append('\t')
                    .append("TRUE")
                    .append('\t')
                    .append("2147483647")
                    .append('\t')
                    .append(name)
                    .append('\t')
                    .append(value)
                    .append('\n')
            }

            cookieFile(context).writeText(sb.toString(), Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun clearCookies(context: Context) {
        runCatching { cookieFile(context).delete() }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}
