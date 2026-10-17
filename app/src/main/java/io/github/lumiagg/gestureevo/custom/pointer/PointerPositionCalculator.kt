package io.github.lumiagg.gestureevo.custom.pointer

import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.dp2px

/**
 * 指针相对于手指/悬浮球的位置实时动态计算器
 *
 * 轨迹规则：
 * 1. 手指刚触发出指针时（dist ~ 0dp）：指针显示在悬浮球右上角（+dxRight, -dyTop）
 * 2. 手指慢慢往屏幕中间移动时：指针实时跟随手指的移动速度逐渐调整位置
 * 3. 手指滑动到离屏幕边缘 40dp 的位置时：指针刚好移动到悬浮球正上方（0, -dyTop）
 * 4. 手指离屏幕边缘距离超出 40dp 后：指针持续向左上角移动
 * 5. 离屏幕边缘距离达到 70dp 时：指针刚好移动到左上角（现在的位置：-dxLeft, -dyTop）
 * 6. 超过 70dp 时：保持在左上角（现在的位置）
 * 7. 若悬浮球向屏幕边缘折返：按原路线实时速度反向逐渐调整位置，到达屏幕边缘（路线和刚触发时相反）
 */
object PointerPositionCalculator {

    fun computePosition(
        touchX: Float,
        touchY: Float,
        screenWidth: Int,
        screenHeight: Int,
        screenStatusHeight: Int,
        pointerRadius: Float,
        triggerSide: String?,
        initialTouchX: Float
    ): Point {
        val distPx = 41.5f.dp2px * 1.3f // 间距在现有基础上提升30% (约 53.95dp)
        val rad = Math.toRadians(40.0)
        val dxPx = (distPx * Math.cos(rad)).toFloat() // 约 41.328dp
        val dyPx = (distPx * Math.sin(rad) * 1.5).toFloat() // 垂直整体上移50% (约 52.017dp)
        val leftOffsetPx = 25f.dp2px // 整体向左偏移25dp

        // 左上角（现在的位置）：相对手指水平向左偏移 dxLeft (约 66.3dp)
        val dxLeft = dxPx + leftOffsetPx
        // 右上角：相对手指水平向右偏移 dxRight (约 41.3dp)
        val dxRight = dxPx
        // 垂直向上偏移
        val dyTop = dyPx

        val threshold40dp = 40f.dp2px
        val threshold70dp = 70f.dp2px

        val isRightTrigger = if (triggerSide != null) {
            triggerSide.equals(TriggerSide.RIGHT, ignoreCase = true)
        } else {
            if (initialTouchX >= 0f) initialTouchX >= screenWidth / 2f else touchX >= screenWidth / 2f
        }

        val distToEdge = if (isRightTrigger) {
            (screenWidth - touchX).coerceAtLeast(0f)
        } else {
            touchX.coerceAtLeast(0f)
        }

        // 根据手指离边缘的距离计算水平偏移 dx：
        // 0dp -> +dxRight (右上角)
        // 40dp -> 0 (正上方)
        // 70dp -> -dxLeft (左上角现在的位置)
        // >70dp -> -dxLeft (保持在左上角)
        val dx = when {
            distToEdge <= threshold40dp -> {
                // [0, 40dp]: 从右上角(+dxRight)实时逐渐平滑过渡到正上方(0)
                val progress = if (threshold40dp > 0f) (distToEdge / threshold40dp).coerceIn(0f, 1f) else 1f
                dxRight * (1f - progress)
            }
            distToEdge <= threshold70dp -> {
                // (40dp, 70dp]: 从正上方(0)持续向左上角移动，直到70dp刚好达到左上角(-dxLeft)
                val progress = if (threshold70dp > threshold40dp) {
                    ((distToEdge - threshold40dp) / (threshold70dp - threshold40dp)).coerceIn(0f, 1f)
                } else 1f
                - (progress * dxLeft)
            }
            else -> {
                // > 70dp: 保持在左上角（现在的位置）
                -dxLeft
            }
        }

        val minSafeY = if (screenStatusHeight > 0) screenStatusHeight.toFloat() else 0f
        // 边缘防裁切（不得从屏幕中消失）
        val targetX = (touchX + dx).coerceIn(pointerRadius, screenWidth - pointerRadius)
        val targetY = (touchY - dyTop).coerceIn(minSafeY + pointerRadius, screenHeight - pointerRadius)

        return Point(targetX, targetY)
    }
}
