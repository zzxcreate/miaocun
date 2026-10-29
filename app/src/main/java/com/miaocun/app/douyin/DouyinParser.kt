package com.miaocun.app.douyin

import android.content.Context
import com.miaocun.app.core.DiagLog
import com.miaocun.app.core.Prefs
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MediaItem(
    val url: String,
    val kind: Kind,
    val title: String
) {
    enum class Kind { VIDEO, IMAGE }
}

/**
 * 抖音分享链接解析。
 *
 * 思路：
 * 1. 从分享文本里抽第一个 http 链接；
 * 2. 若是直接媒体地址（.mp4/.jpg…）就直接用；
 * 3. 否则打开 iesdouyin 分享页，从 `_ROUTER_DATA` 里递归找带 desc + video/images 的对象；
 * 4. 取 play_addr（playwm → play 去水印）或 images[].url_list；
 * 5. 分享页失败时退回 iteminfo 接口；
 * 6. 若用户配置了自定义接口，优先走自定义接口。
 */
object DouyinParser {

    /**
     * 内置解析接口。
     *
     * 抖音自己的接口现在都要签名（a_bogus / ArgusSecurityPlugin），
     * 分享页 `_ROUTER_DATA` 里也已经不放播放地址了，直连解析基本拿不到东西。
     * 所以默认走一个第三方解析接口，返回的是**无水印直链**。
     * 接口失效时在「设置」里换一个自己的接口即可（见 Prefs.api）。
     */
    const val DEFAULT_API = "https://api.bugpk.com/api/douyin"

    private val URL_RE = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!%&'()*+,;=]+""")
    private val ID_RE = Regex("""/(?:video|note|slides)/(\d{6,})""")
    private val MODAL_RE = Regex("""(?:modal_id|item_ids|aweme_id|group_id)=(\d{6,})""")

    fun extractUrl(text: String): String? {
        val match = URL_RE.find(text) ?: return null
        return match.value.trimEnd('.', ',', '，', '。', ')', '）', '"', '\'', ' ')
    }

    private fun extractId(url: String): String? =
        ID_RE.find(url)?.groupValues?.getOrNull(1)
            ?: MODAL_RE.find(url)?.groupValues?.getOrNull(1)

