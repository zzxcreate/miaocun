package com.miaocun.app.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import com.miaocun.app.ClipActivity
import com.miaocun.app.MainActivity
import com.miaocun.app.R
import com.miaocun.app.accessibility.AutoShareService
import com.miaocun.app.core.DiagLog
import com.miaocun.app.core.HistoryItem
import com.miaocun.app.core.HistoryStore
import com.miaocun.app.core.Notifications
import com.miaocun.app.core.Prefs
import com.miaocun.app.core.ScreenshotSaver
import com.miaocun.app.core.Util
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮球 + 操作面板。
 *
 * 「存」= 自动分享拿链接 → 解析 → 下载无水印原片；
 * 截图 = 无障碍 takeScreenshot 静默截屏，按 dp 裁剪后存相册。
 */
class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    private var detached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        Notifications.createChannels(this)
        startAsForeground()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ 通知

    private fun startAsForeground() {
        val notification = Notifications.floatNotification(this).build()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_FLOAT, notification, type)
    }

    // ------------------------------------------------------------ 悬浮球

    private fun addBubble() {
        if (bubble != null || detached) return

        val view = LayoutInflater.from(this).inflate(R.layout.view_bubble, null)
        val size = dp(54)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        val savedX = prefs.bubbleX
        val savedY = prefs.bubbleY
        val initialX = (if (savedX >= 0) savedX else screenW - size - dp(8))
            .coerceIn(0, (screenW - size).coerceAtLeast(0))
        val initialY = (if (savedY >= 0) savedY else screenH / 3)
            .coerceIn(0, (screenH - size).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        view.setOnTouchListener { v, event -> onBubbleTouch(v, event) }

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (added) {
            bubble = view
            bubbleParams = params
        } else {
            toast("悬浮窗无法显示，请检查「显示在其他应用上层」权限")
            stopSelf()
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                dragged = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                params.x = startX + dx
                params.y = startY + dy
                runCatching { windowManager.updateViewLayout(view, params) }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragged) snapToEdge(view, params) else togglePanel()
                return true
            }
        }
        return false
    }

    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val size = dp(54)
        val margin = dp(6)

        params.x = if (params.x + size / 2 < screenW / 2) margin else screenW - size - margin
        params.y = params.y.coerceIn(dp(40), (screenH - size - dp(40)).coerceAtLeast(dp(40)))
        runCatching { windowManager.updateViewLayout(view, params) }

        prefs.bubbleX = params.x
        prefs.bubbleY = params.y
    }

    // ------------------------------------------------------------ 面板

    private fun togglePanel() {
        if (panel != null) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val bp = bubbleParams ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.view_panel, null)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val panelW = view.measuredWidth.coerceAtLeast(dp(188))
        val panelH = view.measuredHeight.coerceAtLeast(dp(120))

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val margin = dp(8)
        val bubbleSize = dp(54)

        val params = WindowManager.LayoutParams(
            panelW,
            panelH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // 优先放气泡左侧，贴左边时改放右侧
        var x = bp.x - panelW - dp(6)
        if (x < margin) x = bp.x + bubbleSize + dp(6)
        x = x.coerceIn(margin, (screenW - panelW - margin).coerceAtLeast(margin))

        var y = bp.y + bubbleSize / 2 - panelH / 2
        y = y.coerceIn(margin, (screenH - panelH - margin).coerceAtLeast(margin))

        params.x = x
        params.y = y

        view.findViewById<View>(R.id.panelClose)?.setOnClickListener { hidePanel() }
        view.findViewById<View>(R.id.actionAuto)?.setOnClickListener { onAutoShare() }
        view.findViewById<View>(R.id.actionClip)?.setOnClickListener { onPaste() }
        view.findViewById<View>(R.id.actionShot)?.setOnClickListener { onScreenshot() }
        view.findViewById<View>(R.id.actionSettings)?.setOnClickListener { onSettings() }
        view.findViewById<View>(R.id.actionQuit)?.setOnClickListener { onQuit() }

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (added) panel = view
    }

    private fun hidePanel() {
        val view = panel ?: return
        panel = null
        runCatching { windowManager.removeView(view) }
    }

    // ------------------------------------------------------------ 动作

    private fun onAutoShare() {
        hidePanel()
        if (AutoShareService.triggerAutoShare()) {
            toast("正在自动复制链接…")
            return
        }
        // 失败只有两种可能：从来没开过，或者开过但服务被系统停掉了（设置里还勾着）。
        val listed = AutoShareService.isEnabledInSettings(this)
        DiagLog.log(this, "auto", "自动分享不可用：设置已勾选=$listed，服务已连接=false")
        toast(if (listed) "无障碍服务没在运行，请在设置里关掉再打开一次" else "请先开启「秒存」无障碍服务")
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun onPaste() {
        hidePanel()
        runCatching {
            startActivity(
                Intent(this, ClipActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun onScreenshot() {
        hidePanel()
        val bubbleView = bubble ?: return
        val params = bubbleParams
        // 直接把窗口从 WindowManager 移除：只把 View 设成 GONE 时，
        // 合成层可能还留着它的残影，截图会把悬浮球一起截进去。
        bubble = null
        runCatching { windowManager.removeView(bubbleView) }
        DiagLog.log(this, "shot", "已移除悬浮球，准备截图")

        handler.postDelayed({
            val accepted = AutoShareService.capture { bitmap ->
                restoreBubble(bubbleView, params)
                if (bitmap == null) {
                    DiagLog.log(
                        this@FloatingService,
                        "shot",
                        "截图返回空（需要 Android 11+ 且无障碍已连接）"
                    )
                    toast("截图失败：需要 Android 11+ 并已开启无障碍服务")
                } else {
                    DiagLog.log(
                        this@FloatingService,
                        "shot",
                        "截图成功 " + bitmap.width + "x" + bitmap.height
                    )
                    saveScreenshot(bitmap)
                }
            }
            if (!accepted) {
                restoreBubble(bubbleView, params)
                DiagLog.log(this@FloatingService, "shot", "无障碍未连接，无法截图")
                toast("需要先开启无障碍服务（Android 11+ 支持静默截图）")
            }
        }, 450)
    }

    /** 截完图把悬浮球放回去，位置沿用被移除前的参数。 */
    private fun restoreBubble(view: View, params: WindowManager.LayoutParams?) {
        if (detached || bubble != null) return
        if (params != null) {
            val ok = runCatching { windowManager.addView(view, params) }.isSuccess
            if (ok) {
                bubble = view
                bubbleParams = params
                return
            }
        }
        addBubble()
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            val uri = ScreenshotSaver.save(this@FloatingService, bitmap, prefs)
            runCatching { bitmap.recycle() }
            withContext(Dispatchers.Main) {
                if (uri != null) {
                    HistoryStore.add(
                        this@FloatingService,
                        HistoryItem(
                            title = "屏幕截图",
                            uri = uri.toString(),
                            time = System.currentTimeMillis(),
                            isVideo = false
                        )
                    )
                    toast("已保存到相册「秒存」")
                } else {
                    toast("保存失败，请检查存储空间")
                }
            }
        }
    }

    private fun onSettings() {
        hidePanel()
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun onQuit() {
        prefs.overlayEnabled = false
        hidePanel()
        stopSelf()
    }

    // ------------------------------------------------------------ 工具

    private fun toast(message: String) {
        handler.post {
            runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        detached = true
        handler.removeCallbacksAndMessages(null)
        hidePanel()
        bubble?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        bubble = null
        bubbleParams = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.miaocun.app.action.STOP_FLOAT"
    }
}
