package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.databinding.PointerFloatingTouchViewBinding

import io.github.lumiagg.gestureevo.databinding.GestureActionPointerViewBinding

import io.github.lumiagg.gestureevo.databinding.PointerFloatingViewBinding

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
import androidx.core.view.doOnNextLayout
import com.google.android.material.card.MaterialCardView
import com.google.zxing.common.detector.MathUtils.distance
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.GestureManager.InteractionMode
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.inspire.SmartSegmenter
import io.github.lumiagg.gestureevo.inspire.getScreenView
import io.github.lumiagg.gestureevo.pointer.ui.HighlightRectView
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_POINTER_COLOR
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_ENABLE_POINTER_DRAG_MODE
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_POINTER_TOUCH_AREA_OFFSET_DP
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_SHOW_POINTER_TRAIL
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.LinkedList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min


class PointerFloating(
    private val context: GestureService,
) : BaseFloatingWindow(context, context, "PointerFloating") {
    private val pointerFloatingView: ConstraintLayout by lazy {
        PointerFloatingViewBinding.inflate(LayoutInflater.from(context)).root
    }
    private val touchView: MaterialCardView by lazy {
        PointerFloatingTouchViewBinding.inflate(LayoutInflater.from(context)).root
    }
    private val pointerLayoutParams = WindowManager.LayoutParams()
    private val touchLayoutParams = WindowManager.LayoutParams()
    private var touchViewScale = 0.35
    var screenStatusHeight = ScreenUtil.getStatusBarHeight(context)
    private val trailView = TrailView(context)
    private val highlightRectView = HighlightRectView(context)
    val pointerManger = PointerManger()
    var needShowTouchView = false
    private var alwaysShowTouchArea = false
    private var touchAreaOffsetDp = 0
    // === CUSTOM EXTENSION START ===
    private var pointerFixedArea = false
    var followBallEnabled = false
    // === CUSTOM EXTENSION END ===
    private var pointerVisibleRequested = false
    var triggerSide: String? = null

    init {
        initView()
        initFloatingWindow()
        initTouchView()
        addTrailView()
        addHighlightRectView()
    }

    private fun addHighlightRectView() {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pointerFloatingView.addView(
            highlightRectView,
            1, // Add above trail view but below pointer
            layoutParams
        )
    }

    private fun addTrailView() {
        val layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT
        )
        pointerFloatingView.addView(
            trailView, 0, layoutParams
        ) // Add at index 0 to be behind pointer
    }

    private fun initView() {
        pointerFloatingView.visibility = View.GONE
    }

    fun refreshTheme() {
        trailView.refreshTheme()
        highlightRectView.setStrokeColor(pointerManger.getPointerColor())
        pointerManger.refreshTheme()
    }

    private fun initFloatingWindow() {
        pointerLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:PointerFloating:Pointer",
        )
        pointerFloatingView.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchView() {
        touchLayoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            width = ViewGroup.LayoutParams.WRAP_CONTENT,
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            allowDisplayCutout = false,
            title = "GestureEVO:PointerFloating:Touch",
        )
        touchView.setOnTouchListener { view, event ->
            pointerManger.onTouchEvent(event, fromTouchView = true)
            return@setOnTouchListener false
        }
        touchView.visibility = View.GONE
    }

    private fun updateTouchView() {
        pointerFloatingView.post {
            if (!pointerVisibleRequested) {
                return@post
            }
            if (!pointerFloatingView.isAttachedToWindow || !touchView.isAttachedToWindow) {
                return@post
            }
            pointerFloatingView.visibility = View.VISIBLE
            if (pointerFloatingView.width > 0 && pointerFloatingView.height > 0) {
                updateTouchViewLayout()
            } else {
                pointerFloatingView.doOnNextLayout {
                    updateTouchViewLayout()
                }
            }
        }
    }

    private fun updateTouchViewLayout() {
        if (!pointerVisibleRequested) {
            return
        }
        if (!pointerFloatingView.isAttachedToWindow || !touchView.isAttachedToWindow) {
            return
        }
        val screenWidth = pointerFloatingView.width
        val screenHeight = pointerFloatingView.height
        if (screenWidth <= 0 || screenHeight <= 0) return

        val layoutParams = touchView.layoutParams as WindowManager.LayoutParams
        layoutParams.width = (screenWidth * touchViewScale + 20f.dp2px).toInt()
        layoutParams.height = ((screenHeight + screenStatusHeight) * touchViewScale).toInt()

        val minDistanceFromEdge = 10f.dp2px
        val minDistanceFromBottom = 20f.dp2px

        val minX = minDistanceFromEdge
        val maxX = screenWidth - layoutParams.width - minDistanceFromEdge
        val minY = minDistanceFromBottom
        val maxY = screenHeight - layoutParams.height - minDistanceFromBottom

        val touchYRatio = (pointerManger.touchViewY / screenHeight).coerceIn(0f, 1f)
        val amplifiedTouchYRatio = ((touchYRatio - 0.5f) * 1.5f + 0.5f).coerceIn(0f, 1f)

        layoutParams.x = (pointerManger.touchViewX - layoutParams.width / 2).toInt()
            .coerceIn(minX.toInt(), maxX.toInt())
        layoutParams.y = (minY + (maxY - minY) * amplifiedTouchYRatio + touchAreaOffsetDp.dp2px).toInt()
            .coerceIn(minY.toInt(), maxY.toInt())

        updateViewLayoutSafely(touchView, layoutParams)
        touchView.alpha = if (shouldShowTouchArea()) 1f else 0f
        touchView.visibility = View.VISIBLE
    }

    private fun shouldShowTouchArea() = needShowTouchView || alwaysShowTouchArea

    private companion object {
        const val POINTER_SHOW_ANIMATION_DURATION = 140L
        const val POINTER_HIDE_ANIMATION_DURATION = 110L
        const val POINTER_ANIMATION_START_SCALE = 0.82f
    }

    var enable = false
        set(value) {
            if (value) {
                show()
            } else {
                hide()
            }
            field = value
        }

    fun show() = context.postOnMain {
        pointerVisibleRequested = true
        ensureViewsAttached()
        updateTouchAreaConfig()
        pointerManger.updatePointerColorConfig()
        highlightRectView.setStrokeColor(pointerManger.getPointerColor())
        pointerFloatingView.visibility = View.VISIBLE
        updateTouchView()
        pointerManger.showPointer()
        pointerManger.viewPositionMonitor.start()
        trailView.updateTrailConfig()
    }

    fun hide() = context.postOnMain {
        // === CUSTOM EXTENSION START ===
        if (followBallEnabled) {
            io.github.lumiagg.gestureevo.custom.ball.FloatingBallManager.resetAndHideFollowBall(context)
            followBallEnabled = false
        }
        // === CUSTOM EXTENSION END ===
        pointerVisibleRequested = false
        pointerManger.viewPositionMonitor.stop()
        if (pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
        if (!pointerFloatingView.isAttachedToWindow && !touchView.isAttachedToWindow) {
            needShowTouchView = false
            pointerManger.initPosition()
            trailView.clearTrail()
            return@postOnMain
        }
        touchView.animate().cancel()
        touchView.visibility = View.GONE
        needShowTouchView = false
        trailView.clearTrail()
        pointerManger.hidePointer {
            if (!pointerVisibleRequested) {
                pointerFloatingView.visibility = View.GONE
                pointerManger.initPosition()
                detachViewsIfNeeded()
            }
        }
    }

    private fun ensureViewsAttached() {
        val updateWhenReady = {
            if (pointerFloatingView.isAttachedToWindow && touchView.isAttachedToWindow) {
                updateTouchView()
            }
        }
        attachViewSafely(
            pointerFloatingView,
            pointerLayoutParams,
            shouldRetry = { pointerVisibleRequested },
            onAttached = updateWhenReady
        )
        attachViewSafely(
            touchView,
            touchLayoutParams,
            shouldRetry = { pointerVisibleRequested },
            onAttached = updateWhenReady
        )
    }

    private fun detachViewsIfNeeded() {
        detachViewSafely(pointerFloatingView)
        detachViewSafely(touchView)
    }

    fun destroy() = context.postOnMain {
        pointerVisibleRequested = false
        pointerManger.viewPositionMonitor.stop()
        if (pointerManger.isDragMode) {
            pointerManger.exitDragMode()
        }
        pointerFloatingView.animate().cancel()
        touchView.animate().cancel()
        pointerFloatingView.visibility = View.GONE
        touchView.visibility = View.GONE
        needShowTouchView = false
        pointerManger.initPosition()
        trailView.clearTrail()
        detachViewsIfNeeded()
    }

    private suspend fun updateTouchAreaConfig() = withContext(Dispatchers.IO) {
        alwaysShowTouchArea = context.settingsStore.readKey(
            KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA,
            false
        )
        touchAreaOffsetDp = context.settingsStore.readKey(
            KEY_POINTER_TOUCH_AREA_OFFSET_DP,
            0
        ).coerceIn(-100, 100)
        // === CUSTOM EXTENSION START ===
        pointerFixedArea = context.settingsStore.readKey(
            io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.KEY_POINTER_FIXED_AREA,
            false
        )
        // === CUSTOM EXTENSION END ===
    }

    // Trail View class to draw the pointer's trail
    inner class TrailView(context: Context) : View(context) {
        var enable = false
        private val MAX_TRAIL_POINTS = 10
        private val trailPoints = LinkedList<Pair<Float, Float>>()
        private val trailPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = DynamicColorUtils.primaryFixedColor
        }

        fun addPoint(x: Float, y: Float) {
            if (enable) {
                trailPoints.add(Pair(x, y))
                if (trailPoints.size > MAX_TRAIL_POINTS) {
                    trailPoints.removeFirst()
                }
                invalidate()
            }
        }

        suspend fun updateTrailConfig() {
            enable = context.settingsStore.readKey(KEY_SHOW_POINTER_TRAIL, false)
            pointerManger.showViewCenter(!enable)
            withContext(Dispatchers.Main) {
                refreshTheme()
            }
        }

        fun refreshTheme() {
            trailPaint.color = pointerManger.getPointerColor()
            invalidate()
        }

        fun clearTrail() {
            trailPoints.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (trailPoints.size < 2) return

            // Draw dots with decreasing size and opacity
            trailPoints.forEachIndexed { index, point ->
                val alpha = (255 * (index.toFloat() / trailPoints.size)).toInt()
                val radius = (3 * (index.toFloat() / trailPoints.size) + 1f.dp2px).dp2px

                trailPaint.alpha = alpha
                canvas.drawCircle(point.first, point.second, radius, trailPaint)
            }
        }
    }

    inner class PointerManger() {
        private val view = GestureActionPointerViewBinding.inflate(LayoutInflater.from(context)).root
        private val pointerIcon: ImageFilterView = view.findViewById(R.id.pointer)
        private var pointerColor: Int = 0

        var touchViewX = 0f
        var touchViewY = 0f
        var isDragMode = false
        private var isHoverLongPressActive = false
        private var hoverLongPressJob: Job? = null
        private var pendingHoverLongPress = false
        private var pendingHoverPoint: Point? = null
        private var isLongPressRunning = false
        private var potentialDragSelectRect: Rect? = null
        private var potentialDragSelectJob: Job? = null
        private var potentialDragSelectSourceRect: Rect? = null
        private var pendingPotentialDragSelectRect: Rect? = null
        private var hasStartedDragSelection = false
        private var lastPotentialThinRect: Rect? = null
        // === CUSTOM EXTENSION START ===
        private var isConfirmedSelection = false
        private var confirmSelectionJob: Job? = null
        private var initialTouchX = -1f
        private var elementCapturedPoint: Point? = null
        // === CUSTOM EXTENSION END ===
        private val moveTouchHandler = MoveTouchHandler()
        val viewPositionMonitor = ViewPositionMonitor()

        init {
            val layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            )

            // Constrain all four sides to parent container
            layoutParams.topToTop = LayoutParams.PARENT_ID
            layoutParams.bottomToBottom = LayoutParams.PARENT_ID
            layoutParams.startToStart = LayoutParams.PARENT_ID
            layoutParams.endToEnd = LayoutParams.PARENT_ID

            pointerFloatingView.addView(view, layoutParams)
            applyYellowPlusStyle()
        }

        private fun applyYellowPlusStyle() {
            pointerIcon.apply {
                setImageResource(R.drawable.baseline_add_24)
                imageTintList = ColorStateList.valueOf(Color.rgb(0xFF, 0xC1, 0x07))
                background = null
                scaleX = 0.7f
                scaleY = 0.7f
                setPadding(0, 0, 0, 0)
            }
        }

        fun initPosition() {
            view.animate().cancel()
            view.x = -100f
            view.y = -100f
            view.alpha = 1f
            view.scaleX = 0.7f
            view.scaleY = 0.7f
            applyYellowPlusStyle()
            trailView.clearTrail()
        }

        fun showPointer() {
            view.animate().cancel()
            view.alpha = 0f
            view.scaleX = POINTER_ANIMATION_START_SCALE * 0.7f
            view.scaleY = POINTER_ANIMATION_START_SCALE * 0.7f
            view.visibility = View.VISIBLE
            applyYellowPlusStyle()
            view.animate()
                .alpha(1f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(POINTER_SHOW_ANIMATION_DURATION)
                .start()
        }

        fun hidePointer(onEnd: () -> Unit) {
            view.animate().cancel()
            if (view.visibility != View.VISIBLE) {
                onEnd()
                return
            }
            view.animate()
                .alpha(0f)
                .scaleX(POINTER_ANIMATION_START_SCALE * 0.7f)
                .scaleY(POINTER_ANIMATION_START_SCALE * 0.7f)
                .setDuration(POINTER_HIDE_ANIMATION_DURATION)
                .withEndAction {
                    view.visibility = View.INVISIBLE
                    view.alpha = 1f
                    view.scaleX = 0.7f
                    view.scaleY = 0.7f
                    onEnd()
                }
                .start()
        }

        fun onTouchEvent(event: MotionEvent, fromTouchView: Boolean = false) {
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    // === CUSTOM EXTENSION START ===
                    initialTouchX = event.rawX
                    // === CUSTOM EXTENSION END ===
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    if (enable && fromTouchView && touchView.alpha == 1f && !alwaysShowTouchArea) {
                        touchView.alpha = 0f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    if (enable) {
                        moveTouchHandler.handleActionMove(event)
                        // === CUSTOM EXTENSION START ===
                        if (followBallEnabled) {
                            io.github.lumiagg.gestureevo.custom.ball.FloatingBallManager.showAt(context, event.rawX, event.rawY)
                        }
                        // === CUSTOM EXTENSION END ===
                    }
                }

                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    touchViewX = event.rawX
                    touchViewY = event.rawY
                    if (enable) {
                        moveTouchHandler.handleActionUp(event)
                    }
                    // === CUSTOM EXTENSION START ===
                    initialTouchX = -1f
                    if (followBallEnabled) {
                        io.github.lumiagg.gestureevo.custom.ball.FloatingBallManager.resetAndHideFollowBall(context)
                        followBallEnabled = false
                    }
                    // === CUSTOM EXTENSION END ===
                    enable = false
                }
            }
        }

        suspend fun updatePointerColorConfig() = withContext(Dispatchers.IO) {
            pointerColor = context.settingsStore.readKey(KEY_POINTER_COLOR, 0)
        }

        fun getPointerColor(): Int {
            return if (pointerColor == 0) DynamicColorUtils.primaryColor else pointerColor
        }

        private fun applyPointerTheme(target: ImageFilterView) {
            val color = getPointerColor()
            target.imageTintList = ColorStateList.valueOf(color)
            target.backgroundTintList = ColorStateList.valueOf(color)
        }

        fun refreshTheme() {
            applyPointerTheme(pointerIcon)
        }

        suspend fun showViewCenter(boolean: Boolean) = withContext(Dispatchers.Main) {
            if (boolean) {
                pointerIcon.apply {
                    setImageResource(R.drawable.baseline_circle_24)
                    applyPointerTheme(this)
                }
            } else {
                pointerIcon.setImageDrawable(null)
            }
        }

        fun enterDragMode() {
            isDragMode = true
            potentialDragSelectJob?.cancel()
            potentialDragSelectRect = null
            potentialDragSelectSourceRect = null
            pendingPotentialDragSelectRect = null
            hasStartedDragSelection = false
            lastPotentialThinRect = null
            context.gestureManager?.setInteractionMode(InteractionMode.POINTER_DRAG_SELECT)
            context.launch(Dispatchers.IO) {
                try {
                    MlKitOcr.preload()
                } catch (t: Throwable) {
                    android.util.Log.e("PointerFloating", "MlKitOcr preload error", t)
                }
                try {
                    SmartSegmenter.prewarm(context.applicationContext)
                } catch (t: Throwable) {
                    android.util.Log.e("PointerFloating", "SmartSegmenter prewarm error", t)
                }
            }
            VibratorUtils.vibrator(context, true)
            // === CUSTOM EXTENSION START ===
            io.github.lumiagg.gestureevo.custom.pointer.AccessibilityNodeCacheManager.onPointerActivated(context)
            // === CUSTOM EXTENSION END ===
            context.postOnMain {
                trailView.enable = false
                trailView.clearTrail()
                pointerIcon.apply {
                    setImageResource(R.drawable.baseline_add_24)
                    imageTintList = ColorStateList.valueOf(Color.rgb(0xFF, 0xC1, 0x07)) // 黄色指针（预选）
                    background = null
                    scaleX = 0.7f // +号缩小30%
                    scaleY = 0.7f
                    setPadding(0, 0, 0, 0)
                }
                elementCapturedPoint = null
                val halfPointerWidth = view.width / 2
                val startPoint = Point(
                    view.x + halfPointerWidth,
                    view.y + halfPointerWidth
                )
                highlightRectView.showRect(startPoint)
                loadPotentialDragSelectRect(startPoint)
            }
        }

        fun exitDragMode() {
            isDragMode = false
            potentialDragSelectJob?.cancel()
            potentialDragSelectJob = null
            potentialDragSelectRect = null
            potentialDragSelectSourceRect = null
            pendingPotentialDragSelectRect = null
            hasStartedDragSelection = false
            lastPotentialThinRect = null
            // === CUSTOM EXTENSION START ===
            confirmSelectionJob?.cancel()
            confirmSelectionJob = null
            isConfirmedSelection = false
            elementCapturedPoint = null
            io.github.lumiagg.gestureevo.custom.pointer.AccessibilityNodeCacheManager.onPointerDeactivated()
            // === CUSTOM EXTENSION END ===
            context.gestureManager?.setInteractionMode(InteractionMode.NORMAL)
            context.postOnMain {
                applyYellowPlusStyle()
                trailView.updateTrailConfig()
                highlightRectView.hideRect()
                highlightRectView.hidePotentialRect()
            }
        }

        // === CUSTOM EXTENSION START ===
        private fun applyPotentialTargetRect(targetRect: Rect?) {
            confirmSelectionJob?.cancel()
            isConfirmedSelection = false
            if (targetRect == null) {
                elementCapturedPoint = null
                highlightRectView.hidePotentialRect()
                if (isDragMode && !hasStartedDragSelection) {
                    pointerIcon.imageTintList = ColorStateList.valueOf(Color.rgb(0xFF, 0xC1, 0x07))
                }
            } else {
                if (hasStartedDragSelection) {
                    highlightRectView.hidePotentialRect()
                    return
                }
                // 实时捕捉到屏幕元素：黄色指针与黄色预选栏（预选）
                pointerIcon.imageTintList = ColorStateList.valueOf(Color.rgb(0xFF, 0xC1, 0x07))
                highlightRectView.showPotentialRect(targetRect, isConfirmed = false)
                // 停留500ms后，选中栏和指针从黄色修改为红色（为真实选中）
                confirmSelectionJob = context.launch(Dispatchers.Main) {
                    delay(500)
                    if (isDragMode && !hasStartedDragSelection && potentialDragSelectRect == targetRect) {
                        isConfirmedSelection = true
                        pointerIcon.imageTintList = ColorStateList.valueOf(Color.rgb(0xF4, 0x43, 0x36))
                        highlightRectView.showPotentialRect(targetRect, isConfirmed = true)
                        VibratorUtils.vibrator(context, false)
                    }
                }
            }
        }
        // === CUSTOM EXTENSION END ===

        private fun loadPotentialDragSelectRect(point: Point) {
            val centerX = point.x.toInt()
            val centerY = point.y.toInt()
            val pointRect = Rect(
                centerX - 8,
                centerY - 8,
                centerX + 8,
                centerY + 8
            )
            val sourceRect = Rect(centerX, centerY, centerX, centerY)
            potentialDragSelectJob?.cancel()
            potentialDragSelectSourceRect = Rect(sourceRect)
            potentialDragSelectJob = context.launch(Dispatchers.Default) {
                // === CUSTOM EXTENSION START ===
                val cachedHit = io.github.lumiagg.gestureevo.custom.pointer.AccessibilityNodeCacheManager.hitTest(centerX, centerY)
                val targetRect = cachedHit ?: getScreenView(context, pointRect, clickMode = true)
                    .filter { it.area > 0 }
                    .minByOrNull { it.area }
                    ?.bound
                // === CUSTOM EXTENSION END ===
                if (potentialDragSelectSourceRect != sourceRect) return@launch
                withContext(Dispatchers.Main) {
                    if (!isDragMode || hasStartedDragSelection) return@withContext
                    if (potentialDragSelectRect != targetRect) {
                        potentialDragSelectRect = targetRect
                        applyPotentialTargetRect(targetRect)
                    }
                }
            }
        }

        private fun updatePotentialDragSelectRect(selectionRect: Rect, endPoint: Point) {
            if (highlightRectView.shouldUseDragSelection(endPoint)) {
                return
            }
            if (selectionRect == lastPotentialThinRect) {
                return
            }
            lastPotentialThinRect = Rect(selectionRect)
            pendingPotentialDragSelectRect = Rect(selectionRect)
            if (potentialDragSelectJob?.isActive == true) {
                return
            }
            potentialDragSelectJob = context.launch(Dispatchers.Default) {
                while (isDragMode && !hasStartedDragSelection) {
                    val sourceRect = pendingPotentialDragSelectRect ?: break
                    pendingPotentialDragSelectRect = null
                    val targetRect = resolvePotentialDragSelectRect(sourceRect)
                    potentialDragSelectSourceRect = Rect(sourceRect)
                    potentialDragSelectRect = targetRect
                    withContext(Dispatchers.Main) {
                        if (!isDragMode || hasStartedDragSelection) return@withContext
                        applyPotentialTargetRect(targetRect)
                    }
                }
            }
        }

        private suspend fun resolvePotentialDragSelectRect(selectionRect: Rect): Rect? {
            val tooSmallWidth = selectionRect.width() < io.github.lumiagg.gestureevo.inspire.ui.InspireView.DRAG_SELECT_CLICK_THRESHOLD
            val tooSmallHeight = selectionRect.height() < io.github.lumiagg.gestureevo.inspire.ui.InspireView.DRAG_SELECT_CLICK_THRESHOLD
            return when {
                tooSmallWidth && tooSmallHeight -> {
                    // === CUSTOM EXTENSION START ===
                    val cachedHit = io.github.lumiagg.gestureevo.custom.pointer.AccessibilityNodeCacheManager.hitTest(selectionRect)
                    cachedHit ?: getScreenView(context, selectionRect, clickMode = true)
                        .filter { it.area > 0 }
                        .minByOrNull { it.area }
                        ?.bound
                    // === CUSTOM EXTENSION END ===
                }

                tooSmallWidth || tooSmallHeight -> {
                    val views = getScreenView(context, selectionRect, clickMode = false)
                    if (views.isEmpty()) {
                        null
                    } else {
                        Rect().also { expandedRect ->
                            views.forEachIndexed { index, view ->
                                if (index == 0) {
                                    expandedRect.set(view.bound)
                                } else {
                                    expandedRect.union(view.bound)
                                }
                            }
                        }
                    }
                }

                else -> null
            }
        }

        private fun showHoverLongPressHint() {
            if (isDragMode || isHoverLongPressActive) return
            isHoverLongPressActive = true
            pointerIcon.animate().cancel()
            pointerIcon.scaleX = 1f
            pointerIcon.scaleY = 1f
            pointerIcon.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(90)
                .withEndAction {
                    pointerIcon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(110)
                        .start()
                }
                .start()
        }

        private fun resetHoverLongPressHint() {
            if (!isHoverLongPressActive) return
            isHoverLongPressActive = false
            pointerIcon.animate().cancel()
            pointerIcon.scaleX = 1f
            pointerIcon.scaleY = 1f
            pendingHoverLongPress = false
            pendingHoverPoint = null
        }

        private fun prepareHoverLongPress(point: Point) {
            if (isDragMode) return
            showHoverLongPressHint()
            pendingHoverLongPress = true
            pendingHoverPoint = point
        }

        private inner class MoveTouchHandler() {

            fun computePosition(): Point {
                val screenWidth = pointerFloatingView.width
                val screenHeight = pointerFloatingView.height
                val touchWidth = touchView.width
                val touchHeight = touchView.height
                if (screenWidth <= 0 || screenHeight <= 0 || touchWidth <= 0 || touchHeight <= 0) {
                    return Point(
                        (view.x + view.width / 2).coerceAtLeast(0f),
                        (view.y + view.height / 2).coerceAtLeast(0f)
                    )
                }

                // === CUSTOM EXTENSION START ===
                if (pointerFixedArea) {
                    val distPx = 41.5f.dp2px * 1.3f // 间距在现有基础上提升30% (53.95dp)
                    val rad = Math.toRadians(40.0)
                    val dxPx = (distPx * Math.cos(rad)).toFloat() // 约 41.328dp
                    val dyPx = (distPx * Math.sin(rad) * 1.5).toFloat() // 垂直整体上移50% (约 52.017dp)
                    val leftOffsetPx = 25f.dp2px // 整体向左偏移25dp
                    val pointerRadius = if (view.width > 0) view.width / 2f else 15f.dp2px
                    val minSafeY = if (screenStatusHeight > 0) screenStatusHeight.toFloat() else 0f

                    val threshold40dp = 40f.dp2px

                    val isRightTrigger = if (triggerSide != null) {
                        triggerSide.equals(TriggerSide.RIGHT, ignoreCase = true)
                    } else {
                        if (initialTouchX >= 0f) initialTouchX >= screenWidth / 2f else touchViewX >= screenWidth / 2f
                    }

                    val dx = if (isRightTrigger) {
                        // 从屏幕右侧边缘触发
                        val distToRight = (screenWidth - touchViewX).coerceAtLeast(0f)
                        if (distToRight >= threshold40dp) {
                            // 手指在离屏幕边缘超过40dp：完全显示在内侧（左侧，-dxPx）
                            -dxPx
                        } else {
                            // 手指在离屏幕边缘40dp以内：指针实时跟随手指的移动速度逐渐平滑调整位置（0dp完全靠右侧，40dp完全过渡到左侧，绝不突变跳闪）
                            val progress = (distToRight / threshold40dp).coerceIn(0f, 1f)
                            val smooth = progress * progress * (3f - 2f * progress)
                            dxPx * (1f - 2f * smooth)
                        }
                    } else {
                        // 从屏幕左侧边缘触发
                        val distToLeft = touchViewX.coerceAtLeast(0f)
                        if (distToLeft >= threshold40dp) {
                            // 手指在离屏幕边缘超过40dp：完全显示在内侧（右侧，+dxPx）
                            dxPx
                        } else {
                            // 手指在离屏幕边缘40dp以内：指针实时跟随手指的移动速度逐渐平滑调整位置（0dp完全靠左侧，40dp完全过渡到右侧，绝不突变跳闪）
                            val progress = (distToLeft / threshold40dp).coerceIn(0f, 1f)
                            val smooth = progress * progress * (3f - 2f * progress)
                            dxPx * (-1f + 2f * smooth)
                        }
                    }

                    // 整体向左偏移25dp，自适应边缘防裁切（不得从屏幕中消失）
                    val targetX = (touchViewX + dx - leftOffsetPx).coerceIn(pointerRadius, screenWidth - pointerRadius)
                    val targetY = (touchViewY - dyPx).coerceIn(minSafeY + pointerRadius, screenHeight - pointerRadius)
                    return Point(targetX, targetY)
                }
                // === CUSTOM EXTENSION END ===

                val touchViewLocation = IntArray(2)
                touchView.getLocationOnScreen(touchViewLocation)
                val touchViewLeft = touchViewLocation[0]
                val touchViewTop = touchViewLocation[1]
                // Calculate relative position within touchView (clamped to 0..1)
                val relativeX = ((touchViewX - touchViewLeft) / touchWidth).coerceIn(0f, 1f)
                val relativeY = ((touchViewY - touchViewTop) / touchHeight).coerceIn(0f, 1f)

                // Map target position to full screen coordinate space
                val targetX = (relativeX * screenWidth).coerceIn(0f, screenWidth.toFloat())
                val targetY = (relativeY * screenHeight).coerceIn(0f, screenHeight.toFloat())
                return Point(targetX, targetY)
            }

            fun handleActionMove(event: MotionEvent) {
                // Offset by pointer half-dimensions so pointer tip aligns accurately
                val halfPointerWidth = view.width / 2
                val halfPointerHeight = view.height / 2

                val targetPoint = computePosition()

                // Apply position accounting for view size
                view.x = targetPoint.x - halfPointerWidth
                view.y = targetPoint.y - halfPointerHeight

                if (isDragMode) {
                    val currentPoint = Point(targetPoint.x, targetPoint.y)

                    if (hasStartedDragSelection) {
                        // 已经处于黄色虚线框选截图功能中：实时更新虚线框选，实线黄色预选栏保持消失
                        highlightRectView.updateRect(currentPoint)
                    } else if (potentialDragSelectRect != null) {
                        // 黄色指针捕捉到屏幕元素并显示黄色预选栏：这时拖动指针触发“虚线框选截图的功能”
                        if (elementCapturedPoint == null) {
                            elementCapturedPoint = Point(targetPoint.x, targetPoint.y)
                        }
                        val anchor = elementCapturedPoint ?: currentPoint
                        val moveDist = Math.hypot((targetPoint.x - anchor.x).toDouble(), (targetPoint.y - anchor.y).toDouble()).toFloat()
                        if (moveDist >= 15f.dp2px) {
                            hasStartedDragSelection = true
                            potentialDragSelectJob?.cancel()
                            potentialDragSelectJob = null
                            potentialDragSelectRect = null
                            potentialDragSelectSourceRect = null
                            pendingPotentialDragSelectRect = null
                            lastPotentialThinRect = null
                            confirmSelectionJob?.cancel()
                            isConfirmedSelection = false
                            // 同时实线黄色预选栏消失，只显示“虚线框选截图的功能”
                            highlightRectView.hidePotentialRect()
                            pointerIcon.imageTintList = ColorStateList.valueOf(Color.rgb(0xFF, 0xC1, 0x07))
                            highlightRectView.showRect(anchor)
                            highlightRectView.updateRect(currentPoint)
                        }
                    } else {
                        // 预选时（黄色指针）依然可以实时移动指针游走探测目标
                        elementCapturedPoint = Point(targetPoint.x, targetPoint.y)
                        loadPotentialDragSelectRect(targetPoint)
                    }

                    if (highlightRectView.isBackToOrigin) {
                        exitDragMode()
                    }
                }

                // Add a point to the trail at the center of the pointer
                trailView.addPoint(targetPoint.x, targetPoint.y)
            }

            fun handleActionUp(event: MotionEvent) {
                if (!isLongPressRunning) {
                    hoverLongPressJob?.cancel()
                    hoverLongPressJob = null
                }
                if (isDragMode) {
                    resetHoverLongPressHint()
                    // === CUSTOM EXTENSION START ===
                    confirmSelectionJob?.cancel()
                    confirmSelectionJob = null
                    val wasConfirmed = isConfirmedSelection
                    isConfirmedSelection = false
                    val targetConfirmedRect = potentialDragSelectRect
                    val hadStartedDrag = hasStartedDragSelection
                    hasStartedDragSelection = false
                    elementCapturedPoint = null
                    // === CUSTOM EXTENSION END ===
                    view.visibility = View.INVISIBLE
                    val currentSelectionRect = highlightRectView.currentSelectionRect(computePosition())
                    potentialDragSelectJob?.cancel()
                    potentialDragSelectJob = null
                    potentialDragSelectRect = null
                    potentialDragSelectSourceRect = null
                    pendingPotentialDragSelectRect = null
                    highlightRectView.hidePotentialRect()
                    highlightRectView.hideRect()

                    context.launch {
                        val effectiveSide = triggerSide
                            ?: (if (computePosition().x < context.resources.displayMetrics.widthPixels / 2f) TriggerSide.LEFT else TriggerSide.RIGHT)
                        if (hadStartedDrag && currentSelectionRect.width() >= 15f.dp2px && currentSelectionRect.height() >= 15f.dp2px) {
                            // 触发“虚线框选截图的功能”后无须等待框选栏变为红色（为真实选中），松手后即刻只捕捉“虚线框选截图”内的元素和截图
                            val dragRect = Rect(
                                min(currentSelectionRect.left, currentSelectionRect.right),
                                min(currentSelectionRect.top, currentSelectionRect.bottom),
                                max(currentSelectionRect.left, currentSelectionRect.right),
                                max(currentSelectionRect.top, currentSelectionRect.bottom)
                            )
                            produceInspireEvent(
                                InspireEvent(rect = dragRect, side = effectiveSide)
                            )
                        } else if (wasConfirmed && targetConfirmedRect != null) {
                            // 真实选中状态松开手指：选择被框选的界面元素
                            produceInspireEvent(
                                InspireEvent(rect = targetConfirmedRect, side = effectiveSide)
                            )
                        } else {
                            // 预选状态未变红且未拉出虚线框：安全退出不产生误操作
                            Log.d("PointerFloating", "Selection cancelled on UP (wasConfirmed=$wasConfirmed, hadStartedDrag=$hadStartedDrag)")
                        }
                    }
                } else if (pendingHoverLongPress) {
                    val targetPoint = pendingHoverPoint ?: computePosition()
                    pendingHoverLongPress = false
                    pendingHoverPoint = null
                    hoverLongPressJob = context.launch {
                        isLongPressRunning = true
                        try {
                            performPointerClick(targetPoint.x, targetPoint.y, 600L)
                        } finally {
                            isLongPressRunning = false
                            withContext(Dispatchers.Main) {
                                resetHoverLongPressHint()
                            }
                            hoverLongPressJob = null
                        }
                    }
                    trailView.clearTrail()
                } else {
                    val targetPoint = computePosition()
                    trailView.clearTrail()
                    Log.d(
                        "PointerFloating",
                        "handleActionUp: ${targetPoint.x}, ${targetPoint.y}"
                    )
                    context.launch {
                        delay(20)
                        performPointerClick(targetPoint.x, targetPoint.y)
                    }
                }
            }
        }

        inner class ViewPositionMonitor() {
            var enable = false
                private set

            // Thread-safe point history list
            private val historyPoints = Collections.synchronizedList(LinkedList<Point>())
            private var monitorJob: Job? = null
            private var checkJob: Job? = null

            // Hold weak reference to avoid memory leaks
            private val viewRef = WeakReference(view)

            // Coroutine exception handler
            private val errorHandler = CoroutineExceptionHandler { _, exception ->
                Log.e("ViewPositionMonitor", "Coroutine error: ${exception.message}", exception)
                stop()
            }

            private fun onStableChange(isStable: Boolean, stablePoint: Point) {
                // Dispatch UI operations to the main thread
                context.postOnMain {
                    if (isStable && !isDragMode) {
                        if (dragModeEnabled) {
                            enterDragMode()
                        } else {
                            prepareHoverLongPress(stablePoint)
                        }
                    }
                }
            }

            private fun analyzePosition() {
                synchronized(historyPoints) {
                    if (historyPoints.size < 2) return

                    // Calculate maximum displacement across recent samples
                    var maxDistance = 0f
                    val referencePosition = historyPoints.first()

                    for (position in historyPoints) {
                        val distance = distance(
                            referencePosition.x, referencePosition.y,
                            position.x, position.y
                        )
                        maxDistance = max(maxDistance, distance)
                    }

                    val isPositionStable = maxDistance < 10.dp2px

                    // Update checkJob atomically
                    val currentCheckJob = checkJob
                    if (isPositionStable && !isDragMode) {
                        if (currentCheckJob == null || !currentCheckJob.isActive) {
                            // Cancel previous check job and schedule a new check
                            currentCheckJob?.cancel()
                            checkJob = context.launch(Dispatchers.Default + errorHandler) {
                                try {
                                    Log.d("ViewPositionMonitor", "Starting stability check")
                                    val currentPoint = historyPoints.lastOrNull() ?: return@launch

                                    try {
                                        // 提高框选触发速度50%（由400ms缩短至200ms）
                                        withTimeout(300) {  // Timeout guard against stalled execution
                                            delay(200)
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        Log.d("ViewPositionMonitor", "Stability check timed out")
                                        return@launch
                                    }

                                    // Verify coroutine is still active
                                    ensureActive()

                                    // Read current view coordinates to avoid stale data
                                    val newView = viewRef.get() ?: return@launch
                                    val distance = distance(
                                        currentPoint.x,
                                        currentPoint.y,
                                        newView.x + newView.width / 2,
                                        newView.y + newView.height / 2
                                    )

                                    val isStable = distance < 5.dp2px
                                    if (!isStable && !dragModeEnabled) {
                                        context.postOnMain {
                                            if (!isLongPressRunning) {
                                                hoverLongPressJob?.cancel()
                                                hoverLongPressJob = null
                                            }
                                            resetHoverLongPressHint()
                                        }
                                    }
                                    onStableChange(
                                        isStable,
                                        Point(
                                            newView.x + newView.width / 2,
                                            newView.y + newView.height / 2
                                        )
                                    )
                                } finally {
                                    // Reset job state upon completion
                                    if (checkJob?.isActive == true && checkJob == currentCheckJob) {
                                        checkJob = null
                                    }
                                }
                            }
                        }
                    } else {
                        currentCheckJob?.cancel()
                        checkJob = null
                        if (!dragModeEnabled) {
                            context.postOnMain {
                                if (!isLongPressRunning) {
                                    hoverLongPressJob?.cancel()
                                    hoverLongPressJob = null
                                }
                                resetHoverLongPressHint()
                            }
                        }
                    }
                }
            }

            private var dragModeEnabled = false

            fun start() {
                context.launch(Dispatchers.Default + errorHandler) {
                    try {
                        // Stop existing tasks and ensure clean state
                        // Cancel all background jobs
                        monitorJob?.cancel()
                        checkJob?.cancel()
                        // Clear historical points
                        synchronized(historyPoints) {
                            historyPoints.clear()
                        }

                        dragModeEnabled =
                            context.settingsStore.readKey(KEY_ENABLE_POINTER_DRAG_MODE, false)
                        enable = true

                        // Start new monitoring loop
                        monitorJob = context.launch(Dispatchers.Default + errorHandler) {
                            try {
                                while (isActive) {
                                    delay(30)

                                    // Exit if view reference has been garbage-collected
                                    val currentView = viewRef.get()
                                    if (currentView == null) {
                                        Log.d(
                                            "ViewPositionMonitor",
                                            "View reference lost, stopping monitor"
                                        )
                                        cancel()
                                        break
                                    }

                                    // Safely append position to history list
                                    val x: Float
                                    val y: Float

                                    // Read view coordinates on main thread
                                    withContext(Dispatchers.Main) {
                                        x = currentView.x + currentView.width / 2
                                        y = currentView.y + currentView.height / 2
                                    }
                                    if (x >= 0 && y >= 0) {
                                        synchronized(historyPoints) {
                                            historyPoints.add(Point(x, y))
                                            if (historyPoints.size > 5) {
                                                historyPoints.removeFirstOrNull()
                                            }
                                        }
                                    }
                                    // Analyze position stability
                                    analyzePosition()
                                }
                            } catch (e: CancellationException) {
                                // Normal cancellation, no action required
                                Log.d("ViewPositionMonitor", "Monitor job cancelled")
                            } catch (e: Exception) {
                                Log.e("ViewPositionMonitor", "Error in monitor job", e)
                                throw e  // Re-throw to error handler
                            }
                        }

                        Log.d("ViewPositionMonitor", "Monitor started")
                    } catch (e: Exception) {
                        Log.e("ViewPositionMonitor", "Failed to start monitor", e)
                        stop()  // Ensure resources are cleaned up
                    }
                }
            }

            fun stop() {
                // Execute stop operations on main thread
                context.postOnMain {
                    try {
                        // Cancel coroutine jobs
                        monitorJob?.cancel()
                        checkJob?.cancel()
                        if (!isLongPressRunning) {
                            hoverLongPressJob?.cancel()
                            hoverLongPressJob = null
                        }
                        resetHoverLongPressHint()
                        enable = false

                        // Reset state
                        monitorJob = null
                        checkJob = null

                        // Clear historical points
                        synchronized(historyPoints) {
                            historyPoints.clear()
                        }

                        Log.d("ViewPositionMonitor", "Monitor stopped")
                    } catch (e: Exception) {
                        Log.e("ViewPositionMonitor", "Error while stopping monitor", e)
                    }
                }
            }

            // Called when the view lifecycle ends
            fun dispose() {
                stop()
                // Clear all references
                historyPoints.clear()
            }
        }
    }

    private suspend fun performPointerClick(
        x: Float,
        y: Float,
        duration: Long = 100L
    ): Boolean {
        // === CUSTOM EXTENSION START ===
        val simulateClick = context.settingsStore.readKey(
            io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.KEY_POINTER_SIMULATE_CLICK,
            true
        )
        if (!simulateClick) {
            return false
        }
        // === CUSTOM EXTENSION END ===
        context.setActionViewTouchEnabled(false)
        delay(50)
        return try {
            context.performClickAsync(x, y, duration)
        } finally {
            context.setActionViewTouchEnabled(true)
        }
    }
}
