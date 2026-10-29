package com.miaocun.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val title: String,
    val uri: String,
    val time: Long,
    val isVideo: Boolean
)

/**
 * 保存记录，仅存 uri 与标题，用于在首页回看/打开。
 */
object HistoryStore {

    private const val FILE_NAME = "history.json"
    private const val MAX_ITEMS = 40

    private fun file(context: android.content.Context): File =
        File(context.filesDir, FILE_NAME)

    fun list(context: android.content.Context): List<HistoryItem> {
        val array = runCatching {
            val f = file(context)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        }.getOrDefault(JSONArray())
        val out = ArrayList<HistoryItem>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(
                HistoryItem(
                    title = o.optString("title"),
                    uri = o.optString("uri"),
                    time = o.optLong("time"),
                    isVideo = o.optBoolean("video", true)
                )
            )
        }
        return out
    }

    fun add(context: android.content.Context, item: HistoryItem) {
        val array = try {
            val f = file(context)
            if (!f.exists()) JSONArray() else JSONArray(f.readText())
        } catch (t: Throwable) {
            JSONArray()
        }
        val next = JSONArray()
        val obj = JSONObject().apply {
            put("title", item.title)
            put("uri", item.uri)
            put("time", item.time)
            put("video", item.isVideo)
        }
        next.put(obj)
        for (i in 0 until array.length()) {
            if (next.length() >= MAX_ITEMS) break
            array.opt(i)?.let { next.put(it) }
        }
        runCatching { file(context).writeText(next.toString()) }
    }

    fun clear(context: android.content.Context) {
        runCatching { file(context).delete() }
    }
}
