package io.github.lumiagg.gestureevo.custom.pointer

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 * 无障碍节点轻量缓存与空间命中管理器
 * 
 * 核心设计：
 * 1. 节流与异步预构建：150ms 节流监听窗口及内容变化事件，推算严密父子交集剪辑边界（clippedBounds）；
 * 2. 扁平化纯内存结构：CachedNode 绝不强引用 AccessibilityNodeInfo，通过 AtomicReference 实现无锁毫秒级原子切换；
 * 3. 毫秒级坐标命中（Hit-Test）：按“面积最小 -> 层级最深”高灵敏定位目标；
 * 4. 稳健反查机制：treePath 快速寻址 + 特征校验（className/ID/bounds容差24px） + 活动窗口整树特征降级回溯。
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

    private suspend fun buildNodeCache(service: AccessibilityService) = withContext(Dispatchers.Default) {
        if (!isTracking || !isActive) return@withContext
        val roots = getWindowsRoots(service)
        if (roots.isEmpty()) return@withContext

        val displayMetrics = service.resources.displayMetrics
        val screenBounds = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)

        val flattenedList = ArrayList<CachedNode>(256)

        for (root in roots) {
            if (!isActive || !isTracking) break
            val windowId = root.windowId

            // 队列存储元素：节点、深度、从根节点到该节点的索引路径、父级剪辑边界
            val queue = ArrayDeque<TraversalItem>()
            queue.add(TraversalItem(root, 0, intArrayOf(), screenBounds))

            while (queue.isNotEmpty() && isActive && isTracking) {
                val item = queue.removeFirst()
                val node = item.node
                val depth = item.depth
                val currentPath = item.path
                val parentClip = item.parentClip

                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                val effectiveClip = Rect(parentClip)
                val hasIntersection = effectiveClip.intersect(nodeBounds)

                val isVisible = node.isVisibleToUser && hasIntersection && !nodeBounds.isEmpty

                if (isVisible) {
                    val area = nodeBounds.width().toLong() * nodeBounds.height().toLong()
                    if (area > 0) {
                        val viewId = runCatching { node.viewIdResourceName }.getOrNull()
                        val className = node.className?.toString().orEmpty()
                        val text = node.text?.toString()
                        val isEditable = node.isEditable

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
                                cachedText = text
                            )
                        )
                    }
                }

                val childCount = node.childCount
                val nextClip = if (hasIntersection) effectiveClip else parentClip
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    if (child.isVisibleToUser) {
                        val childPath = IntArray(currentPath.size + 1).apply {
                            System.arraycopy(currentPath, 0, this, 0, currentPath.size)
                            this[currentPath.size] = i
                        }
                        queue.add(TraversalItem(child, depth + 1, childPath, nextClip))
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
        val parentClip: Rect
    )

    /**
     * 毫秒级坐标命中测试：
     * 筛选 isVisible 且 clippedBounds 包含 (x, y) 的节点，按“面积最小 -> 层级最深”返回最优目标
     */
    fun hitTest(x: Int, y: Int): CachedNode? {
        val currentSnapshot = cacheReference.get()
        if (currentSnapshot.isEmpty()) return null

        val matched = currentSnapshot.filter { node ->
            node.isVisible && node.clippedBounds.contains(x, y) && node.area > 0
        }
        if (matched.isEmpty()) return null

        return matched.minWithOrNull(
            compareBy<CachedNode> { it.area }
                .thenByDescending { it.depth }
        )
    }

    /**
     * 针对微小选区矩形的命中测试
     */
    fun hitTest(rect: Rect): CachedNode? {
        val hitCenter = hitTest(rect.centerX(), rect.centerY())
        if (hitCenter != null) return hitCenter

        val currentSnapshot = cacheReference.get()
        if (currentSnapshot.isEmpty()) return null

        val matched = currentSnapshot.filter { node ->
            node.isVisible && Rect.intersects(node.clippedBounds, rect) && node.area > 0
        }
        if (matched.isEmpty()) return null

        return matched.minWithOrNull(
            compareBy<CachedNode> { it.area }
                .thenByDescending { it.depth }
        )
    }

    /**
     * 返回目标矩形（兼容原旧接口）
     */
    fun hitTestRect(x: Int, y: Int): Rect? = hitTest(x, y)?.boundsInScreen

    fun hitTestRect(rect: Rect): Rect? = hitTest(rect)?.boundsInScreen

    /**
     * 真实节点反查：
     * 优先通过 windowId + treePath 定位候选节点；
     * 必须校验 className + viewIdResourceName + boundsInScreen（24px 容差）；
     * 校验失败立即降级为在当前活动窗口中按特征遍历匹配。
     * 
     * 注意：返回的 AccessibilityNodeInfo 必须用完即释放，严禁外部长期强引用缓存。
     */
    fun findRealNode(service: AccessibilityService, cachedNode: CachedNode): AccessibilityNodeInfo? {
        val roots = getWindowsRoots(service)
        if (roots.isEmpty()) {
            return service.rootInActiveWindow?.let { root ->
                matchByFeatures(root, cachedNode)
            }
        }

        // 1. 尝试在对应 windowId 的根节点下按 treePath 高速索引寻址
        val targetRoot = roots.find { it.windowId == cachedNode.windowId } ?: roots.firstOrNull()
        if (targetRoot != null) {
            val candidate = navigateByTreePath(targetRoot, cachedNode.treePath)
            if (candidate != null && validateCandidateNode(candidate, cachedNode)) {
                return candidate
            }
        }

        // 2. treePath 寻址校验失败，全树降级特征扫描
        for (root in roots) {
            val found = matchByFeatures(root, cachedNode)
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
        // 校验类名
        val className = candidate.className?.toString().orEmpty()
        if (cachedNode.className.isNotEmpty() && className != cachedNode.className) {
            return false
        }

        // 校验 View ID（若缓存记录存在）
        val viewId = runCatching { candidate.viewIdResourceName }.getOrNull()
        if (!cachedNode.viewIdResourceName.isNullOrEmpty() && viewId != cachedNode.viewIdResourceName) {
            return false
        }

        // 校验屏幕边界容差（24px 容差以内）
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

    private fun getWindowsRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
        if (service is io.github.lumiagg.gestureevo.gesture.GestureService) {
            val list = runCatching { getNeedWindowsRoot(service) }.getOrNull()
            if (!list.isNullOrEmpty()) return list
        }
        val gestureService = io.github.lumiagg.gestureevo.gesture.GestureService.instance
        if (gestureService != null) {
            val list = runCatching { getNeedWindowsRoot(gestureService) }.getOrNull()
            if (!list.isNullOrEmpty()) return list
        }
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        return if (activeRoot != null) listOf(activeRoot) else emptyList()
    }
}
