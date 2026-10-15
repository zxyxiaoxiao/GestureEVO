package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.FloatingBallPreviewView

/**
 * 悬浮球与线条通用的滑块调节独立功能弹窗
 */
object BallSliderDialog {

    enum class SliderType {
        BALL_SIZE,         // 图标大小调节
        BALL_INNER_RATIO,  // 图标内部占比调节
        BALL_ALPHA,        // 图标透明度调节
        LINE_HEIGHT,       // 菜单线条高度调节
        LINE_WIDTH,        // 菜单线条宽度调节
        LINE_ALPHA         // 菜单线条透明度调节
    }

    fun show(
        context: Context,
        type: SliderType,
        onConfirmed: (updatedConfig: FloatingBallConfig) -> Unit
    ) {
        val currentConfig = FloatingBallConfig.load(context)
        val workingConfig = currentConfig.copy()

        val dialogView = LayoutInflater.from(context).inflate(R.layout.custom_ball_dialog_common, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val previewView = dialogView.findViewById<FloatingBallPreviewView>(R.id.previewView)
        val contentContainer = dialogView.findViewById<LinearLayout>(R.id.contentContainer)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnReset)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

        val sliderView = LayoutInflater.from(context).inflate(R.layout.custom_ball_view_slider, contentContainer, true)
        val tvSliderValue = sliderView.findViewById<TextView>(R.id.tvSliderValue)
        val slider = sliderView.findViewById<Slider>(R.id.slider)

        // 配置默认值与初始值
        val (titleText, defaultValue, initialValue, isLineMode) = when (type) {
            SliderType.BALL_SIZE -> Quadruple("图标大小调节", 100, workingConfig.sizePercent, false)
            SliderType.BALL_INNER_RATIO -> Quadruple("图标内部占比调节", 100, workingConfig.innerRatioPercent, false)
            SliderType.BALL_ALPHA -> Quadruple("图标透明度调节", 50, workingConfig.iconAlphaPercent, false)
            SliderType.LINE_HEIGHT -> Quadruple("菜单线条高度调节", 20, workingConfig.lineHeightPercent, true)
            SliderType.LINE_WIDTH -> Quadruple("菜单线条宽度调节", 59, workingConfig.lineWidthPercent, true)
            SliderType.LINE_ALPHA -> Quadruple("菜单线条透明度调节", 72, workingConfig.lineAlphaPercent, true)
        }

        tvTitle.text = titleText
        previewView.mode = if (isLineMode) FloatingBallPreviewView.PreviewMode.LINE_STYLE else FloatingBallPreviewView.PreviewMode.BALL_ICON
        previewView.config = workingConfig

        fun applyValueToConfig(value: Int) {
            when (type) {
                SliderType.BALL_SIZE -> workingConfig.sizePercent = value
                SliderType.BALL_INNER_RATIO -> workingConfig.innerRatioPercent = value
                SliderType.BALL_ALPHA -> workingConfig.iconAlphaPercent = value
                SliderType.LINE_HEIGHT -> workingConfig.lineHeightPercent = value
                SliderType.LINE_WIDTH -> workingConfig.lineWidthPercent = value
                SliderType.LINE_ALPHA -> workingConfig.lineAlphaPercent = value
            }
            tvSliderValue.text = "$value%"
            previewView.config = workingConfig
            // 实时通知服务刷新悬浮球
            workingConfig.save(context)
            FloatingBallService.notifyConfigChanged(context)
        }

        slider.value = initialValue.toFloat().coerceIn(0f, 100f)
        tvSliderValue.text = "$initialValue%"

        slider.addOnChangeListener { _, value, _ ->
            applyValueToConfig(value.toInt())
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnReset.setOnClickListener {
            slider.value = defaultValue.toFloat()
            applyValueToConfig(defaultValue)
        }

        btnCancel.setOnClickListener {
            // 恢复原参数并通知服务刷新
            currentConfig.save(context)
            FloatingBallService.notifyConfigChanged(context)
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            // 保存持久化
            workingConfig.save(context)
            FloatingBallService.notifyConfigChanged(context)
            onConfirmed(workingConfig)
            dialog.dismiss()
        }

        dialog.show()
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
