package com.liquid.lfmomni.data

import android.content.Context
import com.liquid.lfmomni.model.Attachment
import com.liquid.lfmomni.model.AttachmentKind
import com.liquid.lfmomni.model.ChatMessage
import com.liquid.lfmomni.model.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会話履歴の端末内保存。
 *
 * Room を使うほどの構造ではない (1 会話・追記のみ) ため、内部ストレージの
 * JSON ファイルに保存する。ネットワークへは一切送らない。
 */
class ChatStore(context: Context) {

    private val file = File(context.filesDir, "conversation.json")

    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("messages") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    add(parseMessage(arr.getJSONObject(i)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(messages: List<ChatMessage>) {
        runCatching {
            val arr = JSONArray()
            messages.forEach { m -> arr.put(toJson(m)) }
            val root = JSONObject().put("version", 1).put("messages", arr)
            file.writeText(root.toString())
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun toJson(m: ChatMessage): JSONObject {
        val atts = JSONArray()
        m.attachments.forEach { a ->
            atts.put(
                JSONObject()
                    .put("kind", a.kind.name)
                    .put("uri", a.uriString)
                    .put("name", a.displayName)
                    .put("duration", a.durationMs)
            )
        }
        return JSONObject()
            .put("id", m.id)
            .put("role", m.role.name)
            .put("text", m.text)
            .put("attachments", atts)
            .put("audio", m.spokenAudioPath ?: JSONObject.NULL)
            .put("createdAt", m.createdAt)
            .put("isError", m.isError)
    }

    private fun parseMessage(o: JSONObject): ChatMessage {
        val atts = mutableListOf<Attachment>()
        o.optJSONArray("attachments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                atts.add(
                    Attachment(
                        kind = runCatching { AttachmentKind.valueOf(a.getString("kind")) }
                            .getOrDefault(AttachmentKind.IMAGE),
                        uriString = a.getString("uri"),
                        displayName = a.optString("name", ""),
                        durationMs = a.optLong("duration", 0L),
                    )
                )
            }
        }
        val audio = if (o.isNull("audio")) null else o.optString("audio").takeIf { it.isNotEmpty() }
        return ChatMessage(
            id = o.optString("id"),
            role = runCatching { Role.valueOf(o.getString("role")) }.getOrDefault(Role.USER),
            text = o.optString("text", ""),
            attachments = atts,
            spokenAudioPath = audio,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            isError = o.optBoolean("isError", false),
        )
    }
}
