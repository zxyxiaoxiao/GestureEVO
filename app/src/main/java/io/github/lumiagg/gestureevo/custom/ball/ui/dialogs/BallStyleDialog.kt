package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.FloatingBallPreviewView
import io.github.lumiagg.gestureevo.settings.ui.showColorPickerDialog
import java.io.File

/**
 * 悬浮球图标样式自定义弹窗
 * 支持预设圆形模板、相册上传自定义图片、调节边框粗细/颜色/圆角与实时预览
 */
object BallStyleDialog {

    private var activePreviewView: FloatingBallPreviewView? = null
    private var activeWorkingConfig: FloatingBallConfig? = null
    private var activeTvImagePath: TextView? = null

    fun onImageSelected(imagePath: String, context: Activity) {
        val cfg = activeWorkingConfig ?: return
        cfg.customIconPath = imagePath
        cfg.iconStyleType = 1
        activeTvImagePath?.text = File(imagePath).name
        activePreviewView?.config = cfg
        cfg.save(context)
        FloatingBallService.notifyConfigChanged(context)
    }

    fun show(
        activity: AppCompatActivity,
        onPickImageRequest: () -> Unit,
        onConfirmed: (updatedConfig: FloatingBallConfig) -> Unit
    ) {
        val currentConfig = FloatingBallConfig.load(activity)
        val workingConfig = currentConfig.copy()
        activeWorkingConfig = workingConfig

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.custom_ball_dialog_common, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val previewView = dialogView.findViewById<FloatingBallPreviewView>(R.id.previewView)
        val contentContainer = dialogView.findViewById<LinearLayout>(R.id.contentContainer)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnReset)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

        tvTitle.text = "悬浮球图标样式自定义"
        btnConfirm.text = "应用"
        previewView.mode = FloatingBallPreviewView.PreviewMode.BALL_ICON
        previewView.config = workingConfig
        activePreviewView = previewView

        val styleView = LayoutInflater.from(activity).inflate(R.layout.custom_ball_view_style, contentContainer, true)
        val rgStyleType = styleView.findViewById<RadioGroup>(R.id.rgStyleType)
        val rbPresetCircle = styleView.findViewById<RadioButton>(R.id.rbPresetCircle)
        val rbCustomImage = styleView.findViewById<RadioButton>(R.id.rbCustomImage)
        val layoutPickImage = styleView.findViewById<LinearLayout>(R.id.layoutPickImage)
        val tvImagePath = styleView.findViewById<TextView>(R.id.tvImagePath)
        val btnPickImage = styleView.findViewById<MaterialButton>(R.id.btnPickImage)

        activeTvImagePath = tvImagePath

        val tvBorderWidth = styleView.findViewById<TextView>(R.id.tvBorderWidth)
        val sliderBorderWidth = styleView.findViewById<Slider>(R.id.sliderBorderWidth)
        val viewBorderColorIndicator = styleView.findViewById<View>(R.id.viewBorderColorIndicator)
        val btnPickBorderColor = styleView.findViewById<MaterialButton>(R.id.btnPickBorderColor)
        val tvCornerRadius = styleView.findViewById<TextView>(R.id.tvCornerRadius)
        val sliderCornerRadius = styleView.findViewById<Slider>(R.id.sliderCornerRadius)

        fun updateColorIndicator(color: Int) {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(2, Color.LTGRAY)
            }
            viewBorderColorIndicator.background = drawable
        }

        fun syncToServiceAndPreview() {
            previewView.config = workingConfig
            workingConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
        }

        // 初始化状态
        if (workingConfig.iconStyleType == 1) {
            rbCustomImage.isChecked = true
            layoutPickImage.visibility = View.VISIBLE
        } else {
            rbPresetCircle.isChecked = true
            layoutPickImage.visibility = View.GONE
        }
        if (workingConfig.customIconPath.isNotBlank()) {
            tvImagePath.text = File(workingConfig.customIconPath).name
        }

        rgStyleType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbCustomImage) {
                workingConfig.iconStyleType = 1
                layoutPickImage.visibility = View.VISIBLE
            } else {
                workingConfig.iconStyleType = 0
                layoutPickImage.visibility = View.GONE
            }
            syncToServiceAndPreview()
        }

        btnPickImage.setOnClickListener {
            onPickImageRequest()
        }

        // 边框粗细
        sliderBorderWidth.value = workingConfig.borderWidthDp.coerceIn(0f, 10f)
        tvBorderWidth.text = "边框粗细: ${workingConfig.borderWidthDp} dp"
        sliderBorderWidth.addOnChangeListener { _, value, _ ->
            workingConfig.borderWidthDp = value
            tvBorderWidth.text = "边框粗细: $value dp"
            syncToServiceAndPreview()
        }

        // 边框颜色
        updateColorIndicator(workingConfig.borderColor)
        btnPickBorderColor.setOnClickListener {
            showColorPickerDialog(
                activity = activity,
                initialColor = workingConfig.borderColor,
                enableAlpha = true,
                onColorSelected = { color ->
                    workingConfig.borderColor = color
                    updateColorIndicator(color)
                    syncToServiceAndPreview()
                },
                onColorPreview = { color ->
                    workingConfig.borderColor = color
                    updateColorIndicator(color)
                    syncToServiceAndPreview()
                }
            )
        }

        // 圆角
        sliderCornerRadius.value = workingConfig.cornerRadiusDp.coerceIn(0f, 32f)
        tvCornerRadius.text = "圆角大小: ${workingConfig.cornerRadiusDp.toInt()} dp"
        sliderCornerRadius.addOnChangeListener { _, value, _ ->
            workingConfig.cornerRadiusDp = value
            tvCornerRadius.text = "圆角大小: ${value.toInt()} dp"
            syncToServiceAndPreview()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnReset.setOnClickListener {
            workingConfig.iconStyleType = 0
            workingConfig.borderWidthDp = 2f
            workingConfig.borderColor = Color.parseColor("#44888888")
            workingConfig.cornerRadiusDp = 24f
            rbPresetCircle.isChecked = true
            layoutPickImage.visibility = View.GONE
            sliderBorderWidth.value = 2f
            sliderCornerRadius.value = 24f
            updateColorIndicator(workingConfig.borderColor)
            syncToServiceAndPreview()
        }

        btnCancel.setOnClickListener {
            activePreviewView = null
            activeWorkingConfig = null
            activeTvImagePath = null
            currentConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            activePreviewView = null
            activeWorkingConfig = null
            activeTvImagePath = null
            workingConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
            onConfirmed(workingConfig)
            dialog.dismiss()
        }

        dialog.show()
    }
}
