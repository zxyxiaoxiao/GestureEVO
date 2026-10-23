package io.github.lumiagg.gestureevo.custom.xposed

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.Keep

/**
 * LSPosed / Xposed 模块桥接管理器
 * 
 * 核心机制：
 * 1. 模块激活检测：isModuleActive() 默认返回 false，若 LSPosed 生效，会被 XposedInit hook 改写为返回 true；
 * 2. 优雅降级：在无 Xposed/LSPosed 环境下安全调用，完全不影响原有基于 AccessibilityService / Shizuku 的各项功能；
 * 3. 系统级按键通道：在 Xposed 激活时，可直接向系统广播请求执行瞬时返回、Home等高级特权操作。
 */
object XposedBridgeHelper {

    private const val TAG = "XposedBridgeHelper"
    const val ACTION_SYSTEM_KEY_EVENT = "io.github.lumiagg.gestureevo.action.XPOSED_SYSTEM_KEY"
    const val EXTRA_KEY_CODE = "extra_key_code"

    /**
     * 该方法由 LSPosed / Xposed 模块入口 Hook。
     * 当 Hook 成功时返回 true，否则返回 false。
     */
    @Keep
    @JvmStatic
    fun isModuleActive(): Boolean {
        return false
    }

    /**
     * 判断当前宿主中 LSPosed 模块是否处于激活并生效状态
     */
    fun isLSPosedActive(): Boolean {
        return runCatching { isModuleActive() }.getOrDefault(false)
    }

    /**
     * 系统级按键分发（当 Xposed 生效时使用系统广播通道瞬时派发，0延迟）
     */
    fun sendSystemKey(context: Context, keyCode: Int): Boolean {
        if (!isLSPosedActive()) return false
        return try {
            val intent = Intent(ACTION_SYSTEM_KEY_EVENT).apply {
                putExtra(EXTRA_KEY_CODE, keyCode)
                setPackage("android")
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "sendSystemKey via Xposed channel: keyCode=$keyCode")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "sendSystemKey via Xposed failed", t)
            false
        }
    }
}
