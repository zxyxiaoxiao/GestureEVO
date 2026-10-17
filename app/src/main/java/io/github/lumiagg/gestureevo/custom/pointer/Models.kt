package io.github.lumiagg.gestureevo.custom.pointer

import android.graphics.Rect

/**
 * 无障碍节点纯内存轻量快照
 * 严禁强引用 AccessibilityNodeInfo 以彻底避免内存泄漏
 */
data class CachedNode(
    val windowId: Int,
    val boundsInScreen: Rect,
    val clippedBounds: Rect, // 结合父容器推算出的实际可见剪辑边界
    val className: String,
    val viewIdResourceName: String?,
    val treePath: IntArray, // 从根节点出发的索引路径（用于快速反查）
    val depth: Int,
    val area: Long,
    val isVisible: Boolean,
    val isEditable: Boolean,
    val cachedText: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CachedNode
        if (windowId != other.windowId) return false
        if (boundsInScreen != other.boundsInScreen) return false
        if (clippedBounds != other.clippedBounds) return false
        if (className != other.className) return false
        if (viewIdResourceName != other.viewIdResourceName) return false
        if (!treePath.contentEquals(other.treePath)) return false
        if (depth != other.depth) return false
        if (area != other.area) return false
        if (isVisible != other.isVisible) return false
        if (isEditable != other.isEditable) return false
        if (cachedText != other.cachedText) return false
        return true
    }

    override fun hashCode(): Int {
        var result = windowId
        result = 31 * result + boundsInScreen.hashCode()
        result = 31 * result + clippedBounds.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + (viewIdResourceName?.hashCode() ?: 0)
        result = 31 * result + treePath.contentHashCode()
        result = 31 * result + depth
        result = 31 * result + area.hashCode()
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + isEditable.hashCode()
        result = 31 * result + (cachedText?.hashCode() ?: 0)
        return result
    }
}

/**
 * 文本提取来源枚举
 */
enum class ExtractionSource {
    DIRECT_REFRESH,
    SUBTREE_AGGREGATION,
    SELECTION_REFRESH,
    CLIPBOARD_COPY,
    CONTENT_DESCRIPTION,
    FALLBACK_CACHE,
    FAILED
}

/**
 * 节点能力诊断信息
 * 只保存纯文本、布尔值、长度等轻量字段，严禁持有 AccessibilityNodeInfo 引用
 */
data class NodeDiagnostics(
    val className: String,
    val viewIdResourceName: String?,
    val windowId: Int,
    val textLength: Int,
    val textSnippet: String,
    val contentDescription: String?,
    val isEditable: Boolean,
    val isFocusable: Boolean,
    val isFocused: Boolean,
    val isScrollable: Boolean,
    val isPassword: Boolean,
    val availableActions: List<String>,
    val extrasKeys: List<String>,
    val suspectedTruncated: Boolean
)

/**
 * 结构化文本提取结果
 */
data class ExtractionResult(
    val text: String?,
    val isTruncated: Boolean,
    val source: ExtractionSource,
    val errorCode: Int = ERROR_NONE,
    val errorMessage: String? = null,
    val diagnostics: NodeDiagnostics
) {
    companion object {
        const val ERROR_NONE = 0
        const val ERROR_TIMEOUT = 1
        const val ERROR_PASSWORD_PROTECTED = 2
        const val ERROR_CLIPBOARD_ACCESS_DENIED_ANDROID_10_PLUS = 3
        const val ERROR_NODE_NOT_FOUND = 4
        const val ERROR_ACTION_FAILED = 5
        const val ERROR_CLIPBOARD_RESTORE_FAILED = 6
    }
}
