package io.github.lumiagg.gestureevo.custom.ball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 类似 FV 悬浮球体验的交互 View
 * 具备精致圆形/圆角绘制、自定义图标渲染、微位移手势指示、长按拖动以及单击/双击区分逻辑
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private var config: FloatingBallConfig
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
    }
    private val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33000000")
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var customBitmap: Bitmap? = null
    private var customShader: BitmapShader? = null
    private val drawRect = RectF()

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
        animateScale(1.15f)
        if (config.longPressAction.isNotBlank()) {
            onActionTrigger?.invoke(config.longPressAction)
        }
    }

    init {
        setWillNotDraw(false)
        applyConfigParams()
    }

    fun updateConfig(newConfig: FloatingBallConfig) {
        this.config = newConfig
        applyConfigParams()
        invalidate()
    }

    private fun applyConfigParams() {
        alpha = config.getIconAlpha()

        borderPaint.strokeWidth = config.borderWidthDp.dp2px
        borderPaint.color = config.borderColor

        // 加载自定义图标图片
        if (config.iconStyleType == 1 && config.customIconPath.isNotBlank()) {
            loadCustomBitmap(config.customIconPath)
        } else {
            customBitmap = null
            customShader = null
        }
    }

    private fun loadCustomBitmap(path: String) {
        try {
            val file = File(path)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp != null) {
                    customBitmap = bmp
                    customShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    imagePaint.shader = customShader
                }
            }
        } catch (_: Exception) {
            customBitmap = null
            customShader = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f + ballOffsetX
        val cy = height / 2f + ballOffsetY
        val strokeW = config.borderWidthDp.dp2px
        val halfW = (width / 2f - strokeW / 2f - 2f.dp2px) * currentScale
        val halfH = (height / 2f - strokeW / 2f - 2f.dp2px) * currentScale
        val radius = minOf(halfW, halfH)

        drawRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val cornerRadius = minOf(radius, config.cornerRadiusDp.dp2px * currentScale)

        // 绘制背景
        backgroundPaint.color = Color.argb(220, 245, 245, 248)
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, backgroundPaint)

        // 自定义图片渲染
        val shader = customShader
        val bmp = customBitmap
        if (config.iconStyleType == 1 && shader != null && bmp != null) {
            val matrix = Matrix()
            val scale = (radius * 2f) / maxOf(bmp.width, bmp.height).toFloat()
            val dx = cx - (bmp.width * scale) / 2f
            val dy = cy - (bmp.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            shader.setLocalMatrix(matrix)
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, imagePaint)
        } else {
            // 预设圆形模板中心呼吸感圆核（受内部占比缩放）
            val innerRatio = (config.innerRatioPercent.coerceIn(0, 100)) / 100f
            val coreRadius = radius * 0.42f * innerRatio
            if (coreRadius > 0f) {
                canvas.drawCircle(cx, cy, coreRadius, innerCorePaint)
            }
        }

        // 绘制边框
        if (config.borderWidthDp > 0f) {
            canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, borderPaint)
        }
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

                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx, dy)

                if (!isDragging) {
                    if (dist > touchSlop) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        isSwiping = true

                        val maxOffset = 12f.dp2px
                        val factor = minOf(1.0f, dist / (swipeThreshold * 2f))
                        val angle = kotlin.math.atan2(dy, dx)
                        ballOffsetX = (factor * maxOffset * kotlin.math.cos(angle))
                        ballOffsetY = (factor * maxOffset * kotlin.math.sin(angle))
                        invalidate()
                    }
                } else {
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
                    isDragging = false
                    animateScale(1.0f)
                    onDragReleased?.invoke()
                } else {
                    resetBallOffset()

                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    val totalDist = hypot(totalDx, totalDy)

                    if (totalDist >= swipeThreshold) {
                        handleSwipeGesture(totalDx, totalDy)
                    } else if (totalDist < touchSlop) {
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
