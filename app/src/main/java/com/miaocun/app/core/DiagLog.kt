package com.miaocun.app.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简诊断日志。
 *
 * 只记录「秒存」自己的运行步骤与错误（解析到几条、点了哪个按钮、失败原因），
 * 不记录任何页面内容。文件是应用私有目录下的 miaocun.log，上限 64KB，
 * 在设置页可以一键复制出来发给开发者。
 */
object DiagLog {

    private const val FILE_NAME = "miaocun.log"
    private const val MAX_BYTES = 64L * 1024L

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
                    file.writeText(keep)
                }
                file.appendText("${timeFormat.format(Date())} [$tag] $message\n")
            }
        }
    }

    fun read(context: Context): String = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (file.exists()) file.readText() else ""
    }.getOrDefault("")

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
        }
    }
}
