package io.github.lumiagg.gestureevo.custom.ball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮球核心视图：支持全屏任意位置拖动、黄色十字指针标四角自适应跟随与矩形框选屏幕取词
 */
@SuppressLint("ViewConstructor")
class FloatingBallView(
    context: Context,
    private var config: FloatingBallConfig
) : FrameLayout(context) {

    // 动作与布局切换回调
    var onActionTrigger: ((actionLink: String) -> Unit)? = null
    var onRequestFullScreenLayout: ((isFullScreen: Boolean) -> Unit)? = null
    var onDragSelectCompleted: ((rect: Rect) -> Unit)? = null
    var onPositionSaved: ((x: Int, y: Int) -> Unit)? = null

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

    // 黄色十字准星与框选画笔
    private val selectRectStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f.dp2px
        color = Color.parseColor("#FFB300") // 经典金黄琥珀色
        pathEffect = DashPathEffect(floatArrayOf(10f.dp2px, 6f.dp2px), 0f)
    }
    private val selectRectFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 255, 179, 0) // 半透明淡黄色
    }

    // 触发区域线条画笔
    private val edgeTriggerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var pointerDrawable: Drawable? = null
    private var customBitmap: Bitmap? = null
    private var customShader: BitmapShader? = null
    private val drawRect = RectF()
    private val selectionRectF = RectF()

    // 触控与状态
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var downRawX = 0f
    private var downRawY = 0f
    private var currentTouchRawX = 0f
    private var currentTouchRawY = 0f

    // 状态标记
    private var isDraggingOrSelecting = false
    private var isFullScreenMode = false
    private var pointerCenterX = 0f
    private var pointerCenterY = 0f

    // 微位移与缩放动画
    private var ballOffsetX = 0f
    private var ballOffsetY = 0f
    private var currentScale = 1.0f

    // 双击检测
    private var clickCount = 0
    private val doubleClickTimeout = 220L
    private val singleClickRunnable = Runnable {
        clickCount = 0
        VibratorUtils.vibrator(context, false)
        onActionTrigger?.invoke(config.clickAction)
    }

    // 长按判定
    private val longPressTimeout = 320L
    private val longPressRunnable = Runnable {
        VibratorUtils.vibrator(context, false)
        startFullScreenDragSelection()
    }

    init {
        setWillNotDraw(false)
        pointerDrawable = ContextCompat.getDrawable(context, R.drawable.pointer)
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

        if (!isFullScreenMode) {
            // 普通静止形态：绘制悬浮球本体及边缘触发线提示
            val cx = width / 2f + ballOffsetX
            val cy = height / 2f + ballOffsetY
            drawBallAt(canvas, cx, cy)
        } else {
            // 全屏拖拽框选形态：绘制选区矩形、悬浮球本体与黄色十字指针标
            drawDragSelectionMode(canvas)
        }
    }

    private fun drawBallAt(canvas: Canvas, cx: Float, cy: Float) {
        val strokeW = config.borderWidthDp.dp2px
        val ballRadius = (config.getActualSizeDp().dp2px) / 2f - strokeW / 2f
        val radius = max(8f, ballRadius * currentScale)

        drawRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val cornerRadius = minOf(radius, config.cornerRadiusDp.dp2px * currentScale)

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

    private fun drawDragSelectionMode(canvas: Canvas) {
        val screenW = width.toFloat()
        val screenH = height.toFloat()

        // 1. 计算悬浮球本体在屏幕上的坐标
        val ballCx = currentTouchRawX
        val ballCy = currentTouchRawY

        // 2. 智能计算黄色十字准星指针的四角吸附坐标（隐形正方形四角算法）
        calculateAdaptivePointerPosition(ballCx, ballCy, screenW, screenH)

        // 3. 计算选区矩形（从按下起始点 downRaw 到当前十字指针中心）
        val left = min(downRawX, pointerCenterX)
        val top = min(downRawY, pointerCenterY)
        val right = max(downRawX, pointerCenterX)
        val bottom = max(downRawY, pointerCenterY)
        selectionRectF.set(left, top, right, bottom)

        // 如果形成了较明显的矩形，绘制黄色半透明背景与黄色虚线边框
        if (selectionRectF.width() > 12f.dp2px || selectionRectF.height() > 12f.dp2px) {
            canvas.drawRoundRect(selectionRectF, 6f.dp2px, 6f.dp2px, selectRectFillPaint)
            canvas.drawRoundRect(selectionRectF, 6f.dp2px, 6f.dp2px, selectRectStrokePaint)
        }

        // 4. 绘制悬浮球本体
        drawBallAt(canvas, ballCx, ballCy)

        // 5. 绘制黄色“+”号十字准星指针标（使用 R.drawable.pointer）
        val pSize = 34f.dp2px
        val pLeft = (pointerCenterX - pSize / 2f).toInt()
        val pTop = (pointerCenterY - pSize / 2f).toInt()
        pointerDrawable?.let { d ->
            d.setBounds(pLeft, pTop, (pLeft + pSize).toInt(), (pTop + pSize).toInt())
            d.draw(canvas)
        }
    }

    /**
     * 指针位置自适应四角算法（保证和悬浮球处于固定距离的隐形正方形四角，绝不远离，贴近屏幕边缘自动切换）
     */
    private fun calculateAdaptivePointerPosition(
        ballCx: Float,
        ballCy: Float,
        screenW: Float,
        screenH: Float
    ) {
        val ballRadius = (config.getActualSizeDp().dp2px) / 2f
        val offsetDist = ballRadius + 22f.dp2px
        val pointerHalfSize = 17f.dp2px
        val statusBarH = ScreenUtil.getStatusBarHeight(context).toFloat()

        // 1. 水平四角判定（左侧 vs 右侧）：
        // 向左拖拽（ballCx < downRawX）时指针保持在左上角（样图效果）；
        // 当悬浮球重新拖拽到屏幕右侧时，指针自动实时切换到右上角；若右侧超出屏幕边缘则安全贴靠。
        val isMovingLeft = (ballCx < downRawX - 8f.dp2px)
        val isNearRightEdge = (ballCx >= screenW - ballRadius - 48f.dp2px)
        val preferLeft = isMovingLeft && !isNearRightEdge

        val cornerX = if (preferLeft) {
            if (ballCx - offsetDist - pointerHalfSize >= 4f.dp2px) {
                ballCx - offsetDist
            } else {
                ballCx + offsetDist
            }
        } else {
            if (ballCx + offsetDist + pointerHalfSize <= screenW - 4f.dp2px) {
                ballCx + offsetDist
            } else {
                (screenW - pointerHalfSize - 4f.dp2px).coerceAtLeast(ballCx)
            }
        }

        // 2. 垂直四角判定（上方 vs 下方）：
        // 默认偏向上方；若上方超出状态栏，自动实时切换到下方
        val canPlaceTop = (ballCy - offsetDist - pointerHalfSize >= statusBarH + 4f.dp2px)
        val canPlaceBottom = (ballCy + offsetDist + pointerHalfSize <= screenH - 8f.dp2px)

        val cornerY = if (canPlaceTop) {
            ballCy - offsetDist
        } else if (canPlaceBottom) {
            ballCy + offsetDist
        } else {
            ballCy - offsetDist
        }

        pointerCenterX = cornerX.coerceIn(pointerHalfSize + 2f.dp2px, screenW - pointerHalfSize - 2f.dp2px)
        pointerCenterY = cornerY.coerceIn(statusBarH + pointerHalfSize + 2f.dp2px, screenH - pointerHalfSize - 2f.dp2px)
    }

    private fun startFullScreenDragSelection() {
        if (!isFullScreenMode) {
            isFullScreenMode = true
            isDraggingOrSelecting = true
            currentScale = 1.15f
            onRequestFullScreenLayout?.invoke(true)
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                currentTouchRawX = event.rawX
                currentTouchRawY = event.rawY
                isDraggingOrSelecting = false

                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                val dist = hypot(dx, dy)

                currentTouchRawX = event.rawX
                currentTouchRawY = event.rawY

                if (!isFullScreenMode) {
                    if (dist > touchSlop) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        startFullScreenDragSelection()
                    }
                } else {
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)

                if (isFullScreenMode) {
                    // 全屏拖拽/框选完成
                    currentScale = 1.0f
                    val left = min(downRawX, pointerCenterX)
                    val top = min(downRawY, pointerCenterY)
                    val right = max(downRawX, pointerCenterX)
                    val bottom = max(downRawY, pointerCenterY)
                    val rectW = right - left
                    val rectH = bottom - top

                    // 如果形成了有效选区框（宽高均大于 15dp），触发划词提取取词！
                    if (rectW >= 15f.dp2px && rectH >= 15f.dp2px) {
                        val selectRect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                        VibratorUtils.vibrator(context, false)
                        onDragSelectCompleted?.invoke(selectRect)
                    }

                    // 贴边保存悬浮球新位置
                    val screenW = ScreenUtil.getScreenWidth(context)
                    val ballSize = config.getActualSizeDp().dp2px.toInt()
                    val targetX = if (event.rawX < screenW / 2) 0 else (screenW - ballSize).coerceAtLeast(0)
                    val targetY = event.rawY.toInt()

                    config.posX = targetX
                    config.posY = targetY
                    config.save(context)
                    onPositionSaved?.invoke(targetX, targetY)

                    // 退出全屏模式，窗口恢复悬浮球原尺寸
                    isFullScreenMode = false
                    isDraggingOrSelecting = false
                    onRequestFullScreenLayout?.invoke(false)
                    invalidate()
                } else {
                    // 未进入全屏框选模式：触发常规手势（单击、双击、微滑动）
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
