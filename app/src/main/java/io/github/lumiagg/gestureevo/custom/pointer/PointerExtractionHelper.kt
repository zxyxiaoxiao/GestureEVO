package io.github.lumiagg.gestureevo.custom.pointer

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 悬浮球与指针交互的统一提取桥接助手
 * 提供从“毫秒级坐标命中 -> 真实节点安全反查 -> 深度长文本提取 -> 结构化返回”的完整端到端链路
 */
object PointerExtractionHelper {

    private const val TAG = "PointerExtractionHelper"

    /**
     * 在屏幕坐标 (x, y) 处通过轻量缓存毫秒级命中目标并执行深度长文本提取
     */
    fun extractTextAtPoint(
        service: AccessibilityService,
        x: Int,
        y: Int,
        floatingWindowView: View? = null,
        windowManager: WindowManager? = null
    ): ExtractionResult {
        // 1. 纯内存空间命中
        val cachedNode = NodeCacheManager.hitTest(x, y)
        if (cachedNode == null) {
            Log.d(TAG, "No cached node hit at ($x, $y)")
            val emptyDiagnostics = NodeDiagnostics(
                className = "",
                viewIdResourceName = null,
                windowId = -1,
                textLength = 0,
                textSnippet = "",
                contentDescription = null,
                isEditable = false,
                isFocusable = false,
                isFocused = false,
                isScrollable = false,
                isPassword = false,
                availableActions = emptyList(),
                extrasKeys = emptyList(),
                suspectedTruncated = false
            )
            return ExtractionResult(
                text = null,
                isTruncated = false,
                source = ExtractionSource.FAILED,
                errorCode = ExtractionResult.ERROR_NODE_NOT_FOUND,
                errorMessage = "No node matched at coordinate ($x, $y)",
                diagnostics = emptyDiagnostics
            )
        }

        // 2. 真实节点反查与校验
        val realNode = NodeCacheManager.findRealNode(service, cachedNode)
        if (realNode == null) {
            Log.w(TAG, "Real node lookup failed for cached node: ${cachedNode.className}")
            val fallbackDiagnostics = NodeDiagnostics(
                className = cachedNode.className,
                viewIdResourceName = cachedNode.viewIdResourceName,
                windowId = cachedNode.windowId,
                textLength = cachedNode.cachedText?.length ?: 0,
                textSnippet = cachedNode.cachedText?.take(100).orEmpty(),
                contentDescription = null,
                isEditable = cachedNode.isEditable,
                isFocusable = false,
                isFocused = false,
                isScrollable = false,
                isPassword = false,
                availableActions = emptyList(),
                extrasKeys = emptyList(),
                suspectedTruncated = false
            )
            return ExtractionResult(
                text = cachedNode.cachedText,
                isTruncated = true,
                source = ExtractionSource.FALLBACK_CACHE,
                errorCode = ExtractionResult.ERROR_NODE_NOT_FOUND,
                errorMessage = "Failed to reverse lookup real AccessibilityNodeInfo",
                diagnostics = fallbackDiagnostics
            )
        }

        // 3. 执行深度提取主流程
        return try {
            DeepTextExtractor.getFullTextFromEditableNode(
                service = service,
                node = realNode,
                floatingWindowView = floatingWindowView,
                windowManager = windowManager
            )
        } finally {
            // 反查结果用完即释放，严禁外部长期强引用
            runCatching {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    realNode.recycle()
                }
            }
        }
    }
}
