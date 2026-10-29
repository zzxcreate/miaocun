package com.miaocun.app.core

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/**
 * 屏幕截图兜底：按设置里的 dp 裁剪后再存相册。
 */
object ScreenshotSaver {

    fun save(context: Context, source: Bitmap, prefs: Prefs): Uri? {
        val density = context.resources.displayMetrics.density

        val left = (prefs.cropLeft * density).toInt().coerceAtLeast(0)
        val top = (prefs.cropTop * density).toInt().coerceAtLeast(0)
        val right = (prefs.cropRight * density).toInt().coerceAtLeast(0)
        val bottom = (prefs.cropBottom * density).toInt().coerceAtLeast(0)

        val name = "秒存_截图_" + Util.timestamp() + "_" + Util.randomSuffix() + ".png"

        val width = source.width - left - right
        val height = source.height - top - bottom
        if (width <= 16 || height <= 16) {
            // 参数不合理就整屏保存，避免裁出空图
            return MediaSaver.saveBitmap(context, source, name)
        }

        val cropped = try {
            Bitmap.createBitmap(source, left, top, width, height)
        } catch (t: Throwable) {
            return MediaSaver.saveBitmap(context, source, name)
        }
        return MediaSaver.saveBitmap(context, cropped, name)
    }
}
