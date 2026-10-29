package com.miaocun.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Util {

    fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun sanitize(name: String, max: Int = 40): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ").trim()
        if (cleaned.isEmpty()) return "未命名"
        return if (cleaned.length > max) cleaned.substring(0, max) else cleaned
    }

    fun randomSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(4)
        repeat(4) { sb.append(chars[kotlin.random.Random.nextInt(chars.length)]) }
        return sb.toString()
    }
}