    private fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m4a") ||
            path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".heic")
    }

    private fun directKind(url: String): MediaItem.Kind {
        val path = url.substringBefore('?').lowercase()
        return if (path.endsWith(".mp4") || path.endsWith(".mov")) MediaItem.Kind.VIDEO
        else MediaItem.Kind.IMAGE
    }

    suspend fun parse(context: Context, shareText: String): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val url = extractUrl(shareText)
            if (url == null) {
                DiagLog.log(context, "parse", "文本里没有链接")
                return@withContext emptyList()
            }
            DiagLog.log(context, "parse", "链接=" + url)

            val api = Prefs.get(context).api.trim().ifEmpty { DEFAULT_API }

            // 1. 解析接口（默认内置接口，返回无水印直链）
            val fromApi = runCatching { parseApi(context, api, url) }
                .onFailure { DiagLog.log(context, "parse", "接口调用失败：" + (it.message ?: it.javaClass.simpleName)) }
                .getOrNull()
            if (!fromApi.isNullOrEmpty()) return@withContext fromApi

            // 2. 自定义接口的其它常见返回格式
            if (api != DEFAULT_API) {
                val fromCustom = runCatching { parseCustomApi(api, url) }.getOrNull()
                if (!fromCustom.isNullOrEmpty()) return@withContext fromCustom
            }

            // 3. 本身就是媒体直链
            if (isDirectMedia(url)) {
                return@withContext listOf(
                    MediaItem(url, directKind(url), "直接链接")
                )
            }

            // 4. 老办法兜底：分享页 / iteminfo（现在多半是空的，留着是因为偶尔还能用）
            val id = extractId(url)
            DiagLog.log(context, "parse", "接口没结果，回落本地解析，作品 id=" + (id ?: "无"))

            val sharePages = ArrayList<String>()
            sharePages.add(url)
            if (id != null) {
                sharePages.add("https://www.iesdouyin.com/share/video/$id/")
                sharePages.add("https://www.iesdouyin.com/share/note/$id/")
            }
            for (page in sharePages.distinct()) {
                val items = runCatching { parseSharePage(page) }.getOrNull()
                if (!items.isNullOrEmpty()) return@withContext items
            }

            if (id != null) {
                val items = runCatching { parseItemInfo(id) }.getOrNull()
                if (!items.isNullOrEmpty()) return@withContext items
            }

            emptyList()
        }

    // ------------------------------------------------------------- 解析接口

    /**
     * 调解析接口，兼容常见返回结构：
     * `{code, data:{type,title,url,images[],video_backup[]}}`（内置接口就是这种）。
     */
    private fun parseApi(context: Context, api: String, shareUrl: String): List<MediaItem> {
        val separator = if (api.contains("?")) "&" else "?"
        val encoded = URLEncoder.encode(shareUrl, "UTF-8")
        val body = Http.getString(api + separator + "url=" + encoded, null)
        DiagLog.log(context, "parse", "接口返回 " + body.length + " 字节")

        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root == null) {
            DiagLog.log(context, "parse", "接口返回不是 JSON：" + body.take(80))
            return emptyList()
        }
        if (root.has("code") && root.optInt("code", 200) != 200) {
            DiagLog.log(context, "parse", "接口错误 code=" + root.optInt("code") + " msg=" + root.optString("msg"))
            return emptyList()
        }

        val data = root.optJSONObject("data") ?: root
        val title = cleanTitle(
            data.optString("title").ifEmpty { data.optString("desc") }
        )

        val out = ArrayList<MediaItem>()

        // 图集
        val images = data.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) {
                val node = images.opt(i)
                val u = when (node) {
                    is String -> node
                    is JSONObject -> firstUrl(node) ?: node.optString("url")
                    else -> ""
                }
                if (u.startsWith("http")) out.add(MediaItem(u, MediaItem.Kind.IMAGE, title))
            }
        }
        if (out.isNotEmpty()) return out

        // 视频：优先原画直链
        val direct = data.optString("url")
        if (direct.startsWith("http")) {
            out.add(MediaItem(direct, MediaItem.Kind.VIDEO, title))
            return out
        }

        // 备用线路里挑码率最高的一条
        val backup = data.optJSONArray("video_backup")
        var best: String? = null
        var bestRate = -1L
        if (backup != null) {
            for (i in 0 until backup.length()) {
                val node = backup.optJSONObject(i) ?: continue
                val u = node.optString("url")
                val rate = node.optLong("bit_rate", 0L)
                if (u.startsWith("http") && rate > bestRate) {
                    bestRate = rate
                    best = u
                }
            }
        }
        if (best != null) out.add(MediaItem(best, MediaItem.Kind.VIDEO, title))
        else DiagLog.log(context, "parse", "接口里没有可用的视频地址")
        return out
    }

    // ---------------------------------------------------------------- 分享页

    private fun parseSharePage(url: String): List<MediaItem> {
        val html = Http.getString(url, Http.REFERER)
        val routerJson = extractRouterData(html) ?: throw IOException("页面里没有 _ROUTER_DATA")
        val root = JSONObject(routerJson)
        val aweme = findAweme(root) ?: throw IOException("没找到作品数据")
        return mediaFromAweme(aweme)
    }

    /** 取 `_ROUTER_DATA` 后面的 JSON 对象，靠括号配对，避免被字符串里的括号干扰。 */
    private fun extractRouterData(html: String): String? {
        val keyIndex = html.indexOf("_ROUTER_DATA")
        if (keyIndex < 0) return null
        val start = html.indexOf('{', keyIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun findAweme(node: Any?): JSONObject? {
        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                findAweme(node.opt(i))?.let { return it }
            }
            return null
        }
        if (node !is JSONObject) return null

        val video = node.opt("video")
        val images = node.opt("images")
        val looksLikeAweme =
            (video is JSONObject && (video.has("play_addr") || video.has("bit_rate") || video.has("play_addr_h264"))) ||
                (images is JSONArray && images.length() > 0)

        if (looksLikeAweme && (
                node.has("desc") || node.has("aweme_id") || node.has("item_id") || node.has("video")
                )
        ) {
            return node
        }

        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val child = node.opt(key)
            if (child is JSONObject || child is JSONArray) {
                findAweme(child)?.let { return it }
            }
        }
        return null
    }

    private fun mediaFromAweme(aweme: JSONObject): List<MediaItem> {
        val title = cleanTitle(aweme.optString("desc"))
        val out = ArrayList<MediaItem>()

        val images = aweme.optJSONArray("images")
        if (images != null && images.length() > 0) {
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = firstUrl(image) ?: continue
                out.add(MediaItem(url, MediaItem.Kind.IMAGE, title))
            }
        }
        if (out.isNotEmpty()) return out

        val video = aweme.optJSONObject("video") ?: return emptyList()
        val candidates = ArrayList<String>()
        video.optJSONObject("play_addr")?.let { collectUrls(it, candidates) }
        video.optJSONObject("download_addr")?.let { collectUrls(it, candidates) }
        video.optJSONArray("bit_rate")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)
                    ?.optJSONObject("play_addr")
                    ?.let { collectUrls(it, candidates) }
            }
        }

        val best = candidates
            .map { it.replace("playwm", "play") }
            .distinct()
            .firstOrNull { it.startsWith("http") }
            ?: return emptyList()

        out.add(MediaItem(best, MediaItem.Kind.VIDEO, title))
        return out
    }

    private fun collectUrls(node: JSONObject, out: MutableList<String>) {
        val list = node.optJSONArray("url_list")
        if (list != null) {
            for (i in 0 until list.length()) {
                val u = list.optString(i)
                if (u.startsWith("http")) out.add(u)
            }
        }
        val single = node.optString("url")
        if (single.startsWith("http")) out.add(single)
    }

    private fun firstUrl(node: JSONObject): String? {
        val candidates = ArrayList<String>()
        collectUrls(node, candidates)
        node.optJSONObject("display_image")?.let { collectUrls(it, candidates) }
        node.optJSONObject("owner_watermark_image")?.let { collectUrls(it, candidates) }
        return candidates.firstOrNull { it.startsWith("http") }
    }

    // ------------------------------------------------------------- iteminfo

    private fun parseItemInfo(id: String): List<MediaItem> {
        val url = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$id"
        val body = Http.getString(url, Http.REFERER)
        val root = JSONObject(body)
        val list = root.optJSONArray("item_list") ?: return emptyList()
        val aweme = list.optJSONObject(0) ?: return emptyList()
        return mediaFromAweme(aweme)
    }

    // -------------------------------------------------------------- 自定义接口

    private fun parseCustomApi(api: String, shareUrl: String): List<MediaItem> {
        val separator = if (api.contains("?")) "&" else "?"
        val encoded = URLEncoder.encode(shareUrl, "UTF-8")
        val body = Http.getString(api + separator + "url=" + encoded, null)
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()

        val keys = listOf("media", "items", "data", "urls", "list")
        for (key in keys) {
            val array = root.optJSONArray(key) ?: continue
            val out = ArrayList<MediaItem>()
            for (i in 0 until array.length()) {
                when (val node = array.opt(i)) {
                    is String -> {
                        if (node.startsWith("http")) {
                            out.add(MediaItem(node, kindOf(node), "自定义接口"))
                        }
                    }

                    is JSONObject -> {
                        val u = node.optString("url").ifEmpty { node.optString("src") }
                        if (u.startsWith("http")) {
                            val type = node.optString("type")
                            val kind = if (type.isNotEmpty()) kindOfType(type) else kindOf(u)
                            val title = node.optString("title").ifEmpty { "自定义接口" }
                            out.add(MediaItem(u, kind, title))
                        }
                    }
                }
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun kindOf(url: String): MediaItem.Kind {
        val path = url.substringBefore('?').lowercase()
        return if (path.endsWith(".mp4") || path.endsWith(".mov") || path.contains("/video/"))
            MediaItem.Kind.VIDEO
        else MediaItem.Kind.IMAGE
    }

    private fun kindOfType(type: String): MediaItem.Kind {
        val t = type.lowercase()
        return if (t.contains("video") || t.contains("mp4")) MediaItem.Kind.VIDEO
        else MediaItem.Kind.IMAGE
    }

    private fun cleanTitle(desc: String): String {
        val t = desc.trim()
        if (t.isEmpty() || t == "null") return "抖音作品"
        return t
    }
}
