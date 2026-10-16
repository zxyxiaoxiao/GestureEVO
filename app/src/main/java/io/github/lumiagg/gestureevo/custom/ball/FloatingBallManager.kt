package io.github.lumiagg.gestureevo.custom.ball

import android.content.Context
import android.provider.Settings

/**
 * 悬浮球生命周期与前台服务调度管理器（单例桥接器）
 */
object FloatingBallManager {

    var config: FloatingBallConfig = FloatingBallConfig()
        private set

    val isShowing: Boolean
        get() = FloatingBallService.instance != null

    /**
     * 在无障碍服务或核心组件初始化时联动前台服务
     */
    fun init(context: Context) {
        this.config = FloatingBallConfig.load(context)
        if (config.isEnabled && Settings.canDrawOverlays(context)) {
            FloatingBallService.start(context)
        }
    }

    /**
     * 刷新悬浮球
     */
    fun refresh(context: Context) {
        this.config = FloatingBallConfig.load(context)
        FloatingBallService.notifyConfigChanged(context)
    }

    /**
     * 隐藏悬浮球
     */
    fun hide(context: Context) {
        FloatingBallService.stop(context)
    }

    /**
     * 回收销毁
     */
    fun destroy(context: Context? = null) {
        context?.let {
            FloatingBallService.stop(it)
        }
    }

    /**
     * 前台包名变更联动（自动隐藏黑名单）
     */
    fun onForegroundPackageChanged(context: Context, pkgName: String) {
        FloatingBallService.notifyForegroundApp(context, pkgName)
    }

    /**
     * 触发器按压联动：在指定屏幕坐标显示悬浮球
     */
    fun showAt(context: Context, rawX: Float, rawY: Float) {
        FloatingBallService.instance?.showAtPoint(rawX, rawY)
    }

    /**
     * 触发器按压移动联动：跟随手指更新坐标
     */
    fun updateFollowPosition(context: Context, rawX: Float, rawY: Float) {
        FloatingBallService.instance?.updateFollowPoint(rawX, rawY)
    }

    /**
     * 触发器手指释放联动：悬浮球归位并隐藏
     */
    fun resetAndHideFollowBall(context: Context) {
        FloatingBallService.instance?.resetAndHideTriggerFollow()
    }
}
