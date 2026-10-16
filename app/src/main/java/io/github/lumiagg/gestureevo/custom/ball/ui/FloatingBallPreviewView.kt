package io.github.lumiagg.gestureevo.custom.ball.ui

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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.utils.dp2px
import java.io.File

/**
 * 悬浮球与线条调节弹窗专用的实时预览控件
 * 支持：悬浮球图标预览、线条样式预览、小屏幕视口拖拽位置预览
 */
class FloatingBallPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PreviewMode {
        BALL_ICON,  // 预览悬浮球本体（尺寸、内部占比、透明度、边框、圆角、图片）
        SCREEN_POS  // 微缩屏幕视口位置预览（拖拽悬浮球更新坐标）
    }

    var mode: PreviewMode = PreviewMode.BALL_ICON
        set(value) {
            field = value
            invalidate()
        }

    // 当前配置克隆体，用于弹窗实时渲染
    var config: FloatingBallConfig = FloatingBallConfig()
        set(value) {
            field = value
            reloadCustomBitmap()
            invalidate()
        }

    // 位置模式下的屏幕逻辑尺寸与回调
    var screenRealWidth: Int = 1080
    var screenRealHeight: Int = 2400
    var onPositionDragged: ((x: Int, y: Int) -> Unit)? = null

    // 画笔
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#15000000")
        strokeWidth = 1f.dp2px
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#F5F6F9")
    }
    private val ballBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33000000")
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val screenFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#888888")
        strokeWidth = 2f.dp2px
    }

    private var customBitmap: Bitmap? = null
    private var customShader: BitmapShader? = null
    private val tempRect = RectF()
    private val linePath = Path()

    init {
        reloadCustomBitmap()
    }

    private fun reloadCustomBitmap() {
        if (config.iconStyleType == 1 && config.customIconPath.isNotBlank()) {
            try {
                val f = File(config.customIconPath)
                if (f.exists()) {
                    val bmp = BitmapFactory.decodeFile(config.customIconPath)
                    if (bmp != null) {
                        customBitmap = bmp
                        customShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        imagePaint.shader = customShader
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        customBitmap = null
        customShader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制预览区域浅灰背景与精美网格标尺
        canvas.drawRoundRect(0f, 0f, w, h, 16f.dp2px, 16f.dp2px, bgPaint)
        drawGrid(canvas, w, h)

        when (mode) {
            PreviewMode.BALL_ICON -> drawBallIconPreview(canvas, w / 2f, h / 2f)
            PreviewMode.SCREEN_POS -> drawScreenPosPreview(canvas, w / 2f, h / 2f)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val step = 16f.dp2px
        var x = step
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += step
        }
        var y = step
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += step
        }
    }

    private fun drawBallIconPreview(canvas: Canvas, cx: Float, cy: Float) {
        val ballSizeDp = config.getActualSizeDp()
        drawBallAt(canvas, cx, cy, (ballSizeDp.dp2px) / 2f)
    }

    private fun drawBallAt(canvas: Canvas, cx: Float, cy: Float, radius: Float = (config.getActualSizeDp().dp2px) / 2f) {
        val ballAlpha = config.getIconAlpha()
        tempRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val corner = minOf(radius, config.cornerRadiusDp.dp2px * (config.sizePercent / 100f))

        // 绘制悬浮球底色（半透明白）
        ballBgPaint.color = Color.WHITE
        ballBgPaint.alpha = (ballAlpha * 255).toInt()
        canvas.drawRoundRect(tempRect, corner, corner, ballBgPaint)

        // 自定义图片或预设内芯
        if (config.iconStyleType == 1 && customShader != null && customBitmap != null) {
            val bmp = customBitmap!!
            val matrix = Matrix()
            val scale = (radius * 2f) / maxOf(bmp.width, bmp.height).toFloat()
            val dx = cx - (bmp.width * scale) / 2f
            val dy = cy - (bmp.height * scale) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            customShader?.setLocalMatrix(matrix)
            imagePaint.alpha = (ballAlpha * 255).toInt()
            canvas.drawRoundRect(tempRect, corner, corner, imagePaint)
        } else {
            val innerRatio = (config.innerRatioPercent.coerceIn(0, 100)) / 100f
            val coreR = radius * 0.42f * innerRatio
            if (coreR > 0f) {
                innerCorePaint.alpha = (ballAlpha * 255 * 0.5f).toInt()
                canvas.drawCircle(cx, cy, coreR, innerCorePaint)
            }
        }

        // 边框
        if (config.borderWidthDp > 0f) {
            borderPaint.strokeWidth = config.borderWidthDp.dp2px
            borderPaint.color = config.borderColor
            borderPaint.alpha = (ballAlpha * 255).toInt()
            canvas.drawRoundRect(tempRect, corner, corner, borderPaint)
        }
    }



    private fun drawScreenPosPreview(canvas: Canvas, w: Float, h: Float) {
        // 计算微缩手机屏幕框的尺寸与内缩居中
        val pad = 24f.dp2px
        val maxW = w - pad * 2
        val maxH = h - pad * 2

        val aspect = screenRealWidth.toFloat() / maxOf(1, screenRealHeight).toFloat()
        var viewW = maxW
        var viewH = viewW / aspect
        if (viewH > maxH) {
            viewH = maxH
            viewW = viewH * aspect
        }

        val frameLeft = (w - viewW) / 2f
        val frameTop = (h - viewH) / 2f
        val frameRight = frameLeft + viewW
        val frameBottom = frameTop + viewH

        // 绘制微缩手机外框
        screenFramePaint.color = Color.parseColor("#44888888")
        canvas.drawRoundRect(frameLeft, frameTop, frameRight, frameBottom, 12f.dp2px, 12f.dp2px, screenFramePaint)

        // 映射坐标
        val scale = viewW / screenRealWidth.toFloat()
        val ballPxOnReal = config.getActualSizeDp().dp2px
        val ballSizeOnScreen = (ballPxOnReal * scale).coerceAtLeast(16f.dp2px)

        val posX = frameLeft + config.posX * scale
        val posY = frameTop + config.posY * scale

        // 绘制悬浮球指示点
        val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3")
            alpha = 220
        }
        canvas.drawCircle(posX + ballSizeOnScreen / 2f, posY + ballSizeOnScreen / 2f, ballSizeOnScreen / 2f, ballPaint)

        // 白色内圈
        canvas.drawCircle(posX + ballSizeOnScreen / 2f, posY + ballSizeOnScreen / 2f, ballSizeOnScreen / 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != PreviewMode.SCREEN_POS) {
            return super.onTouchEvent(event)
        }

        val pad = 24f.dp2px
        val maxW = width - pad * 2
        val maxH = height - pad * 2
        val aspect = screenRealWidth.toFloat() / maxOf(1, screenRealHeight).toFloat()
        var viewW = maxW
        var viewH = viewW / aspect
        if (viewH > maxH) {
            viewH = maxH
            viewW = viewH * aspect
        }
        val frameLeft = (width - viewW) / 2f
        val frameTop = (height - viewH) / 2f
        val scale = viewW / screenRealWidth.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val touchX = event.x.coerceIn(frameLeft, frameLeft + viewW)
                val touchY = event.y.coerceIn(frameTop, frameTop + viewH)

                val realX = ((touchX - frameLeft) / scale).toInt().coerceIn(0, screenRealWidth)
                val realY = ((touchY - frameTop) / scale).toInt().coerceIn(0, screenRealHeight)

                config.posX = realX
                config.posY = realY
                invalidate()
                onPositionDragged?.invoke(realX, realY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
