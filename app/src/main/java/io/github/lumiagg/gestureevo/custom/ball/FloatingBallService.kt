package io.github.lumiagg.gestureevo.custom.ball

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.app.NotificationCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 独立前台服务：管理悬浮球渲染、状态切换、前台通知与事件监听
 */
class FloatingBallService : Service() {

    private var windowManager: WindowManager? = null
    private var ballView: FloatingBallView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isBallAdded = false

    private lateinit var config: FloatingBallConfig

    // 状态标记
    private var isTemporarilyHiddenByApp = false
    private var isTemporarilyHiddenByScreen = false
    private var isManualHidden = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (config.lockScreenHide) {
                        isTemporarilyHiddenByScreen = true
                        refreshBallVisibility()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    if (isTemporarilyHiddenByScreen) {
                        isTemporarilyHiddenByScreen = false
                        refreshBallVisibility()
                    }
                }
                ACTION_TOGGLE_MANUAL_HIDE -> {
                    toggleManualHide()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = FloatingBallConfig.load(this)
        isManualHidden = config.manualHideEnabled

        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_TOGGLE_MANUAL_HIDE)
        }
        registerReceiver(screenReceiver, filter)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (config.isEnabled && canDrawOverlays()) {
            attachBallView()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIG_CHANGED -> {
                config = FloatingBallConfig.load(this)
                isManualHidden = config.manualHideEnabled
                updateNotification()
                if (config.isEnabled && canDrawOverlays()) {
                    if (!isBallAdded) {
                        attachBallView()
                    } else {
                        updateBallView()
                    }
                } else {
                    detachBallView()
                }
            }
            ACTION_TOGGLE_MANUAL_HIDE -> {
                toggleManualHide()
            }
            ACTION_FOREGROUND_APP_CHANGED -> {
                val pkgName = intent.getStringExtra(EXTRA_PKG_NAME) ?: ""
                handleForegroundAppChanged(pkgName)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        detachBallView()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮球服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮球后台运行与唤起控制"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(ACTION_TOGGLE_MANUAL_HIDE).apply {
            setPackage(packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pIntent = PendingIntent.getBroadcast(this, 1001, toggleIntent, flags)

        val statusText = if (isManualHidden) "悬浮球已手动隐藏（点击唤起）" else "悬浮球正在运行中（点击隐藏）"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮球")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.baseline_circle_24)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun toggleManualHide() {
        isManualHidden = !isManualHidden
        config.manualHideEnabled = isManualHidden
        config.save(this)
        updateNotification()
        refreshBallVisibility()
    }

    fun handleForegroundAppChanged(pkgName: String) {
        val shouldHide = config.autoHideApps.contains(pkgName)
        if (isTemporarilyHiddenByApp != shouldHide) {
            isTemporarilyHiddenByApp = shouldHide
            refreshBallVisibility()
        }
    }

    private fun refreshBallVisibility() {
        val view = ballView ?: return
        val visible = !isManualHidden && !isTemporarilyHiddenByApp && !isTemporarilyHiddenByScreen && config.isEnabled
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun attachBallView() {
        if (isBallAdded) return
        val wm = windowManager ?: return

        val sizePx = config.getActualSizeDp().dp2px.toInt()
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams().apply {
            type = windowType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.START or Gravity.TOP
            width = sizePx
            height = sizePx
            x = config.posX
            y = config.posY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        this.layoutParams = params

        val view = FloatingBallView(this, config).apply {
            onActionTrigger = { actionLink -> dispatchAction(actionLink) }
            onDragMove = { dx, dy -> updatePositionDelta(dx, dy) }
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
            isBallAdded = true
            refreshBallVisibility()
            Log.d(TAG, "FloatingBall attached at (${params.x}, ${params.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach FloatingBall", e)
        }
    }

    private fun updateBallView() {
        val view = ballView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return

        val sizePx = config.getActualSizeDp().dp2px.toInt()
        params.width = sizePx
        params.height = sizePx
        params.x = config.posX
        params.y = config.posY

        view.updateConfig(config)
        try {
            wm.updateViewLayout(view, params)
            refreshBallVisibility()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update layout params", e)
        }
    }

    private fun detachBallView() {
        if (!isBallAdded) return
        try {
            ballView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove view", e)
        } finally {
            ballView = null
            layoutParams = null
            isBallAdded = false
        }
    }

    private fun updatePositionDelta(deltaX: Float, deltaY: Float) {
        val params = layoutParams ?: return
        val view = ballView ?: return
        val wm = windowManager ?: return

        params.x += deltaX.toInt()
        params.y += deltaY.toInt()

        val screenHeight = ScreenUtil.getScreenHeight(this)
        val size = config.getActualSizeDp().dp2px.toInt()
        params.y = params.y.coerceIn(0, (screenHeight - size).coerceAtLeast(0))

        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "updatePositionDelta error", e)
        }
    }

    private fun snapToEdge() {
        val params = layoutParams ?: return
        val view = ballView ?: return
        val wm = windowManager ?: return

        val screenWidth = ScreenUtil.getScreenWidth(this)
        val ballSize = config.getActualSizeDp().dp2px.toInt()
        val currentX = params.x
        val centerX = currentX + ballSize / 2

        val targetX = if (centerX < screenWidth / 2) 0 else (screenWidth - ballSize).coerceAtLeast(0)

        ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
            start()
        }

        config.posX = targetX
        config.posY = params.y
        config.save(this)
    }

    private fun saveCurrentPosition() {
        val params = layoutParams ?: return
        config.posX = params.x
        config.posY = params.y
        config.save(this)
    }

    private fun dispatchAction(actionLink: String) {
        if (actionLink.isBlank()) return
        CoroutineScope(Dispatchers.Main).launch {
            try {
                ActionProxy(applicationContext, actionLink).execute()
            } catch (e: Exception) {
                Log.e(TAG, "dispatchAction failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "FloatingBallService"
        private const val CHANNEL_ID = "channel_floating_ball"
        private const val NOTIFICATION_ID = 20002

        const val ACTION_CONFIG_CHANGED = "io.github.lumiagg.gestureevo.custom.ball.ACTION_CONFIG_CHANGED"
        const val ACTION_TOGGLE_MANUAL_HIDE = "io.github.lumiagg.gestureevo.custom.ball.ACTION_TOGGLE_MANUAL_HIDE"
        const val ACTION_FOREGROUND_APP_CHANGED = "io.github.lumiagg.gestureevo.custom.ball.ACTION_FOREGROUND_APP_CHANGED"
        const val EXTRA_PKG_NAME = "extra_pkg_name"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        var instance: FloatingBallService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java)
            context.stopService(intent)
        }

        fun notifyConfigChanged(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_CONFIG_CHANGED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun notifyForegroundApp(context: Context, pkgName: String) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_FOREGROUND_APP_CHANGED
                putExtra(EXTRA_PKG_NAME, pkgName)
            }
            if (instance != null) {
                instance?.handleForegroundAppChanged(pkgName)
            } else {
                context.startService(intent)
            }
        }
    }
}
