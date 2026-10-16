package io.github.lumiagg.gestureevo.custom.ball

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * 悬浮球配置数据模型与持久化管理
 */
data class FloatingBallConfig(
    // 基础开关
    var isEnabled: Boolean = true,
    var autoSnapToEdge: Boolean = true,

    // 【分组1：图标】
    var sizePercent: Int = 100, // 默认 100%
    var innerRatioPercent: Int = 100, // 内部占比 0~100%，默认 100%
    var iconAlphaPercent: Int = 50, // 透明度 0~100%，默认 50%
    var posX: Int = 0,
    var posY: Int = 600,
    var positionMode: Int = 0, // 0: 自定义位置, 1: 始终跟随触发器
    var followTriggerTag: String = "", // 跟随触发器的 tag，为空时跟随全部触发器
    var iconStyleType: Int = 0, // 0: 预设圆形模板, 1: 自定义图片
    var customIconPath: String = "", // 自定义图片路径
    var borderWidthDp: Float = 2f, // 边框粗细 dp
    var borderColor: Int = Color.parseColor("#44888888"), // 边框颜色
    var cornerRadiusDp: Float = 24f, // 圆角大小 dp (对于48dp圆，24dp即正圆)

    // 【分组2：隐藏设置】
    var manualHideEnabled: Boolean = false, // 启用手动隐藏，默认关闭
    var manualHideWakeType: Int = 0, // 0: 通知点击唤起, 1: 手势唤起
    var lockScreenHide: Boolean = true, // 锁屏悬浮球：关闭 (即锁屏时隐藏)
    var autoHideApps: Set<String> = emptySet(), // 自动隐藏 APP 黑名单包名列表

    // 【分组3：手势动作绑定】
    var clickAction: String = "gesture-evo://action/ACC_ACTION?accAction=1", // 返回
    var doubleClickAction: String = "gesture-evo://action/ACC_ACTION?accAction=3", // 最近任务
    var longPressAction: String = "", // 长按
    var swipeUpAction: String = "gesture-evo://action/ACC_ACTION?accAction=2", // 桌面主页
    var swipeDownAction: String = "gesture-evo://action/ACC_ACTION?accAction=4", // 打开通知栏
    var swipeLeftAction: String = "",
    var swipeRightAction: String = ""
) {

    /**
     * 计算悬浮球在屏幕上的实际像素大小（基准 50dp，根据 sizePercent 缩放）
     */
    fun getActualSizeDp(): Float {
        val baseDp = 50f
        val factor = (sizePercent.coerceIn(10, 200)) / 100f
        return baseDp * factor
    }

    /**
     * 获取图标透明度浮点值 (0.0f ~ 1.0f)
     */
    fun getIconAlpha(): Float {
        return (iconAlphaPercent.coerceIn(0, 100)) / 100f
    }

    fun save(context: Context) {
        val sp: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().apply {
            putBoolean(KEY_ENABLED, isEnabled)
            putBoolean(KEY_AUTO_SNAP, autoSnapToEdge)

            // 图标与位置
            putInt(KEY_SIZE_PERCENT, sizePercent)
            putInt(KEY_INNER_RATIO_PERCENT, innerRatioPercent)
            putInt(KEY_ICON_ALPHA_PERCENT, iconAlphaPercent)
            putInt(KEY_POS_X, posX)
            putInt(KEY_POS_Y, posY)
            putInt(KEY_POS_MODE, positionMode)
            putString(KEY_FOLLOW_TRIGGER_TAG, followTriggerTag)
            putInt(KEY_ICON_STYLE_TYPE, iconStyleType)
            putString(KEY_CUSTOM_ICON_PATH, customIconPath)
            putFloat(KEY_BORDER_WIDTH_DP, borderWidthDp)
            putInt(KEY_BORDER_COLOR, borderColor)
            putFloat(KEY_CORNER_RADIUS_DP, cornerRadiusDp)

            // 隐藏
            putBoolean(KEY_MANUAL_HIDE_ENABLED, manualHideEnabled)
            putInt(KEY_MANUAL_HIDE_WAKE_TYPE, manualHideWakeType)
            putBoolean(KEY_LOCK_SCREEN_HIDE, lockScreenHide)
            putStringSet(KEY_AUTO_HIDE_APPS, autoHideApps)

            // 动作
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
        private const val KEY_AUTO_SNAP = "key_ball_auto_snap"

        private const val KEY_SIZE_PERCENT = "key_ball_size_percent"
        private const val KEY_INNER_RATIO_PERCENT = "key_ball_inner_ratio_percent"
        private const val KEY_ICON_ALPHA_PERCENT = "key_ball_icon_alpha_percent"
        private const val KEY_POS_X = "key_ball_pos_x"
        private const val KEY_POS_Y = "key_ball_pos_y"
        private const val KEY_POS_MODE = "key_ball_pos_mode"
        private const val KEY_FOLLOW_TRIGGER_TAG = "key_ball_follow_trigger_tag"
        private const val KEY_ICON_STYLE_TYPE = "key_ball_icon_style_type"
        private const val KEY_CUSTOM_ICON_PATH = "key_ball_custom_icon_path"
        private const val KEY_BORDER_WIDTH_DP = "key_ball_border_width_dp"
        private const val KEY_BORDER_COLOR = "key_ball_border_color"
        private const val KEY_CORNER_RADIUS_DP = "key_ball_corner_radius_dp"

        private const val KEY_MANUAL_HIDE_ENABLED = "key_ball_manual_hide_enabled"
        private const val KEY_MANUAL_HIDE_WAKE_TYPE = "key_ball_manual_hide_wake_type"
        private const val KEY_LOCK_SCREEN_HIDE = "key_ball_lock_screen_hide"
        private const val KEY_AUTO_HIDE_APPS = "key_ball_auto_hide_apps"

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
                autoSnapToEdge = sp.getBoolean(KEY_AUTO_SNAP, true),

                sizePercent = sp.getInt(KEY_SIZE_PERCENT, 100),
                innerRatioPercent = sp.getInt(KEY_INNER_RATIO_PERCENT, 100),
                iconAlphaPercent = sp.getInt(KEY_ICON_ALPHA_PERCENT, 50),
                posX = sp.getInt(KEY_POS_X, 0),
                posY = sp.getInt(KEY_POS_Y, 600),
                positionMode = sp.getInt(KEY_POS_MODE, 0),
                followTriggerTag = sp.getString(KEY_FOLLOW_TRIGGER_TAG, "") ?: "",
                iconStyleType = sp.getInt(KEY_ICON_STYLE_TYPE, 0),
                customIconPath = sp.getString(KEY_CUSTOM_ICON_PATH, "") ?: "",
                borderWidthDp = sp.getFloat(KEY_BORDER_WIDTH_DP, 2f),
                borderColor = sp.getInt(KEY_BORDER_COLOR, Color.parseColor("#44888888")),
                cornerRadiusDp = sp.getFloat(KEY_CORNER_RADIUS_DP, 24f),

                manualHideEnabled = sp.getBoolean(KEY_MANUAL_HIDE_ENABLED, false),
                manualHideWakeType = sp.getInt(KEY_MANUAL_HIDE_WAKE_TYPE, 0),
                lockScreenHide = sp.getBoolean(KEY_LOCK_SCREEN_HIDE, true),
                autoHideApps = sp.getStringSet(KEY_AUTO_HIDE_APPS, emptySet()) ?: emptySet(),

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
