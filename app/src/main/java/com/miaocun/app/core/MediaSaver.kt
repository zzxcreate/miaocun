package com.miaocun.app.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream

/**
 * 统一走 MediaStore：不需要存储权限，保存到相册「秒存」目录。
 */
object MediaSaver {

    fun videoCollection(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun imageCollection(): Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun relativePath(isVideo: Boolean): String =
        if (isVideo) "${Environment.DIRECTORY_MOVIES}/秒存"
        else "${Environment.DIRECTORY_PICTURES}/秒存"

    fun createEntry(
        context: Context,
        isVideo: Boolean,
        fileName: String,
        mime: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(isVideo))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (isVideo) videoCollection() else imageCollection()
        return runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
    }

    fun finishEntry(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    /**
     * 下载完成后才知道真实图片格式（webp / heic 很常见），这里在 finishEntry 之前改名改 mime，
     * 免得相册里出现“后缀 jpg 其实是 webp”打不开的情况。
     */
    fun updateMeta(context: Context, uri: Uri, fileName: String, mime: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        val uri = createEntry(context, false, fileName, "image/png") ?: return null
        var ok = false
        try {
            val stream: OutputStream? = context.contentResolver.openOutputStream(uri)
            if (stream == null) {
                delete(context, uri)
                return null
            }
            stream.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            ok = true
        } catch (t: Throwable) {
            ok = false
        }
        if (!ok) {
            delete(context, uri)
            return null
        }
        finishEntry(context, uri)
        return uri
    }
}
