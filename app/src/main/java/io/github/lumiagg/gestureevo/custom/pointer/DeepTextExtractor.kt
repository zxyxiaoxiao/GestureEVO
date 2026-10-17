package io.github.lumiagg.gestureevo.custom.pointer

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 长文本输入框深度提取器
 * 
 * 核心策略：
 * 采用“先诊断 -> 强制刷新直接读取 -> 兄弟/子树聚合 -> 静默全选刷新 -> 全选复制读剪贴板 -> 逐级安全降级”的完整闭环。
 * 严格执行超时预算（Step 1-3 共享 80ms，Step 4 独立 120ms，总超时 200ms），绝不阻塞主线程，绝不泄露密码，无痕恢复剪贴板与选区高亮。
 */
object DeepTextExtractor {

    private const val TAG = "DeepTextExtractor"
    private const val BUDGET_STEP1_3_MS = 80L
    private const val BUDGET_STEP4_MS = 120L
    private const val BUDGET_TOTAL_MS = 200L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 深度提取长文本核心主方法
     */
    fun getFullTextFromEditableNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        floatingWindowView: View? = null,
        windowManager: WindowManager? = null
    ): ExtractionResult {
        val overallStartTime = SystemClock.uptimeMillis()

        // 模块二：先诊断
        val diagnostics = NodeDiagnosticsCollector.diagnoseNode(node)

        // 密码框安全中止：绝对不提取、不复制密码，保护用户隐私安全
        if (diagnostics.isPassword) {
            Log.w(TAG, "Extraction aborted: target is password field")
            return ExtractionResult(
                text = null,
                isTruncated = false,
                source = ExtractionSource.FAILED,
                errorCode = ExtractionResult.ERROR_PASSWORD_PROTECTED,
                errorMessage = "Password field access prohibited for security",
                diagnostics = diagnostics
            )
        }

        var bestText: String? = null
        var bestSource = ExtractionSource.FAILED

        // Step 1: 强制刷新与直接读取基准文本
        runCatching { node.refresh() }
        val baseText = getCleanText(node)
        bestText = baseText
        bestSource = ExtractionSource.DIRECT_REFRESH
        val candidateLength = baseText.length

        // Step 2: 父容器回溯与兄弟/子树节点拼接（零副作用优先）
        val step2Text = aggregateSubtreeText(node, baseText)
        if (step2Text.length > bestText.length) {
            bestText = step2Text
            bestSource = ExtractionSource.SUBTREE_AGGREGATION
        }

        // 检查 Step 1–3 耗时
        val elapsedStep1to2 = SystemClock.uptimeMillis() - overallStartTime
        if (elapsedStep1to2 >= BUDGET_STEP1_3_MS) {
            Log.w(TAG, "Step 1-2 budget exceeded: ${elapsedStep1to2}ms")
            return createTimeoutResult(bestText, bestSource, diagnostics)
        }

        // Step 3: 获取焦点与 ACTION_SET_SELECTION 全选测试
        val supportsSelection = diagnostics.availableActions.contains("ACTION_SET_SELECTION") ||
                diagnostics.isEditable
        var selectionRefreshedText: String? = null

        if (supportsSelection) {
            val origStart = runCatching { node.textSelectionStart }.getOrDefault(-1)
            val origEnd = runCatching { node.textSelectionEnd }.getOrDefault(-1)

            // 若未获焦且支持 ACTION_FOCUS，先执行焦点请求
            if (!diagnostics.isFocused && diagnostics.availableActions.contains("ACTION_FOCUS")) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            }

            // 全选策略分级
            val selectionSuccess = trySetSelectionStrategies(node, candidateLength, diagnostics.suspectedTruncated)

            if (selectionSuccess) {
                // 免剪贴板抢先刷新：全选成功后立即刷新并读取 node.text
                runCatching { node.refresh() }
                val afterSelectText = getCleanText(node)
                val isSignificantlyLonger = afterSelectText.length > candidateLength &&
                        afterSelectText.length > (bestText?.length ?: 0)
                val hasNoTruncationMarker = !afterSelectText.endsWith("...") && !afterSelectText.endsWith("…")

                if (isSignificantlyLonger && hasNoTruncationMarker) {
                    selectionRefreshedText = afterSelectText
                    bestText = afterSelectText
                    bestSource = ExtractionSource.SELECTION_REFRESH
                    // 立即恢复原光标选区，消除高亮
                    restoreSelection(node, origStart, origEnd)
                    Log.d(TAG, "Extraction succeeded via SELECTION_REFRESH without touching clipboard! Length=${bestText.length}")
                    return ExtractionResult(
                        text = bestText,
                        isTruncated = false,
                        source = bestSource,
                        errorCode = ExtractionResult.ERROR_NONE,
                        diagnostics = diagnostics
                    )
                }
            }

            // 若 Step 3 未直接解决且不准备进入 Step 4，或者在进入 Step 4 前恢复光标
            if (!diagnostics.availableActions.contains("ACTION_COPY")) {
                restoreSelection(node, origStart, origEnd)
            }
        }

        // 检查 Step 1–3 总体预算
        val elapsedStep1to3 = SystemClock.uptimeMillis() - overallStartTime
        if (elapsedStep1to3 >= BUDGET_STEP1_3_MS) {
            Log.w(TAG, "Step 1-3 total budget exceeded: ${elapsedStep1to3}ms")
            return createTimeoutResult(bestText, bestSource, diagnostics)
        }

        // Step 4: ACTION_COPY 与剪贴板无痕读取（含 Android 10+ 焦点破局）
        val remainingTotalBudget = BUDGET_TOTAL_MS - (SystemClock.uptimeMillis() - overallStartTime)
        val step4Budget = minOf(BUDGET_STEP4_MS, max(20L, remainingTotalBudget))

        var clipboardErrorCode = ExtractionResult.ERROR_NONE
        var clipboardErrorMessage: String? = null

        if (diagnostics.availableActions.contains("ACTION_COPY") && step4Budget > 30L) {
            val copyResult = performSafeClipboardCopy(
                service = service,
                node = node,
                baseLength = maxOf(candidateLength, bestText?.length ?: 0),
                floatingWindowView = floatingWindowView,
                windowManager = windowManager,
                timeoutMs = step4Budget
            )

            if (copyResult.success && !copyResult.copiedText.isNullOrBlank()) {
                Log.d(TAG, "Extraction succeeded via CLIPBOARD_COPY! Length=${copyResult.copiedText.length}")
                return ExtractionResult(
                    text = copyResult.copiedText,
                    isTruncated = false,
                    source = ExtractionSource.CLIPBOARD_COPY,
                    errorCode = ExtractionResult.ERROR_NONE,
                    diagnostics = diagnostics
                )
            } else {
                clipboardErrorCode = copyResult.errorCode
                clipboardErrorMessage = copyResult.errorMessage
            }
        }

        // Step 5: 安全降级策略
        // 1. 若 Step 2 的兄弟/子树聚合有效且优于基准文本
        if (step2Text.isNotBlank() && step2Text.length > candidateLength) {
            return ExtractionResult(
                text = step2Text,
                isTruncated = diagnostics.suspectedTruncated,
                source = ExtractionSource.SUBTREE_AGGREGATION,
                errorCode = clipboardErrorCode,
                errorMessage = clipboardErrorMessage,
                diagnostics = diagnostics
            )
        }

        // 2. 检查 contentDescription
        val contentDesc = diagnostics.contentDescription
        if (!contentDesc.isNullOrBlank() && contentDesc.length > (bestText?.length ?: 0)) {
            return ExtractionResult(
                text = contentDesc,
                isTruncated = false,
                source = ExtractionSource.CONTENT_DESCRIPTION,
                errorCode = clipboardErrorCode,
                errorMessage = clipboardErrorMessage,
                diagnostics = diagnostics
            )
        }

        // 3. 返回可见文本，强制标记 isTruncated
        val isTruncatedFinal = diagnostics.suspectedTruncated || (bestText?.length ?: 0) < candidateLength
        return ExtractionResult(
            text = if (!bestText.isNullOrBlank()) bestText else baseText,
            isTruncated = isTruncatedFinal,
            source = if (!bestText.isNullOrBlank()) bestSource else ExtractionSource.FALLBACK_CACHE,
            errorCode = if (clipboardErrorCode != ExtractionResult.ERROR_NONE) clipboardErrorCode else ExtractionResult.ERROR_NONE,
            errorMessage = clipboardErrorMessage ?: (if (isTruncatedFinal) "Text may be partially visible in viewport" else null),
            diagnostics = diagnostics
        )
    }

    private fun getCleanText(node: AccessibilityNodeInfo): String {
        val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { node.isShowingHintText }.getOrDefault(false)
        } else false
        if (isShowingHint) return ""

        val rawText = node.text?.toString() ?: return ""
        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { node.hintText?.toString() }.getOrNull()
        } else null
        if (hintText != null && rawText == hintText) return ""

        return rawText
    }

    /**
     * Step 2: 向上回溯父容器并拼接兄弟与子树文本
     */
    private fun aggregateSubtreeText(startNode: AccessibilityNodeInfo, baseText: String): String {
        var containerNode: AccessibilityNodeInfo? = null
        var curr: AccessibilityNodeInfo? = startNode
        var depth = 0

        while (depth < 3) {
            val parent = runCatching { curr?.parent }.getOrNull() ?: break
            val childCount = parent.childCount
            if (childCount > 1) {
                containerNode = parent
                break
            }
            curr = parent
            depth++
        }

        if (containerNode == null) return baseText

        val textBoundsList = mutableListOf<Pair<Rect, String>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(containerNode)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = getCleanText(node)
            if (text.isNotBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                textBoundsList.add(rect to text)
            }
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                if (child.isVisibleToUser) {
                    queue.add(child)
                }
            }
        }

        if (textBoundsList.isEmpty()) return baseText

        // 按视觉空间顺序排序：优先从上到下（容差 12px 视为同行），同行从左到右
        textBoundsList.sortWith(Comparator { o1, o2 ->
            val topDiff = o1.first.top - o2.first.top
            if (Math.abs(topDiff) > 12) {
                topDiff
            } else {
                o1.first.left - o2.first.left
            }
        })

        val sb = StringBuilder()
        var lastText = ""
        for (pair in textBoundsList) {
            val t = pair.second.trim()
            // 过滤完全包含的父子冗余
            if (t.isNotEmpty() && !lastText.contains(t) && !t.contains(lastText)) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(t)
                lastText = t
            }
        }

        val aggregated = sb.toString()
        return if (aggregated.length > baseText.length) aggregated else baseText
    }

    /**
     * Step 3: 分级执行全选
     */
    private fun trySetSelectionStrategies(
        node: AccessibilityNodeInfo,
        candidateLength: Int,
        suspectedTruncated: Boolean
    ): Boolean {
        val targets = if (!suspectedTruncated) {
            intArrayOf(candidateLength, 100_000, Integer.MAX_VALUE)
        } else {
            intArrayOf(100_000, Integer.MAX_VALUE, candidateLength)
        }

        for (end in targets) {
            if (end <= 0) continue
            val bundle = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            val ok = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)
            }.getOrDefault(false)

            if (ok) {
                return true
            }
        }
        return false
    }

    private fun restoreSelection(node: AccessibilityNodeInfo, start: Int, end: Int) {
        if (start < 0 || end < 0) return
        val bundle = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)
        }
    }

    private data class SafeCopyResult(
        val success: Boolean,
        val copiedText: String? = null,
        val errorCode: Int = ExtractionResult.ERROR_NONE,
        val errorMessage: String? = null
    )

    /**
     * Step 4: 剪贴板安全操作，结合 Android 10+ 临时焦点破局与现场无痕还原
     */
    private fun performSafeClipboardCopy(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        baseLength: Int,
        floatingWindowView: View?,
        windowManager: WindowManager?,
        timeoutMs: Long
    ): SafeCopyResult {
        val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return SafeCopyResult(false, null, ExtractionResult.ERROR_ACTION_FAILED, "ClipboardManager not available")

        var originalClip: ClipData? = null
        var focusBorrowed = false
        val origLp = floatingWindowView?.layoutParams as? WindowManager.LayoutParams

        // 1. Android 10+ 后台剪贴板读取限制破局：临时借用悬浮窗焦点
        if (floatingWindowView != null && windowManager != null && origLp != null) {
            try {
                val latchFocus = CountDownLatch(1)
                mainHandler.post {
                    try {
                        origLp.flags = origLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                        windowManager.updateViewLayout(floatingWindowView, origLp)
                        floatingWindowView.requestFocus()
                        focusBorrowed = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to borrow window focus for clipboard access", e)
                    } finally {
                        latchFocus.countDown()
                    }
                }
                latchFocus.await(40, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Focus borrow interrupted", e)
            }
        }

        try {
            // 备份原剪贴板
            originalClip = runCatching { cm.primaryClip }.getOrNull()
        } catch (e: SecurityException) {
            Log.w(TAG, "Android 10+ clipboard access denied on backup", e)
            return SafeCopyResult(
                success = false,
                errorCode = ExtractionResult.ERROR_CLIPBOARD_ACCESS_DENIED_ANDROID_10_PLUS,
                errorMessage = "Android 10+ restricted background clipboard access"
            )
        }

        val clipChangedLatch = CountDownLatch(1)
        val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            clipChangedLatch.countDown()
        }

        try {
            cm.addPrimaryClipChangedListener(clipListener)

            // 执行复制动作
            val actionResult = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            }.getOrDefault(false)

            if (!actionResult) {
                return SafeCopyResult(
                    success = false,
                    errorCode = ExtractionResult.ERROR_ACTION_FAILED,
                    errorMessage = "performAction(ACTION_COPY) returned false"
                )
            }

            // 等待剪贴板监听回调（最多 80ms 防卡死）
            val waitSuccess = clipChangedLatch.await(minOf(80L, timeoutMs), TimeUnit.MILLISECONDS)
            val currentClip = runCatching { cm.primaryClip }.getOrNull()

            if (currentClip != null && currentClip.itemCount > 0) {
                val clipText = currentClip.getItemAt(0).coerceToText(service)?.toString()
                if (!clipText.isNullOrBlank() && clipText.length > baseLength) {
                    return SafeCopyResult(
                        success = true,
                        copiedText = clipText
                    )
                }
            }

            return SafeCopyResult(
                success = false,
                errorCode = if (!waitSuccess) ExtractionResult.ERROR_TIMEOUT else ExtractionResult.ERROR_ACTION_FAILED,
                errorMessage = if (!waitSuccess) "Clipboard copy listener wait timed out" else "No new text acquired from clipboard"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Android 10+ clipboard access denied during copy", e)
            return SafeCopyResult(
                success = false,
                errorCode = ExtractionResult.ERROR_CLIPBOARD_ACCESS_DENIED_ANDROID_10_PLUS,
                errorMessage = "Android 10+ restricted background clipboard access"
            )
        } finally {
            runCatching { cm.removePrimaryClipChangedListener(clipListener) }

            // 现场恢复：将剪贴板内容还原为原有数据
            try {
                if (originalClip != null) {
                    cm.setPrimaryClip(originalClip)
                }
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL: Failed to restore user's original primary clip!", e)
            }

            // 现场恢复：归还悬浮窗 NOT_FOCUSABLE 属性
            if (focusBorrowed && floatingWindowView != null && windowManager != null && origLp != null) {
                mainHandler.post {
                    try {
                        origLp.flags = origLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        windowManager.updateViewLayout(floatingWindowView, origLp)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore window NOT_FOCUSABLE flag", e)
                    }
                }
            }
        }
    }

    private fun createTimeoutResult(
        bestText: String?,
        source: ExtractionSource,
        diagnostics: NodeDiagnostics
    ): ExtractionResult {
        return ExtractionResult(
            text = bestText,
            isTruncated = true,
            source = source,
            errorCode = ExtractionResult.ERROR_TIMEOUT,
            errorMessage = "Text extraction budget timed out within limit",
            diagnostics = diagnostics
        )
    }
}
