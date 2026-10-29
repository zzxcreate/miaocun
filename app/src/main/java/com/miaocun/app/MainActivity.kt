package com.miaocun.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.miaocun.app.accessibility.AutoShareService
import com.miaocun.app.core.DiagLog
import com.miaocun.app.core.HistoryStore
import com.miaocun.app.core.Prefs
import com.miaocun.app.overlay.FloatingService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var suppressSwitch = false

    private lateinit var tvOverlayStatus: TextView
    private lateinit var btnOverlayPermission: Button
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnNotification: Button
    private lateinit var switchOverlay: SwitchCompat
    private lateinit var switchAutomation: SwitchCompat
    private lateinit var etCropTop: EditText
    private lateinit var etCropBottom: EditText
    private lateinit var etCropLeft: EditText
    private lateinit var etCropRight: EditText
    private lateinit var btnSaveCrop: Button
    private lateinit var etApi: EditText
    private lateinit var btnSaveApi: Button
    private lateinit var btnClip: Button
    private lateinit var btnClearHistory: Button
    private lateinit var layoutHistory: LinearLayout
    private lateinit var tvDiag: TextView
    private lateinit var btnCopyDiag: Button
    private lateinit var btnClearDiag: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        bindViews()
        fillFields()
        attachListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderHistory()
        renderDiag()
    }

    private fun bindViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        btnNotification = findViewById(R.id.btnNotification)
        switchOverlay = findViewById(R.id.switchOverlay)
        switchAutomation = findViewById(R.id.switchAutomation)
        etCropTop = findViewById(R.id.etCropTop)
        etCropBottom = findViewById(R.id.etCropBottom)
        etCropLeft = findViewById(R.id.etCropLeft)
        etCropRight = findViewById(R.id.etCropRight)
        btnSaveCrop = findViewById(R.id.btnSaveCrop)
        etApi = findViewById(R.id.etApi)
        btnSaveApi = findViewById(R.id.btnSaveApi)
        btnClip = findViewById(R.id.btnClip)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        layoutHistory = findViewById(R.id.layoutHistory)
        tvDiag = findViewById(R.id.tvDiag)
        btnCopyDiag = findViewById(R.id.btnCopyDiag)
        btnClearDiag = findViewById(R.id.btnClearDiag)
    }

    private fun fillFields() {
        etCropTop.setText(prefs.cropTop.toString())
        etCropBottom.setText(prefs.cropBottom.toString())
        etCropLeft.setText(prefs.cropLeft.toString())
        etCropRight.setText(prefs.cropRight.toString())
        etApi.setText(prefs.api)
    }

    private fun attachListeners() {
        btnOverlayPermission.setOnClickListener { openOverlaySettings() }
        btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        btnNotification.setOnClickListener { openNotificationSettings() }

        switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) {
                if (Settings.canDrawOverlays(this)) {
                    prefs.overlayEnabled = true
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, FloatingService::class.java)
                    )
                } else {
                    suppressSwitch = true
                    switchOverlay.isChecked = false
                    suppressSwitch = false
                    toast("请先允许「显示在其他应用上层」")
                    openOverlaySettings()
                }
            } else {
                prefs.overlayEnabled = false
                stopService(Intent(this, FloatingService::class.java))
            }
        }

        switchAutomation.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitch) prefs.automation = checked
        }

        btnSaveCrop.setOnClickListener { saveCrop() }
        btnSaveApi.setOnClickListener { saveApi() }

        btnClip.setOnClickListener {
            runCatching { startActivity(Intent(this, ClipActivity::class.java)) }
        }

        btnClearHistory.setOnClickListener {
            HistoryStore.clear(this)
            renderHistory()
            toast("已清空记录")
        }

        btnCopyDiag.setOnClickListener { copyDiag() }

        btnClearDiag.setOnClickListener {
            DiagLog.clear(this)
            renderDiag()
            toast("已清空日志")
        }
    }

    // ------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        setStatus(tvOverlayStatus, overlayOk, "已开启", "未开启 · 点右侧去开启")

        val accessibilityOk = isAccessibilityEnabled()
        setStatus(
            tvAccessibilityStatus,
            accessibilityOk,
            "已开启",
            "未开启 · 自动分享与截图需要它"
        )

        val notificationOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        setStatus(
            tvNotificationStatus,
            notificationOk,
            "已开启",
            "未开启 · 看不到保存进度"
        )

        suppressSwitch = true
        switchOverlay.isChecked = prefs.overlayEnabled
        switchAutomation.isChecked = prefs.automation
        suppressSwitch = false
    }

    private fun setStatus(view: TextView, ok: Boolean, okText: String, badText: String) {
        view.text = if (ok) okText else badText
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn)
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AutoShareService.isConnected()) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { part ->
            part.contains(packageName) && part.contains("AutoShareService")
        }
    }

    // ------------------------------------------------------------ 记录

    private fun renderHistory() {
        layoutHistory.removeAllViews()

        val items = HistoryStore.list(this)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有保存记录"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 12f
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            layoutHistory.addView(empty)
            return
        }

        val format = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        items.take(8).forEach { item ->
            val row = TextView(this).apply {
                text = format.format(Date(item.time)) + "  " + item.title.take(30)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 12f
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_row)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { openMedia(item.uri) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(6)
            row.layoutParams = lp
            layoutHistory.addView(row)
        }
    }

    private fun openMedia(uriString: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("没有能打开它的应用") }
    }

    // ------------------------------------------------------------ 设置跳转

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                .onFailure { toast("请到系统设置里手动开启悬浮窗权限") }
        }
    }

    private fun openAccessibilitySettings() {
        toast("在列表里找到「秒存 · 自动分享」并开启")
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast("打不开无障碍设置，请手动进入") }
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("请到系统设置里打开通知") }
    }

    // ------------------------------------------------------------ 保存设置

    private fun saveCrop() {
        prefs.cropTop = etCropTop.text.toString().toIntOrNull() ?: 0
        prefs.cropBottom = etCropBottom.text.toString().toIntOrNull() ?: 0
        prefs.cropLeft = etCropLeft.text.toString().toIntOrNull() ?: 0
        prefs.cropRight = etCropRight.text.toString().toIntOrNull() ?: 0
        toast("裁剪参数已保存")
    }

    private fun saveApi() {
        prefs.api = etApi.text.toString()
        toast(if (prefs.api.isEmpty()) "已恢复内置解析" else "接口已保存")
    }

    // ------------------------------------------------------------ 诊断

    private fun renderDiag() {
        val text = DiagLog.read(this)
        tvDiag.text = if (text.isEmpty()) "暂无日志" else text.takeLast(1600)
    }

    private fun copyDiag() {
        val text = DiagLog.read(this)
        if (text.isEmpty()) {
            toast("暂无日志")
            return
        }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("秒存日志", text))
            toast("日志已复制，直接粘贴发我即可")
        }.onFailure { toast("复制失败") }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
