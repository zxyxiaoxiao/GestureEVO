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
}
