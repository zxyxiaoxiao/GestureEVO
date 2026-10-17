package io.github.lumiagg.gestureevo.custom.pointer

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import io.github.lumiagg.gestureevo.gesture.GestureService

/**
 * 兼容旧调用入口的委托门面，内部全量升级委托给最新的 [NodeCacheManager]
 */
object AccessibilityNodeCacheManager {

    fun onPointerActivated(service: GestureService) {
        NodeCacheManager.onPointerActivated(service)
    }

    fun onPointerDeactivated() {
        NodeCacheManager.onPointerDeactivated()
    }

    fun onAccessibilityEvent(service: GestureService, event: AccessibilityEvent) {
        NodeCacheManager.onAccessibilityEvent(service, event)
    }

    fun hitTest(x: Int, y: Int): Rect? {
        return NodeCacheManager.hitTestRect(x, y)
    }

    fun hitTest(rect: Rect): Rect? {
        return NodeCacheManager.hitTestRect(rect)
    }
}
