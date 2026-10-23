package io.github.lumiagg.gestureevo.custom.pointer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.lumiagg.gestureevo.inspire.getNeedWindowsRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 无障碍节点轻量缓存与空间命中管理器（防遮盖与全量控件几何优化版）
 * 
 * 核心优化：
 * 1. 跨窗口 Z-Order 顶层防穿透：按 window.layer 降序严密筛选，悬浮窗展示时绝不穿透到底层全屏应用；
 * 2. 同窗口内部 Z-Order 遮蔽保护：逆序深度遍历建立图层顺序（zOrder），解决聊天详情覆盖会话列表时的底层穿透问题；
 * 3. 语义完整控件提升（Semantic Bounds）：当点击/框选中输入框、长文本或聊天气泡内的细微子元素时，自动提升为完整输入框/消息控件边界，确保选中栏与截图 100% 完整对应真实控件；
 * 4. 纯内存扁平快照：毫秒级原子切换，杜绝长生命周期引用 AccessibilityNodeInfo。
 */
object NodeCacheManager {

    private const val TAG = "NodeCacheManager"
    private const val THROTTLE_DELAY_MS = 150L
    private const val BOUNDS_TOLERANCE_PX = 24

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isTracking = false

    private val cacheReference = AtomicReference<List<CachedNode>>(emptyList())

    private var rebuildJob: Job? = null
    private var lastEventTime = 0L

    fun onPointerActivated(service: AccessibilityService) {
        isTracking = true
        scheduleRebuild(service, immediate = true)
    }

