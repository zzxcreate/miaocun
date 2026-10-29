package com.miaocun.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 全部本地配置，走 SharedPreferences，不联网、不上报。
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY, false)
        set(value) {
            sp.edit().putBoolean(KEY_OVERLAY, value).apply()
        }

    var automation: Boolean
        get() = sp.getBoolean(KEY_AUTOMATION, true)
        set(value) {
            sp.edit().putBoolean(KEY_AUTOMATION, value).apply()
        }

    var cropTop: Int
        get() = sp.getInt(KEY_CROP_TOP, 0)
        set(value) {
            sp.edit().putInt(KEY_CROP_TOP, value.coerceAtLeast(0)).apply()
        }

    var cropBottom: Int
        get() = sp.getInt(KEY_CROP_BOTTOM, 0)
        set(value) {
            sp.edit().putInt(KEY_CROP_BOTTOM, value.coerceAtLeast(0)).apply()
        }

    var cropLeft: Int
        get() = sp.getInt(KEY_CROP_LEFT, 0)
        set(value) {
            sp.edit().putInt(KEY_CROP_LEFT, value.coerceAtLeast(0)).apply()
        }

    var cropRight: Int
        get() = sp.getInt(KEY_CROP_RIGHT, 0)
        set(value) {
            sp.edit().putInt(KEY_CROP_RIGHT, value.coerceAtLeast(0)).apply()
        }

    var api: String
        get() = sp.getString(KEY_API, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_API, value.trim()).apply()
        }

    var bubbleX: Int
        get() = sp.getInt(KEY_BUBBLE_X, -1)
        set(value) {
            sp.edit().putInt(KEY_BUBBLE_X, value).apply()
        }

    var bubbleY: Int
        get() = sp.getInt(KEY_BUBBLE_Y, -1)
        set(value) {
            sp.edit().putInt(KEY_BUBBLE_Y, value).apply()
        }

    companion object {
        private const val FILE = "miaocun_prefs"
        private const val KEY_OVERLAY = "overlay_enabled"
        private const val KEY_AUTOMATION = "automation"
        private const val KEY_CROP_TOP = "crop_top"
        private const val KEY_CROP_BOTTOM = "crop_bottom"
        private const val KEY_CROP_LEFT = "crop_left"
        private const val KEY_CROP_RIGHT = "crop_right"
        private const val KEY_API = "custom_api"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
