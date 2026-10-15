package io.github.lumiagg.gestureevo.custom.ball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 类似 FV 悬浮球体验的交互 View
 * 具备精致圆形绘制、微位移手势指示、长按拖动以及单击/双击区分逻辑
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private val config: FloatingBallConfig
) : FrameLayout(context) {

    // 动作触发回调
    var onActionTrigger: ((actionLink: String) -> Unit)? = null
    // 拖动偏移回调（用于更新 WindowManager 坐标）
    var onDragMove: ((deltaX: Float, deltaY: Float) -> Unit)? = null
    // 拖动松手回调（触发贴边动画）
    var onDragReleased: (() -> Unit)? = null

    // 绘制画笔
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f.dp2px
        color = Color.parseColor("#44888888")
    }
    private val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33000000")
    }

    // 交互状态
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val swipeThreshold = 28f.dp2px
    private val mainHandler = Handler(Looper.getMainLooper())

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private var isDragging = false
    private var isSwiping = false

    // 悬浮球本体在原位的弹性微位移
    private var ballOffsetX = 0f
    private var ballOffsetY = 0f
    private var currentScale = 1.0f

    // 双击检测计时器与状态
    private var clickCount = 0
    private val doubleClickTimeout = 220L
    private val singleClickRunnable = Runnable {
        clickCount = 0
        VibratorUtils.vibrator(context, false)
        onActionTrigger?.invoke(config.clickAction)
    }

    // 长按检测计时器（380ms 无大位移进入拖动位置模式）
    private val longPressTimeout = 380L
    private val longPressRunnable = Runnable {
        isDragging = true
        VibratorUtils.vibrator(context, false)
        // 放大悬浮球提供即时触觉与视觉反馈
        animateScale(1.15f)
        if (config.longPressAction.isNotBlank()) {
            onActionTrigger?.invoke(config.longPressAction)
        }
    }

    init {
        setWillNotDraw(false)
        alpha = config.alpha
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f + ballOffsetX
        val cy = height / 2f + ballOffsetY
        val radius = (minOf(width, height) / 2f - 4f.dp2px) * currentScale

        // 绘制半透明磨砂质感外圈底色
        backgroundPaint.color = Color.argb(220, 245, 245, 248)
        canvas.drawCircle(cx, cy, radius, backgroundPaint)

        // 绘制微妙边缘高光圈
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 绘制中心呼吸感圆核（类似 FV 悬浮球的内芯）
        canvas.drawCircle(cx, cy, radius * 0.42f, innerCorePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                isDragging = false
                isSwiping = false

                // 安排长按判定
                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx, dy)

                if (!isDragging) {
                    // 若有较明显位移，取消长按判定
                    if (dist > touchSlop) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        isSwiping = true

                        // 阻尼弹性拉扯微位移效果（最大偏移 12dp）
                        val maxOffset = 12f.dp2px
                        val factor = minOf(1.0f, dist / (swipeThreshold * 2f))
                        val angle = kotlin.math.atan2(dy, dx)
                        ballOffsetX = (factor * maxOffset * kotlin.math.cos(angle))
                        ballOffsetY = (factor * maxOffset * kotlin.math.sin(angle))
                        invalidate()
                    }
                } else {
                    // 已进入拖动模式：更新悬浮球在屏幕上的绝对位置
                    val deltaX = event.rawX - lastRawX
                    val deltaY = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onDragMove?.invoke(deltaX, deltaY)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)

                if (isDragging) {
                    // 结束拖拽
                    isDragging = false
                    animateScale(1.0f)
                    onDragReleased?.invoke()
                } else {
                    // 弹簧回弹微位移
                    resetBallOffset()

                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    val totalDist = hypot(totalDx, totalDy)

                    if (totalDist >= swipeThreshold) {
                        // 判定滑动方向
                        handleSwipeGesture(totalDx, totalDy)
                    } else if (totalDist < touchSlop) {
                        // 判定单击与双击
                        handleClickGesture()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleSwipeGesture(dx: Float, dy: Float) {
        val action = if (abs(dx) > abs(dy)) {
            if (dx < 0) config.swipeLeftAction else config.swipeRightAction
        } else {
            if (dy < 0) config.swipeUpAction else config.swipeDownAction
        }

        if (action.isNotBlank()) {
            VibratorUtils.vibrator(context, false)
            onActionTrigger?.invoke(action)
        }
    }

    private fun handleClickGesture() {
        clickCount++
        if (clickCount == 1) {
            mainHandler.postDelayed(singleClickRunnable, doubleClickTimeout)
        } else if (clickCount >= 2) {
            mainHandler.removeCallbacks(singleClickRunnable)
            clickCount = 0
            if (config.doubleClickAction.isNotBlank()) {
                VibratorUtils.vibrator(context, true)
                onActionTrigger?.invoke(config.doubleClickAction)
            }
        }
    }

    private fun resetBallOffset() {
        if (ballOffsetX != 0f || ballOffsetY != 0f) {
            val anim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 180
                interpolator = OvershootInterpolator(1.5f)
                val startX = ballOffsetX
                val startY = ballOffsetY
                addUpdateListener { va ->
                    val fraction = va.animatedValue as Float
                    ballOffsetX = startX * fraction
                    ballOffsetY = startY * fraction
                    invalidate()
                }
            }
            anim.start()
        }
    }

    private fun animateScale(targetScale: Float) {
        ValueAnimator.ofFloat(currentScale, targetScale).apply {
            duration = 160
            addUpdateListener {
                currentScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
