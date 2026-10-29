package com.miaocun.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.miaocun.app.ClipActivity
import com.miaocun.app.core.DiagLog

/**
 * 无障碍服务：只做两件事
 * 1. 自动点「分享」→「复制链接 / 分享链接」，让悬浮球一键拿到分享链接；
 * 2. 调用系统截图接口，把当前画面交给悬浮窗保存。
 *
 * 不读取、不保存、不上传任何页面文本内容；调试日志只记录点了哪些按钮。
 */
class AutoShareService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /**
     * 分享面板里「复制链接」的候选文字，越靠前优先级越高。
     * 不同抖音版本的文案不一样：老版本是「复制链接」，新版本是「分享链接」。
     */
    private val copyLabels = listOf("复制链接", "分享链接", "拷贝链接", "复制", "链接")

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        DiagLog.log(this, "auto", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (current === this) current = null
        DiagLog.log(this, "auto", "无障碍服务已断开")
        super.onDestroy()
    }

    // ------------------------------------------------------------ 自动分享

    private fun startAutoShare() {
        if (running) return
        running = true
        DiagLog.log(this, "auto", "开始自动复制链接")
        toast("正在自动复制链接…")
        findShareButton(0)
    }

    private fun findShareButton(attempt: Int) {
        val root = rootInActiveWindow
        val node = findNode(root) { label -> label == "分享" }
            ?: findNode(root) { label -> label.contains("分享") && label.length <= 6 }

        if (node != null) {
            DiagLog.log(this, "auto", "点「" + labelOf(node) + "」打开分享面板")
            clickNode(node)
            handler.postDelayed({ pollCopy(0) }, 400)
            return
        }

        if (attempt >= 5) {
            dumpLabels(root, "找不到「分享」按钮")
            fail("没找到「分享」按钮，请手动分享后点「粘贴解析」")
            return
        }
        handler.postDelayed({ findShareButton(attempt + 1) }, 300)
    }

    private fun pollCopy(attempt: Int) {
        if (attempt > 20) {
            dumpLabels(rootInActiveWindow, "等不到复制入口")
            fail("没等到「复制链接」，请手动复制后点「粘贴解析」")
            return
        }

        val root = rootInActiveWindow
        var found: AccessibilityNodeInfo? = null
        for (label in copyLabels) {
            found = findNode(root) { text -> text == label }
            if (found != null) break
        }
        val node = found

        if (node == null) {
            if (attempt == 6 || attempt == 14) {
                dumpLabels(root, "还没出现复制入口 #" + attempt)
            }
            handler.postDelayed({ pollCopy(attempt + 1) }, 180)
            return
        }

        DiagLog.log(this, "auto", "点「" + labelOf(node) + "」")
        clickNode(node)
        // 点完不管是「复制链接」还是「分享链接」，都交给 ClipActivity 去读剪贴板；
        // 读不到会自己提示用户，不会静默失败。
        handler.postDelayed({ openClipActivity() }, 750)
    }

    private fun openClipActivity() {
        running = false
        runCatching {
            startActivity(
                Intent(this, ClipActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun fail(message: String) {
        running = false
        toast(message)
    }

    private fun toast(message: String) {
        handler.post {
            runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------ 节点工具

    private fun findNode(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        predicate: (String) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val label = labelOf(node)
        if (label.isNotEmpty() && predicate(label)) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            findNode(child, depth + 1, predicate)?.let { return it }
        }
        return null
    }

    private fun labelOf(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val text = node.text?.toString().orEmpty().trim()
        if (text.isNotEmpty()) return text
        return node.contentDescription?.toString().orEmpty().trim()
    }

    /** 把当前界面里和「复制/分享/链接」相关的文字打进日志，用来适配新版抖音。 */
    private fun dumpLabels(root: AccessibilityNodeInfo?, reason: String) {
        val labels = ArrayList<String>()
        collectLabels(root, labels, 0)
        val interesting = labels
            .filter { it.contains("复制") || it.contains("分享") || it.contains("链接") ||
                it.contains("保存") || it.contains("下载") || it.contains("相册") }
            .distinct()
            .take(30)
        DiagLog.log(this, "auto", reason + "；画面相关文字=" + interesting.joinToString(" / "))
    }

    private fun collectLabels(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int
    ) {
        if (node == null || depth > 40 || out.size > 500) return
        val label = labelOf(node)
        if (label.isNotEmpty()) out.add(label)
        for (i in 0 until node.childCount) {
            collectLabels(runCatching { node.getChild(i) }.getOrNull(), out, depth + 1)
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var cursor: AccessibilityNodeInfo? = node
        var guard = 0
        while (guard < 6) {
            val n = cursor ?: break
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cursor = runCatching { n.parent }.getOrNull()
            guard++
        }
        return runCatching {
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }.getOrDefault(false)
    }

    companion object {

        @Volatile
        private var current: AutoShareService? = null

        fun isConnected(): Boolean = current != null

        /** 触发一次「分享 → 复制链接」，返回是否已受理。 */
        fun triggerAutoShare(): Boolean {
            val service = current ?: return false
            service.handler.post { service.startAutoShare() }
            return true
        }

        /**
         * 截当前屏幕。返回是否已受理（Android 11 以下或无障碍未开启会返回 false）。
         */
        fun capture(callback: (Bitmap?) -> Unit): Boolean {
            val service = current ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            captureInternal(service, callback)
            return true
        }

        @RequiresApi(Build.VERSION_CODES.R)
        private fun captureInternal(service: AccessibilityService, callback: (Bitmap?) -> Unit) {
            runCatching {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = try {
                                val hardware = Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer,
                                    result.colorSpace
                                )
                                hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (t: Throwable) {
                                null
                            } finally {
                                runCatching { result.hardwareBuffer.close() }
                            }
                            callback(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            callback(null)
                        }
                    }
                )
            }.onFailure { callback(null) }
        }
    }
}
