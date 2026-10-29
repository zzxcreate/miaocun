package com.miaocun.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miaocun.app.download.DownloadService

/**
 * 从抖音「分享 → 复制链接」之外的系统分享面板进来，也能直接保存。
 */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank() || !text.contains("http", ignoreCase = true)) {
            runCatching { Toast.makeText(this, "没有收到链接", Toast.LENGTH_SHORT).show() }
            finish()
            return
        }

        DownloadService.start(this, text)
        runCatching { Toast.makeText(this, "开始解析并保存…", Toast.LENGTH_SHORT).show() }
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
