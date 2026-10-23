package io.github.lumiagg.gestureevo.custom.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GestureEVO LSPosed / Xposed 模块总入口
 */
class XposedInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "GestureEVO_Xposed"
        private const val PACKAGE_GESTURE_EVO = "io.github.lumiagg.gestureevo"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 当自身应用加载时：Hook 激活自检方法，标记模块生效
        if (lpparam.packageName == PACKAGE_GESTURE_EVO) {
            hookSelfModuleStatus(lpparam)
            return
        }

        // 2. 当系统框架加载时：挂钩系统按键注入与增强通道
        if (lpparam.packageName == "android") {
            hookSystemServer(lpparam)
        }
    }

    private fun hookSelfModuleStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "io.github.lumiagg.gestureevo.custom.xposed.XposedBridgeHelper",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            XposedBridge.log("$TAG: Successfully hooked GestureEVO module active status.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook self module status: ${t.message}")
        }
    }

    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 在 ActivityManagerService 或 ActivityThread 初始化完成后，注册系统级按键广播接收器
            XposedHelpers.findAndHookMethod(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader,
                "systemReady",
                Runnable::class.java,
                "com.android.server.am.ActivityManagerService\$SystemReadyFinishedListener",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return
                            registerSystemReceiver(context)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: Error in systemReady hook: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            // 兼容部分非原生 ROM，尝试备选 hook 点
            hookSystemServerFallback(lpparam)
        }
    }

    private fun hookSystemServerFallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader,
                "init",
                Context::class.java,
                "android.view.IWindowManager",
                "com.android.server.policy.WindowManagerPolicy\$WindowManagerFuncs",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        registerSystemReceiver(context)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: System server hook fallback failed: ${t.message}")
        }
    }

    private fun registerSystemReceiver(context: Context) {
        try {
            val filter = IntentFilter(XposedBridgeHelper.ACTION_SYSTEM_KEY_EVENT)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == XposedBridgeHelper.ACTION_SYSTEM_KEY_EVENT) {
                        val keyCode = intent.getIntExtra(XposedBridgeHelper.EXTRA_KEY_CODE, -1)
                        if (keyCode > 0) {
                            injectKeyEvent(keyCode)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            XposedBridge.log("$TAG: System key broadcast receiver registered successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to register system receiver: ${t.message}")
        }
    }

    private fun injectKeyEvent(keyCode: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)

            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val inputManager = XposedHelpers.callStaticMethod(inputManagerClass, "getInstance")
            val injectMethod = inputManagerClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            injectMethod.invoke(inputManager, down, 0)
            injectMethod.invoke(inputManager, up, 0)
            XposedBridge.log("$TAG: Injected system key event: keyCode=$keyCode")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: injectKeyEvent failed: ${t.message}")
        }
    }
}
