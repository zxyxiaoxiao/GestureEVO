package io.github.lumiagg.gestureevo.custom.ball

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
import android.widget.FrameLayout
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 悬浮球核心视图：支持全屏任意位置自由拖动、贴边吸附及常规手势交互（单击、双击、微滑动）
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private var config: FloatingBallConfig
) : FrameLayout(context) {

    // 回调通知
    var onActionTrigger: ((actionLink: String) -> Unit)? = null
    var onPositionMoved: ((rawX: Float, rawY: Float, deltaX: Float, deltaY: Float) -> Unit)? = null
    var onDragFinished: ((finalRawX: Float, finalRawY: Float) -> Unit)? = null

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

    // 触控与手势识别
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false

    // 双击检测
    private var clickCount = 0
    private val doubleClickTimeout = 220L
    private val singleClickRunnable = Runnable {
        clickCount = 0
        VibratorUtils.vibrator(context, false)
        onActionTrigger?.invoke(config.clickAction)
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
        val cx = width / 2f
        val cy = height / 2f
        drawBallAt(canvas, cx, cy)
    }

    private fun drawBallAt(canvas: Canvas, cx: Float, cy: Float) {
        val strokeW = config.borderWidthDp.dp2px
        val ballRadius = (config.getActualSizeDp().dp2px) / 2f - strokeW / 2f
        val radius = max(8f, ballRadius)

        drawRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val cornerRadius = minOf(radius, config.cornerRadiusDp.dp2px)

        // 绘制悬浮球底色
        backgroundPaint.color = Color.argb(220, 245, 245, 248)
        canvas.drawRoundRect(drawRect, cornerRadius, cornerRadius, backgroundPaint)

        // 绘制自定义图片或预设内芯
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
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY
                val totalDist = hypot(totalDx, totalDy)

                // 跟随触发器模式下禁止拖拽移动位置
                val canDragMove = config.positionMode != 1
                if (canDragMove && !isDragging && totalDist > touchSlop) {
                    isDragging = true
                    mainHandler.removeCallbacks(singleClickRunnable)
                }

                if (isDragging) {
                    val deltaX = event.rawX - lastRawX
                    val deltaY = event.rawY - lastRawY
                    onPositionMoved?.invoke(event.rawX, event.rawY, deltaX, deltaY)
                }

                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // 全屏自由拖拽结束，触发松手吸附与坐标保存
                    isDragging = false
                    onDragFinished?.invoke(event.rawX, event.rawY)
                } else {
                    // 未发生拖拽：处理手势（微滑动或点击）
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    val totalDist = hypot(totalDx, totalDy)

                    if (totalDist >= 28f.dp2px) {
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
}
