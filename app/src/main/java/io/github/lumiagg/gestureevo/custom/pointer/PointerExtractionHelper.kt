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

    /**
     * 针对真实选中的控件位置（bounds），深度抓取该控件的所有文本内容
     * 确保比如“手机聊天输入框里输入了几百上千字，大部分被隐藏”，也能直接把整个输入框的所有内容抓取出来
     */
    fun extractFullTextForNodeBounds(
        service: AccessibilityService,
        bounds: android.graphics.Rect
    ): List<String>? {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        // 1. 优先通过中心点或矩形区域毫秒级命中缓存节点
        val cachedNode = NodeCacheManager.hitTest(centerX, centerY)
            ?: NodeCacheManager.hitTest(bounds)

        var realNode = if (cachedNode != null) {
            NodeCacheManager.findRealNode(service, cachedNode)
        } else null

        // 2. 若缓存未反查到真实节点，尝试从活动窗口根节点回退查找
        if (realNode == null) {
            val roots = if (service is io.github.lumiagg.gestureevo.gesture.GestureService) {
                io.github.lumiagg.gestureevo.inspire.getNeedWindowsRoot(service)
            } else emptyList()
            for (root in roots) {
                realNode = findTargetNodeInHierarchy(root, bounds)
                if (realNode != null) break
            }
        }

        if (realNode != null) {
            return try {
                // 如果是输入框或可编辑节点，执行深度长文本提取（包含强制刷新、兄弟子树聚合、选区提取等）
                if (realNode.isEditable || realNode.className?.contains("EditText", ignoreCase = true) == true) {
                    val extractionResult = DeepTextExtractor.getFullTextFromEditableNode(service, realNode)
                    val fullText = extractionResult.text
                    if (!fullText.isNullOrBlank()) {
                        Log.d(TAG, "extractFullTextForNodeBounds extracted ${fullText.length} chars from editable node")
                        return listOf(fullText)
                    }
                }
                // 非输入框或普通控件：直接读取完整的控件文本，杜绝字符位置过滤
                val nodeText = realNode.text?.toString()
                if (!nodeText.isNullOrBlank()) {
                    Log.d(TAG, "extractFullTextForNodeBounds read direct text: ${nodeText.take(50)}")
                    return listOf(nodeText)
                }
                // 若自身无直接文本但属于复合控件，聚合子树完整文本
                val aggregateBuilder = StringBuilder()
                collectAllChildText(realNode, aggregateBuilder)
                val aggregated = aggregateBuilder.toString().trim()
                if (aggregated.isNotBlank()) {
                    Log.d(TAG, "extractFullTextForNodeBounds read aggregated text: ${aggregated.take(50)}")
                    return listOf(aggregated)
                }
                null
            } finally {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                        @Suppress("DEPRECATION")
                        realNode.recycle()
                    }
                }
            }
        }
        return null
    }

    private fun findTargetNodeInHierarchy(
        root: AccessibilityNodeInfo,
        targetBounds: android.graphics.Rect
    ): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestMatch: AccessibilityNodeInfo? = null
        var minAreaDiff = Long.MAX_VALUE

        val nodeRect = android.graphics.Rect()
        while (!queue.isEmpty()) {
            val node = queue.poll() ?: continue
            node.getBoundsInScreen(nodeRect)
            if (android.graphics.Rect.intersects(nodeRect, targetBounds) || nodeRect == targetBounds) {
                if (!node.text.isNullOrBlank()) {
                    val areaDiff = Math.abs((nodeRect.width() * nodeRect.height()) - (targetBounds.width() * targetBounds.height())).toLong()
                    if (areaDiff < minAreaDiff) {
                        minAreaDiff = areaDiff
                        bestMatch = node
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return bestMatch
    }

    private fun collectAllChildText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val txt = child.text?.toString()
            if (!txt.isNullOrBlank()) {
                if (builder.isNotEmpty()) builder.append("\n")
                builder.append(txt)
            }
            collectAllChildText(child, builder)
        }
    }
}

