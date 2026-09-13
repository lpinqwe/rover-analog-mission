package com.rover.gateway

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * Sends statuses/errors directly to a Telegram chat (the same one where you receive APKs).
 * No dependencies — plain HttpsURLConnection.
 */
object TgNotify {

    const val KEY_TG_TOKEN = "tg_token"
    const val KEY_TG_CHAT = "tg_chat"
    const val DEFAULT_TOKEN = ""
    const val DEFAULT_CHAT = ""

    // Hard global limit: no more than 6 messages per minute in TG —
    // neither crashes nor the watchdog can flood the chat.
    private val lock = Any()
    private var windowStart = 0L
    private var count = 0

    fun report(prefs: SharedPreferences, text: String) {
        val token = prefs.getString(KEY_TG_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        val chat = prefs.getString(KEY_TG_CHAT, DEFAULT_CHAT) ?: DEFAULT_CHAT
        if (token.isBlank() || chat.isBlank()) return
        thread {
            try {
                val allowed = synchronized(lock) {
                    val now = System.currentTimeMillis()
                    if (now - windowStart > 60_000) {
                        windowStart = now
                        count = 0
                    }
                    if (count >= 6) false else {
                        count++
                        true
                    }
                }
                if (!allowed) return@thread  // silently drop excess
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                val conn = url.openConnection() as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject()
                    .put("chat_id", chat)
                    .put("text", text)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.inputStream.close()
            } catch (_: Throwable) {
                // reporter must not crash the app
            }
        }
    }
}