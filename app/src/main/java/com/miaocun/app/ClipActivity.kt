package com.miaocun.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miaocun.app.download.DownloadService

/**
 * 透明 Activity：读一次剪贴板就退出。
 * 由悬浮窗或无障碍服务拉起，用来拿到「分享 → 复制链接」的结果。
 */
class ClipActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        Handler(Looper.getMainLooper()).postDelayed({ readClipboard() }, 260)
    }

    private fun readClipboard() {
        val text = runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0).coerceToText(this)?.toString()
        }.getOrNull()

        if (text.isNullOrBlank() || !text.contains("http", ignoreCase = true)) {
            toast("剪贴板里没有可用链接，请先点「复制链接」")
            finish()
            return
        }

        DownloadService.start(this, text)
        toast("开始解析并保存…")
        finish()
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
