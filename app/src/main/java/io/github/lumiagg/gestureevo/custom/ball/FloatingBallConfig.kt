package io.github.lumiagg.gestureevo.custom.ball

import android.content.Context
import android.content.SharedPreferences

/**
 * 悬浮球配置数据模型与持久化管理
 */
data class FloatingBallConfig(
    var isEnabled: Boolean = true,
    var sizeDp: Int = 48,
    var alpha: Float = 0.85f,
    var autoSnapToEdge: Boolean = true,
    var posX: Int = 0,
    var posY: Int = 600,
    // 手势绑定的 actionLink，默认为常见系统全局动作（返回、任务列表、主屏、通知栏等）
    var clickAction: String = "gesture-evo://action/ACC_ACTION?accAction=1", // 返回
    var doubleClickAction: String = "gesture-evo://action/ACC_ACTION?accAction=3", // 最近任务
    var longPressAction: String = "", // 长按默认进入拖拽模式，如配置则可在特定时机触发
    var swipeUpAction: String = "gesture-evo://action/ACC_ACTION?accAction=2", // 桌面主页
    var swipeDownAction: String = "gesture-evo://action/ACC_ACTION?accAction=4", // 打开通知栏
    var swipeLeftAction: String = "",
    var swipeRightAction: String = ""
) {

    fun save(context: Context) {
        val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().apply {
            putBoolean(KEY_ENABLED, isEnabled)
            putInt(KEY_SIZE_DP, sizeDp)
            putFloat(KEY_ALPHA, alpha)
            putBoolean(KEY_AUTO_SNAP, autoSnapToEdge)
            putInt(KEY_POS_X, posX)
            putInt(KEY_POS_Y, posY)
            putString(KEY_CLICK_ACTION, clickAction)
            putString(KEY_DOUBLE_CLICK_ACTION, doubleClickAction)
            putString(KEY_LONG_PRESS_ACTION, longPressAction)
            putString(KEY_SWIPE_UP_ACTION, swipeUpAction)
            putString(KEY_SWIPE_DOWN_ACTION, swipeDownAction)
            putString(KEY_SWIPE_LEFT_ACTION, swipeLeftAction)
            putString(KEY_SWIPE_RIGHT_ACTION, swipeRightAction)
            apply()
        }
    }

    companion object {
        private const val PREF_NAME = "floating_ball_settings"
        private const val KEY_ENABLED = "key_ball_enabled"
        private const val KEY_SIZE_DP = "key_ball_size_dp"
        private const val KEY_ALPHA = "key_ball_alpha"
        private const val KEY_AUTO_SNAP = "key_ball_auto_snap"
        private const val KEY_POS_X = "key_ball_pos_x"
        private const val KEY_POS_Y = "key_ball_pos_y"
        private const val KEY_CLICK_ACTION = "key_ball_click_action"
        private const val KEY_DOUBLE_CLICK_ACTION = "key_ball_double_click_action"
        private const val KEY_LONG_PRESS_ACTION = "key_ball_long_press_action"
        private const val KEY_SWIPE_UP_ACTION = "key_ball_swipe_up_action"
        private const val KEY_SWIPE_DOWN_ACTION = "key_ball_swipe_down_action"
        private const val KEY_SWIPE_LEFT_ACTION = "key_ball_swipe_left_action"
        private const val KEY_SWIPE_RIGHT_ACTION = "key_ball_swipe_right_action"

        fun load(context: Context): FloatingBallConfig {
            val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return FloatingBallConfig(
                isEnabled = sp.getBoolean(KEY_ENABLED, true),
                sizeDp = sp.getInt(KEY_SIZE_DP, 48),
                alpha = sp.getFloat(KEY_ALPHA, 0.85f),
                autoSnapToEdge = sp.getBoolean(KEY_AUTO_SNAP, true),
                posX = sp.getInt(KEY_POS_X, 0),
                posY = sp.getInt(KEY_POS_Y, 600),
                clickAction = sp.getString(KEY_CLICK_ACTION, "gesture-evo://action/ACC_ACTION?accAction=1") ?: "",
                doubleClickAction = sp.getString(KEY_DOUBLE_CLICK_ACTION, "gesture-evo://action/ACC_ACTION?accAction=3") ?: "",
                longPressAction = sp.getString(KEY_LONG_PRESS_ACTION, "") ?: "",
                swipeUpAction = sp.getString(KEY_SWIPE_UP_ACTION, "gesture-evo://action/ACC_ACTION?accAction=2") ?: "",
                swipeDownAction = sp.getString(KEY_SWIPE_DOWN_ACTION, "gesture-evo://action/ACC_ACTION?accAction=4") ?: "",
                swipeLeftAction = sp.getString(KEY_SWIPE_LEFT_ACTION, "") ?: "",
                swipeRightAction = sp.getString(KEY_SWIPE_RIGHT_ACTION, "") ?: ""
            )
        }
    }
}
