package io.github.lumiagg.gestureevo.custom.pointer

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.lumiagg.gestureevo.gesture.GestureService
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍节点轻量缓存与快速空间命中测试管理器
 * 
 * 核心原理：
 * 1. 触发指针后，在界面变化（窗口变化、内容变动等）时节流预构建并缓存当前窗口可访问的无障碍节点树；
 * 2. 将节点属性（布局边界、父子剪辑边界、类名、文本、可见性、深度层级等）解耦并提取为纯内存轻量对象；
 * 3. 指针悬停时不再现场 Binder 递归解析界面，直接在扁平列表上做纯内存坐标命中测试：
 *    筛选可见且包含坐标的节点，取面积最小且层级最深者作为目标，做到“快、准、稳”。
 */
object AccessibilityNodeCacheManager {

    data class CachedNode(
        val bounds: Rect,
        val clipBounds: Rect,
        val className: String,
        val text: String?,
        val contentDescription: String?,
        val isVisible: Boolean,
        val depth: Int,
        val area: Long,
        val isClickable: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isTracking = false

    @Volatile
    private var cachedNodes: List<CachedNode> = emptyList()

    private var rebuildJob: Job? = null
    private var lastEventTime = 0L
    private const val THROTTLE_DELAY_MS = 120L

    fun onPointerActivated(service: GestureService) {
        isTracking = true
        scheduleRebuild(service, immediate = true)
    }

    fun onPointerDeactivated() {
        isTracking = false
        rebuildJob?.cancel()
        rebuildJob = null
        cachedNodes = emptyList()
    }

    fun onAccessibilityEvent(service: GestureService, event: AccessibilityEvent) {
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

    private fun scheduleRebuild(service: GestureService, immediate: Boolean) {
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

    private suspend fun buildNodeCache(service: GestureService) = withContext(Dispatchers.Default) {
        if (!isTracking || !isActive) return@withContext
        val roots = runCatching { getNeedWindowsRoot(service) }.getOrNull() ?: return@withContext
        if (roots.isEmpty()) return@withContext

        val displayMetrics = service.resources.displayMetrics
        val screenBounds = Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)

        val flattenedList = ArrayList<CachedNode>(256)

        for (root in roots) {
            if (!isActive || !isTracking) break
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Pair<Int, Rect>>>()
            queue.add(root to (0 to screenBounds))

            while (queue.isNotEmpty() && isActive && isTracking) {
                val (node, pair) = queue.removeFirst()
                val depth = pair.first
                val parentClip = pair.second

                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                val effectiveClip = Rect(parentClip)
                val hasIntersection = effectiveClip.intersect(nodeBounds)

                val isVisible = node.isVisibleToUser && hasIntersection && !nodeBounds.isEmpty

                if (isVisible) {
                    val area = nodeBounds.width().toLong() * nodeBounds.height().toLong()
                    if (area > 0) {
                        flattenedList.add(
                            CachedNode(
                                bounds = nodeBounds,
                                clipBounds = effectiveClip,
                                className = node.className?.toString() ?: "",
                                text = node.text?.toString(),
                                contentDescription = node.contentDescription?.toString(),
                                isVisible = true,
                                depth = depth,
                                area = area,
                                isClickable = node.isClickable
                            )
                        )
                    }
                }

                // 子节点遍历与推算
                val childCount = node.childCount
                val nextClip = if (hasIntersection) effectiveClip else parentClip
                for (i in 0 until childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    if (child.isVisibleToUser) {
                        queue.add(child to (depth + 1 to nextClip))
                    }
                }
            }
        }

        if (isActive && isTracking) {
            cachedNodes = flattenedList
        }
    }

    /**
     * 在缓存节点上进行纯内存坐标命中测试：
     * 筛选包含指针坐标且可见的节点，取面积最小/层级最深者作为目标。
     */
    fun hitTest(x: Int, y: Int): Rect? {
        val currentSnapshot = cachedNodes
        if (currentSnapshot.isEmpty()) return null

        val matched = currentSnapshot.filter { node ->
            node.isVisible && node.clipBounds.contains(x, y) && node.area > 0
        }
        if (matched.isEmpty()) return null

        val target = matched.minWithOrNull(
            compareBy<CachedNode> { it.area }
                .thenByDescending { it.depth }
        )
        return target?.bounds
    }

    /**
     * 针对微小矩形选区的快速命中
     */
    fun hitTest(rect: Rect): Rect? {
        val hit = hitTest(rect.centerX(), rect.centerY())
        if (hit != null) return hit

        val currentSnapshot = cachedNodes
        if (currentSnapshot.isEmpty()) return null

        val matched = currentSnapshot.filter { node ->
            node.isVisible && Rect.intersects(node.clipBounds, rect) && node.area > 0
        }
        if (matched.isEmpty()) return null

        return matched.minWithOrNull(
            compareBy<CachedNode> { it.area }
                .thenByDescending { it.depth }
        )?.bounds
    }
}
