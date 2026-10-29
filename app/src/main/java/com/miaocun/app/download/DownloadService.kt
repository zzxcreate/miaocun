package com.miaocun.app.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.miaocun.app.MainActivity
import com.miaocun.app.core.HistoryItem
import com.miaocun.app.core.HistoryStore
import com.miaocun.app.core.MediaSaver
import com.miaocun.app.core.Notifications
import com.miaocun.app.core.Util
import com.miaocun.app.douyin.DouyinParser
import com.miaocun.app.douyin.Http
import com.miaocun.app.douyin.MediaItem
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 解析 + 下载 + 写入相册。
 *
 * 两种入口：
 * - EXTRA_SHARE：一段分享文本（自动抽链接再解析）
 * - EXTRA_URL：直链（截图分享页之外的兜底）
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground("正在解析链接…")

        val share = intent.getStringExtra(EXTRA_SHARE)
        val directUrl = intent.getStringExtra(EXTRA_URL)
        val kind = intent.getStringExtra(EXTRA_KIND)
        val title = intent.getStringExtra(EXTRA_TITLE)

        work?.cancel()
        work = scope.launch {
            try {
                val items: List<MediaItem> = if (!directUrl.isNullOrEmpty()) {
                    listOf(
                        MediaItem(
                            url = directUrl,
                            kind = if (kind == "image") MediaItem.Kind.IMAGE else MediaItem.Kind.VIDEO,
                            title = title.orEmpty().ifEmpty { "直接链接" }
                        )
                    )
                } else {
                    DouyinParser.parse(this@DownloadService, share.orEmpty())
                }

                if (items.isEmpty()) {
                    finishWith("解析失败", "没能从链接里找到视频或图片")
                    return@launch
                }

                var success = 0
                items.forEachIndexed { index, item ->
                    updateProgress(
                        title = "正在保存 ${index + 1}/${items.size}",
                        text = item.title.take(40),
                        percent = (index * 100) / items.size
                    )
                    if (saveItem(item)) success++
                }

                if (success > 0) {
                    finishWith(
                        "已保存 $success 个文件",
                        "位置：相册「秒存」文件夹"
                    )
                } else {
                    finishWith("保存失败", "请检查网络后重试")
                }
            } catch (t: Throwable) {
                finishWith("保存失败", t.message ?: t.javaClass.simpleName)
            } finally {
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun saveItem(item: MediaItem): Boolean = withContext(Dispatchers.IO) {
        val isVideo = item.kind == MediaItem.Kind.VIDEO
        val extension = if (isVideo) "mp4" else "jpg"
        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val fileName =
            "秒存_" + Util.timestamp() + "_" + Util.randomSuffix() + "." + extension

        val uri = MediaSaver.createEntry(this@DownloadService, isVideo, fileName, mime)
            ?: return@withContext false

        try {
            val out = contentResolver.openOutputStream(uri)
                ?: throw IOException("无法写入相册")
            out.use { stream ->
                Http.downloadTo(item.url, Http.REFERER, stream) { done, total ->
                    if (total > 0) {
                        val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
                        notifyProgress("正在下载", percent)
                    }
                }
            }
            if (!isVideo) {
                val realExt = sniffImageExtension(uri)
                if (realExt != null && realExt != "jpg") {
                    val base = fileName.substringBeforeLast('.', fileName)
                    MediaSaver.updateMeta(
                        this@DownloadService,
                        uri,
                        "$base.$realExt",
                        imageMime(realExt)
                    )
                }
            }
            MediaSaver.finishEntry(this@DownloadService, uri)
            HistoryStore.add(
                this@DownloadService,
                HistoryItem(
                    title = item.title,
                    uri = uri.toString(),
                    time = System.currentTimeMillis(),
                    isVideo = isVideo
                )
            )
            true
        } catch (t: Throwable) {
            MediaSaver.delete(this@DownloadService, uri)
            false
        }
    }

    /** 读文件头判断图片真实格式，抖音图片多半是 webp。 */
    private fun sniffImageExtension(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            val bytes = ByteArray(12)
            val read = input.read(bytes)
            if (read < 12) return@use null
            val head = String(bytes, 0, 12, Charsets.ISO_8859_1)
            when {
                head.startsWith("RIFF") && head.substring(8).startsWith("WEBP") -> "webp"
                head.startsWith("\u0089PNG") -> "png"
                head.regionMatches(4, "ftyp", 0, 4) -> "heic"
                else -> null
            }
        }
    }.getOrNull()

    private fun imageMime(ext: String): String = when (ext) {
        "webp" -> "image/webp"
        "png" -> "image/png"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    // ------------------------------------------------------------- 通知相关

    private fun notifyManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun baseNotification(title: String, text: String): NotificationCompat.Builder =
        Notifications.builder(this, Notifications.CHANNEL_SAVE)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setProgress(0, 0, true)

    private fun startAsForeground(text: String) {
        val notification = baseNotification("秒存", text).build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_SAVE, notification, type)
    }

    private fun notifyProgress(title: String, percent: Int) {
        val notification = baseNotification(title, "$percent%")
            .setProgress(100, percent, false)
            .build()
        notifyManager().notify(Notifications.ID_SAVE, notification)
    }

    private fun updateProgress(title: String, text: String, percent: Int) {
        val notification = baseNotification(title, text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
        notifyManager().notify(Notifications.ID_SAVE, notification)
    }

    private suspend fun finishWith(title: String, text: String) {
        withContext(Dispatchers.Main) {
            val done: Notification = Notifications.builder(this@DownloadService, Notifications.CHANNEL_SAVE)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            notifyManager().notify(Notifications.ID_SAVE, done)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SHARE = "extra_share"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_KIND = "extra_kind"
        const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, shareText: String) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_SHARE, shareText)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startDirect(context: Context, url: String, kind: String, title: String) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TITLE, title)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
