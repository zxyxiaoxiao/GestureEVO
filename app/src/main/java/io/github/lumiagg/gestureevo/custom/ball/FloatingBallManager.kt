package io.github.lumiagg.gestureevo.custom.ball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 悬浮球生命周期与窗口布局管理器（单例）
 */
@SuppressLint("StaticFieldLeak")
object FloatingBallManager {
    private const val TAG = "FloatingBallManager"

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var ballView: FloatingBallView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    var config: FloatingBallConfig = FloatingBallConfig()
        private set

    var isShowing: Boolean = false
        private set

    /**
     * 在无障碍服务或核心组件中初始化
     */
    fun init(context: Context) {
        this.appContext = context.applicationContext
        this.windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        this.config = FloatingBallConfig.load(context)

        if (config.isEnabled) {
            show()
        }
    }

    /**
     * 展示悬浮球
     */
    fun show() {
        if (isShowing) return
        val context = appContext ?: return
        val wm = windowManager ?: return

        val size = config.sizeDp.dp2px.toInt()
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.START or Gravity.TOP
            width = size
            height = size
            x = config.posX
            y = config.posY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        this.layoutParams = params

        val view = FloatingBallView(context, config).apply {
            onActionTrigger = { actionLink ->
                dispatchAction(actionLink)
            }
            onDragMove = { deltaX, deltaY ->
                updatePositionDelta(deltaX, deltaY)
            }
            onDragReleased = {
                if (config.autoSnapToEdge) {
                    snapToEdge()
                } else {
                    saveCurrentPosition()
                }
            }
        }
        this.ballView = view

        try {
            wm.addView(view, params)
            isShowing = true
            Log.d(TAG, "FloatingBall added to WindowManager at (${params.x}, ${params.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add FloatingBallView to WindowManager", e)
        }
    }

    /**
     * 隐藏悬浮球
     */
    fun hide() {
        if (!isShowing) return
        try {
            ballView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing FloatingBallView", e)
        } finally {
            ballView = null
            layoutParams = null
            isShowing = false
        }
    }

    /**
     * 回收销毁
     */
    fun destroy() {
        hide()
        appContext = null
        windowManager = null
    }

    /**
     * 根据拖动增量更新悬浮球屏幕位置
     */
    private fun updatePositionDelta(deltaX: Float, deltaY: Float) {
        val params = layoutParams ?: return
        val view = ballView ?: return
        val wm = windowManager ?: return

        params.x += deltaX.toInt()
        params.y += deltaY.toInt()

        // 限制在屏幕可见范围内
        val context = appContext ?: return
        val screenHeight = ScreenUtil.getScreenHeight(context)
        val size = config.sizeDp.dp2px.toInt()
        params.y = params.y.coerceIn(0, (screenHeight - size).coerceAtLeast(0))

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update layout position", e)
        }
    }

    /**
     * 自动贴边平滑吸附到最近屏幕边缘
     */
    private fun snapToEdge() {
        val params = layoutParams ?: return
        val view = ballView ?: return
        val wm = windowManager ?: return
        val context = appContext ?: return

        val screenWidth = ScreenUtil.getScreenWidth(context)
        val ballSize = config.sizeDp.dp2px.toInt()
        val currentX = params.x
        val centerX = currentX + ballSize / 2

        // 计算靠左或靠右的目标位置
        val targetX = if (centerX < screenWidth / 2) {
            0
        } else {
            (screenWidth - ballSize).coerceAtLeast(0)
        }

        ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Snap animation update error", e)
                }
            }
            start()
        }

        config.posX = targetX
        config.posY = params.y
        config.save(context)
    }

    private fun saveCurrentPosition() {
        val params = layoutParams ?: return
        val context = appContext ?: return
        config.posX = params.x
        config.posY = params.y
        config.save(context)
    }

    /**
     * 核心动作派发：复用项目中现有的 ActionProxy 执行手势关联动作
     */
    private fun dispatchAction(actionLink: String) {
        if (actionLink.isBlank()) return
        val context = appContext ?: return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d(TAG, "dispatchAction -> $actionLink")
                ActionProxy(context, actionLink).execute()
            } catch (e: Exception) {
                Log.e(TAG, "dispatchAction failed for link: $actionLink", e)
            }
        }
    }
}