    fun onPointerDeactivated() {
        isTracking = false
        rebuildJob?.cancel()
        rebuildJob = null
        cacheReference.set(emptyList())
    }

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (!isTracking) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scheduleRebuild(service, immediate = false)
            }
        }
    }

    private fun scheduleRebuild(service: AccessibilityService, immediate: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!immediate && now - lastEventTime < THROTTLE_DELAY_MS && rebuildJob?.isActive == true) {
            return
        }
        lastEventTime = now

        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            if (!immediate) {
                delay(THROTTLE_DELAY_MS)
            }
            if (!isTracking || !isActive) return@launch
            buildNodeCache(service)
        }
    }

    private class WindowMeta(
        val windowId: Int,
        val layer: Int,
        val bounds: Rect,
        val rootNode: AccessibilityNodeInfo
    )

    private fun getWindowsRootMeta(service: AccessibilityService): List<WindowMeta> {
        val result = mutableListOf<WindowMeta>()
        val actualService = service as? io.github.lumiagg.gestureevo.gesture.GestureService
            ?: io.github.lumiagg.gestureevo.gesture.GestureService.instance
            ?: service

        val windows = runCatching { actualService.windows }.getOrNull()
        if (!windows.isNullOrEmpty()) {
            for (window in windows) {
                // 排除无障碍覆盖层（避免自身悬浮球、指针等 Overlay 干扰）
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = window.root ?: continue
                if (root.childCount == 0 && root.text.isNullOrBlank()) continue
                // 排除本软件自身的界面，防止将自己的视图识别为目标
                val pkgName = root.packageName?.toString()
                if (pkgName == "io.github.lumiagg.gestureevo") continue

                val winBounds = Rect()
                window.getBoundsInScreen(winBounds)
                result.add(
                    WindowMeta(
                        windowId = window.id,
                        layer = window.layer,
                        bounds = winBounds,
                        rootNode = root
                    )
                )
            }
        }
        if (result.isEmpty()) {
            val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
            if (activeRoot != null) {
                val dm = service.resources.displayMetrics
                result.add(
                    WindowMeta(
                        windowId = activeRoot.windowId,
                        layer = 0,
                        bounds = Rect(0, 0, dm.widthPixels, dm.heightPixels),
                        rootNode = activeRoot
                    )
                )
            }
        }
        // 按 layer 降序排列，图层越高的窗口排在最前面
        return result.sortedByDescending { it.layer }
    }

    private suspend fun buildNodeCache(service: AccessibilityService) = withContext(Dispatchers.Default) {
        if (!isTracking || !isActive) return@withContext
        val windowMetas = getWindowsRootMeta(service)
        if (windowMetas.isEmpty()) return@withContext

        val displayMetrics = service.resources.displayMetrics
        val screenBounds = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)

        val flattenedList = ArrayList<CachedNode>(256)
        var globalZOrder = 0

        for (win in windowMetas) {
            if (!isActive || !isTracking) break
            val windowId = win.windowId
            val windowLayer = win.layer

            // 队列存储元素：节点、深度、从根节点到该节点的索引路径、父级剪辑边界、所属语义控件祖先边界
            val queue = ArrayDeque<TraversalItem>()
            queue.add(TraversalItem(win.rootNode, 0, intArrayOf(), screenBounds, null))

            while (queue.isNotEmpty() && isActive && isTracking) {
                val item = queue.removeFirst()
                val node = item.node
                val depth = item.depth
                val currentPath = item.path
                val parentClip = item.parentClip
                val inheritedSemanticBounds = item.inheritedSemanticBounds

                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                val effectiveClip = Rect(parentClip)
                val hasIntersection = effectiveClip.intersect(nodeBounds)

                val isVisible = node.isVisibleToUser && hasIntersection && !nodeBounds.isEmpty
                val className = node.className?.toString().orEmpty()
                val text = node.text?.toString()
                val isEditable = node.isEditable || className.contains("EditText", ignoreCase = true)

                // 推算当前节点的语义边界：若为完整输入框或长文本控件，则确立为语义基准边界
                val isSemanticContainer = isEditable || 
                    (className.contains("TextView", ignoreCase = true) && !text.isNullOrBlank()) ||
                    (node.isClickable && !text.isNullOrBlank())

                val currentSemanticBounds = if (isSemanticContainer) {
                    Rect(nodeBounds)
                } else {
                    inheritedSemanticBounds ?: Rect(nodeBounds)
                }

                if (isVisible) {
                    val area = nodeBounds.width().toLong() * nodeBounds.height().toLong()
                    if (area > 0) {
                        val viewId = runCatching { node.viewIdResourceName }.getOrNull()
                        globalZOrder++

                        flattenedList.add(
                            CachedNode(
                                windowId = windowId,
                                boundsInScreen = nodeBounds,
                                clippedBounds = effectiveClip,
                                className = className,
                                viewIdResourceName = viewId,
                                treePath = currentPath,
                                depth = depth,
                                area = area,
                                isVisible = true,
                                isEditable = isEditable,
                                cachedText = text,
                                windowLayer = windowLayer,
                                zOrder = globalZOrder,
                                semanticBounds = currentSemanticBounds
                            )
                        )
                    }
                }

                val childCount = node.childCount
                val nextClip = if (hasIntersection) effectiveClip else parentClip

                // 逆序加入队列：同一父级下索引大的子控件后绘制（位于更上层）
                for (i in childCount - 1 downTo 0) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    if (child.isVisibleToUser) {
                        val childPath = IntArray(currentPath.size + 1).apply {
                            System.arraycopy(currentPath, 0, this, 0, currentPath.size)
                            this[currentPath.size] = i
                        }
                        queue.add(TraversalItem(child, depth + 1, childPath, nextClip, currentSemanticBounds))
                    }
                }
            }
        }

        if (isActive && isTracking) {
            cacheReference.set(flattenedList)
            Log.d(TAG, "Node cache built successfully. Cached count=${flattenedList.size}")
        }
    }

    private class TraversalItem(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val path: IntArray,
        val parentClip: Rect,
        val inheritedSemanticBounds: Rect?
    )

    /**
     * 毫秒级坐标命中测试（防穿透与顶层优先）
     */
    fun hitTest(x: Int, y: Int): CachedNode? {
        val currentSnapshot = cacheReference.get()
        if (currentSnapshot.isEmpty()) return null

        // 1. 跨窗口层级探测：按 windowLayer 从高到低（最顶层优先）严格筛选
        val windowsByLayer = currentSnapshot
            .groupBy { it.windowLayer }
            .entries
            .sortedByDescending { it.key }

        for ((_, nodesInWindow) in windowsByLayer) {
            val matchedInTopWindow = nodesInWindow.filter { node ->
                node.isVisible && node.clippedBounds.contains(x, y) && node.area > 0
            }
            if (matchedInTopWindow.isNotEmpty()) {
                // 成功在当前最顶层可见窗口命中！绝不穿透到下层被遮盖的窗口
                // 2. 同窗口内顶层优先：优先选取具有有效文本/输入框的表层控件
                val textNodes = matchedInTopWindow.filter { !it.cachedText.isNullOrBlank() || it.isEditable }
                val candidates = if (textNodes.isNotEmpty()) textNodes else matchedInTopWindow

                return candidates.maxWithOrNull(
                    compareBy<CachedNode> { it.zOrder }
                        .thenBy { -it.area }
                ) ?: matchedInTopWindow.minByOrNull { it.area }
            }
        }
        return null
    }

    /**
     * 针对微小选区矩形的命中测试
     */
    fun hitTest(rect: Rect): CachedNode? {
        val hitCenter = hitTest(rect.centerX(), rect.centerY())
        if (hitCenter != null) return hitCenter

        val currentSnapshot = cacheReference.get()
        if (currentSnapshot.isEmpty()) return null

        val windowsByLayer = currentSnapshot
            .groupBy { it.windowLayer }
            .entries
            .sortedByDescending { it.key }

        for ((_, nodesInWindow) in windowsByLayer) {
            val matchedInTopWindow = nodesInWindow.filter { node ->
                node.isVisible && Rect.intersects(node.clippedBounds, rect) && node.area > 0
            }
            if (matchedInTopWindow.isNotEmpty()) {
                val textNodes = matchedInTopWindow.filter { !it.cachedText.isNullOrBlank() || it.isEditable }
                val candidates = if (textNodes.isNotEmpty()) textNodes else matchedInTopWindow

                return candidates.maxWithOrNull(
                    compareBy<CachedNode> { it.zOrder }
                        .thenBy { -it.area }
                ) ?: matchedInTopWindow.minByOrNull { it.area }
            }
        }
        return null
    }

    /**
     * 返回目标矩形（严格返回提升后的语义完整控件边界，确保输入框/整条消息全量框选与截图）
     */
    fun hitTestRect(x: Int, y: Int): Rect? = hitTest(x, y)?.semanticBounds

    fun hitTestRect(rect: Rect): Rect? = hitTest(rect)?.semanticBounds

    /**
     * 真实节点反查
     */
    fun findRealNode(service: AccessibilityService, cachedNode: CachedNode): AccessibilityNodeInfo? {
        val metas = getWindowsRootMeta(service)
        if (metas.isEmpty()) {
            return service.rootInActiveWindow?.let { root ->
                matchByFeatures(root, cachedNode)
            }
        }

        // 1. 尝试在对应 windowId 的根节点下按 treePath 高速索引寻址
        val targetRoot = metas.find { it.windowId == cachedNode.windowId }?.rootNode ?: metas.firstOrNull()?.rootNode
        if (targetRoot != null) {
            val candidate = navigateByTreePath(targetRoot, cachedNode.treePath)
            if (candidate != null && validateCandidateNode(candidate, cachedNode)) {
                return candidate
            }
        }

        // 2. treePath 寻址校验失败，全树降级特征扫描
        for (meta in metas) {
            val found = matchByFeatures(meta.rootNode, cachedNode)
            if (found != null) {
                return found
            }
        }

        return null
    }

    private fun navigateByTreePath(root: AccessibilityNodeInfo, path: IntArray): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = root
        for (childIndex in path) {
            if (current == null) return null
            val childCount = current.childCount
            if (childIndex !in 0 until childCount) {
                return null
            }
            current = runCatching { current?.getChild(childIndex) }.getOrNull()
        }
        return current
    }

    private fun validateCandidateNode(candidate: AccessibilityNodeInfo, cachedNode: CachedNode): Boolean {
        val className = candidate.className?.toString().orEmpty()
        if (cachedNode.className.isNotEmpty() && className != cachedNode.className) {
            return false
        }

        val viewId = runCatching { candidate.viewIdResourceName }.getOrNull()
        if (!cachedNode.viewIdResourceName.isNullOrEmpty() && viewId != cachedNode.viewIdResourceName) {
            return false
        }

        val candidateBounds = Rect()
        candidate.getBoundsInScreen(candidateBounds)
        val cachedBounds = cachedNode.boundsInScreen

        val diffLeft = abs(candidateBounds.left - cachedBounds.left)
        val diffTop = abs(candidateBounds.top - cachedBounds.top)
        val diffRight = abs(candidateBounds.right - cachedBounds.right)
        val diffBottom = abs(candidateBounds.bottom - cachedBounds.bottom)

        return diffLeft <= BOUNDS_TOLERANCE_PX &&
                diffTop <= BOUNDS_TOLERANCE_PX &&
                diffRight <= BOUNDS_TOLERANCE_PX &&
                diffBottom <= BOUNDS_TOLERANCE_PX
    }

    private fun matchByFeatures(root: AccessibilityNodeInfo, cachedNode: CachedNode): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val cachedBounds = cachedNode.boundsInScreen
        var fallbackMatch: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val candidateBounds = Rect()
            node.getBoundsInScreen(candidateBounds)

            val diffLeft = abs(candidateBounds.left - cachedBounds.left)
            val diffTop = abs(candidateBounds.top - cachedBounds.top)
            val diffRight = abs(candidateBounds.right - cachedBounds.right)
            val diffBottom = abs(candidateBounds.bottom - cachedBounds.bottom)

            val boundsMatch = diffLeft <= BOUNDS_TOLERANCE_PX &&
                    diffTop <= BOUNDS_TOLERANCE_PX &&
                    diffRight <= BOUNDS_TOLERANCE_PX &&
                    diffBottom <= BOUNDS_TOLERANCE_PX

            if (boundsMatch) {
                val viewId = runCatching { node.viewIdResourceName }.getOrNull()
                val className = node.className?.toString().orEmpty()

                if (!cachedNode.viewIdResourceName.isNullOrEmpty() && viewId == cachedNode.viewIdResourceName) {
                    return node
                }
                if (className == cachedNode.className) {
                    return node
                }
                if (fallbackMatch == null) {
                    fallbackMatch = node
                }
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull()
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return fallbackMatch
    }
}
