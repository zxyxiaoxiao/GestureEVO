package io.github.lumiagg.gestureevo.custom.ball.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.gesture.GestureService

/**
 * 悬浮球运行所需权限校验与引导授权工具
 */
object FloatingBallPermissionHelper {

    /**
     * 校验悬浮窗权限
     */
    fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 校验通知权限
     */
    fun checkNotificationPermission(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 校验无障碍服务权限（用于手势全局动作分发）
     */
    fun checkAccessibilityPermission(context: Context): Boolean {
        val appContext = context.applicationContext
        val expectedService = ComponentName(appContext, GestureService::class.java)
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val accessibilityEnabled = Settings.Secure.getInt(
            appContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1
        return accessibilityEnabled && enabledServices.split(':').any {
            ComponentName.unflattenFromString(it) == expectedService
        }
    }

    /**
     * 引导授权悬浮窗
     */
    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    /**
     * 引导授权通知
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            activity.startActivity(intent)
        } else {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivity(intent)
        }
    }

    /**
     * 引导授权无障碍服务
     */
    fun requestAccessibilityPermission(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /**
     * 综合权限检查：如有缺失，弹出清晰的引导对话框；全部具备则执行 onAllGranted
     */
    fun checkAndGuidePermissions(activity: Activity, onAllGranted: () -> Unit) {
        if (!checkOverlayPermission(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("需要“显示在其他应用上层”权限")
                .setMessage("悬浮球需要悬浮窗权限以在屏幕任意界面显示与触控，请在接下来的系统设置中开启。")
                .setPositiveButton("前往开启") { _, _ ->
                    requestOverlayPermission(activity)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (!checkNotificationPermission(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("需要“通知”权限")
                .setMessage("悬浮球使用独立前台服务保持后台稳定运行，并提供通知栏快速唤起/隐藏控制。")
                .setPositiveButton("前往开启") { _, _ ->
                    requestNotificationPermission(activity)
                }
                .setNegativeButton("稍后开启") { _, _ ->
                    onAllGranted()
                }
                .show()
            return
        }

        if (!checkAccessibilityPermission(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("建议开启“无障碍服务”")
                .setMessage("悬浮球手势动作（返回、最近任务、主页等）依赖无障碍服务，未开启时部分全局手势可能无法执行。")
                .setPositiveButton("前往开启") { _, _ ->
                    requestAccessibilityPermission(activity)
                }
                .setNegativeButton("暂不开启") { _, _ ->
                    onAllGranted()
                }
                .show()
            return
        }

        onAllGranted()
    }
}
