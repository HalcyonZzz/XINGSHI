package com.xingshi.ai.feature.chat

import android.content.Context
import java.util.Base64

 
object VoiceTurnMetadataStore {

    private const val PREFS = "voice_turn_metadata"
    private const val MESSAGE_PREFIX = "m:"
    private const val CALL_PREFIX = "c:"

    data class Entry(
        val messageId: Long,
        val companionId: Long,
        val source: String,
        val capturedAt: Long,
        val durationMs: Long,
        val observation: String,
        val callId: String,
        val transcript: String
    )

    fun save(
        context: Context,
        messageId: Long,
        companionId: Long,
        source: String,
        capturedAt: Long,
        durationMs: Long,
        observation: String = "",
        callId: String = "",
        transcript: String = ""
    ) {
        val value = encode(Entry(messageId, companionId, source, capturedAt, durationMs, observation, callId, transcript))
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        if (messageId > 0L) editor.putString(MESSAGE_PREFIX + messageId, value)
        if (callId.isNotBlank()) {
            
            val index = preferences.getInt("count:" + callId, 0)
            editor.putString(CALL_PREFIX + callId + ":" + index, value)
            editor.putInt("count:" + callId, index + 1)
        }
        editor.apply()
    }

    fun forMessage(context: Context, messageId: Long): Entry? =
        decode(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(MESSAGE_PREFIX + messageId, null))

    fun forCall(context: Context, callId: String): List<Entry> {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = preferences.getInt("count:" + callId, 0)
        return (0 until count).mapNotNull { index ->
            decode(preferences.getString(CALL_PREFIX + callId + ":" + index, null))
        }
    }

    fun durationMs(context: Context, messageId: Long): Long =
        forMessage(context, messageId)?.durationMs ?: 0L

    fun removeCompanion(context: Context, companionId: Long) {
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = preferences.all.mapNotNull { (key, value) -> decode(value as? String)?.takeIf { it.companionId == companionId }?.let { key to it } }
        val editor = preferences.edit()
        entries.forEach { (key, _) -> editor.remove(key) }
        entries.map { it.second.callId }.filter { it.isNotBlank() }.distinct().forEach { editor.remove("count:$it") }
        check(editor.commit()) { "通话资料清理尚未完成" }
    }

    private fun encode(entry: Entry): String = listOf(
        entry.messageId.toString(),
        entry.companionId.toString(),
        entry.source,
        entry.capturedAt.toString(),
        entry.durationMs.toString(),
        b64(entry.observation),
        b64(entry.callId),
        b64(entry.transcript)
    ).joinToString("|")

    private fun decode(value: String?): Entry? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("|")
        if (parts.size < 8) return null
        return runCatching {
            Entry(
                messageId = parts[0].toLong(),
                companionId = parts[1].toLong(),
                source = parts[2],
                capturedAt = parts[3].toLong(),
                durationMs = parts[4].toLong(),
                observation = unb64(parts[5]),
                callId = unb64(parts[6]),
                transcript = unb64(parts[7])
            )
        }.getOrNull()
    }

    private fun b64(text: String): String =
        Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun unb64(text: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8) }.getOrDefault("")
}
