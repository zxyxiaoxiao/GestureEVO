package io.github.lumiagg.gestureevo.custom.pointer

import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object NodeDiagnosticsCollector {

    private const val TAG = "NodeDiagnostics"

    /**
     * 诊断指定无障碍节点能力，生成轻量诊断快照并打印日志
     * 禁止持有 AccessibilityNodeInfo 引用以避免内存泄漏
     */
    fun diagnoseNode(node: AccessibilityNodeInfo): NodeDiagnostics {
        val className = node.className?.toString().orEmpty()
        val viewIdResourceName = runCatching { node.viewIdResourceName }.getOrNull()
        val windowId = node.windowId

        val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { node.isShowingHintText }.getOrDefault(false)
        } else false

        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { node.hintText?.toString() }.getOrNull()
        } else null

        val rawText = node.text?.toString()
        val actualText = if (isShowingHint) {
            // 当前显示的为占位符提示词，并非实际正文
            ""
        } else if (rawText != null && hintText != null && rawText == hintText) {
            // 与提示词完全一致，视为占位符
            ""
        } else {
            rawText.orEmpty()
        }

        val textLength = actualText.length
        val textSnippet = if (textLength > 100) actualText.substring(0, 100) else actualText
        val contentDesc = node.contentDescription?.toString()

        val isEditable = node.isEditable
        val isFocusable = node.isFocusable
        val isFocused = node.isFocused
        val isScrollable = node.isScrollable
        val isPassword = node.isPassword

        val actionNames = mutableListOf<String>()
        val actions = runCatching { node.actionList }.getOrNull().orEmpty()
        for (action in actions) {
            when (action.id) {
                AccessibilityNodeInfo.ACTION_FOCUS -> actionNames.add("ACTION_FOCUS")
                AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> actionNames.add("ACTION_CLEAR_FOCUS")
                AccessibilityNodeInfo.ACTION_SET_SELECTION -> actionNames.add("ACTION_SET_SELECTION")
                AccessibilityNodeInfo.ACTION_COPY -> actionNames.add("ACTION_COPY")
                AccessibilityNodeInfo.ACTION_PASTE -> actionNames.add("ACTION_PASTE")
                AccessibilityNodeInfo.ACTION_CUT -> actionNames.add("ACTION_CUT")
                AccessibilityNodeInfo.ACTION_SET_TEXT -> actionNames.add("ACTION_SET_TEXT")
                AccessibilityNodeInfo.ACTION_CLICK -> actionNames.add("ACTION_CLICK")
                else -> {
                    val label = action.label?.toString()
                    if (!label.isNullOrBlank()) {
                        actionNames.add("CUSTOM_${action.id}_$label")
                    }
                }
            }
        }

        val extrasKeys = mutableListOf<String>()
        runCatching {
            val extras = node.extras
            if (extras != null) {
                for (key in extras.keySet()) {
                    extrasKeys.add(key)
                }
            }
        }

        // 截断预判：如果 getText() 以 ... 或 … 结尾，或者节点可滚动但文本很短，标记为疑似截断
        val suspectedTruncated = actualText.endsWith("...") ||
                actualText.endsWith("…") ||
                (isScrollable && textLength in 1..80)

        val diagnostics = NodeDiagnostics(
            className = className,
            viewIdResourceName = viewIdResourceName,
            windowId = windowId,
            textLength = textLength,
            textSnippet = textSnippet,
            contentDescription = contentDesc,
            isEditable = isEditable,
            isFocusable = isFocusable,
            isFocused = isFocused,
            isScrollable = isScrollable,
            isPassword = isPassword,
            availableActions = actionNames,
            extrasKeys = extrasKeys,
            suspectedTruncated = suspectedTruncated
        )

        Log.d(TAG, "DiagnoseNode Result: class=$className, id=$viewIdResourceName, editable=$isEditable, isPassword=$isPassword, textLen=$textLength, suspectedTruncated=$suspectedTruncated, actions=$actionNames")
        return diagnostics
    }
}
