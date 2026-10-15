package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

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
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.FloatingBallPreviewView
import io.github.lumiagg.gestureevo.settings.ui.showColorPickerDialog

/**
 * 菜单线条样式弹窗
 * 支持取色器选颜色、渐变开关、实线/虚线线型切换与实时预览
 */
object LineStyleDialog {

    fun show(
        activity: AppCompatActivity,
        onConfirmed: (updatedConfig: FloatingBallConfig) -> Unit
    ) {
        val currentConfig = FloatingBallConfig.load(activity)
        val workingConfig = currentConfig.copy()

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.custom_ball_dialog_common, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val previewView = dialogView.findViewById<FloatingBallPreviewView>(R.id.previewView)
        val contentContainer = dialogView.findViewById<LinearLayout>(R.id.contentContainer)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnReset)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

        tvTitle.text = "菜单线条样式"
        btnConfirm.text = "应用"
        previewView.mode = FloatingBallPreviewView.PreviewMode.LINE_STYLE
        previewView.config = workingConfig

        val lineStyleView = LayoutInflater.from(activity).inflate(R.layout.custom_ball_view_line_style, contentContainer, true)
        val viewLineColorIndicator = lineStyleView.findViewById<View>(R.id.viewLineColorIndicator)
        val btnPickLineColor = lineStyleView.findViewById<MaterialButton>(R.id.btnPickLineColor)
        val switchLineGradient = lineStyleView.findViewById<MaterialSwitch>(R.id.switchLineGradient)
        val rgLineDotted = lineStyleView.findViewById<RadioGroup>(R.id.rgLineDotted)
        val rbSolid = lineStyleView.findViewById<RadioButton>(R.id.rbSolid)
        val rbDotted = lineStyleView.findViewById<RadioButton>(R.id.rbDotted)

        fun updateColorIndicator(color: Int) {
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(2, Color.LTGRAY)
            }
            viewLineColorIndicator.background = drawable
        }

        fun syncToServiceAndPreview() {
            previewView.config = workingConfig
            workingConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
        }

        updateColorIndicator(workingConfig.lineColor)
        switchLineGradient.isChecked = workingConfig.lineGradientEnabled
        if (workingConfig.lineIsDotted) rbDotted.isChecked = true else rbSolid.isChecked = true

        btnPickLineColor.setOnClickListener {
            showColorPickerDialog(
                activity = activity,
                initialColor = workingConfig.lineColor,
                enableAlpha = true,
                onColorSelected = { color ->
                    workingConfig.lineColor = color
                    updateColorIndicator(color)
                    syncToServiceAndPreview()
                },
                onColorPreview = { color ->
                    workingConfig.lineColor = color
                    updateColorIndicator(color)
                    syncToServiceAndPreview()
                }
            )
        }

        switchLineGradient.setOnCheckedChangeListener { _, isChecked ->
            workingConfig.lineGradientEnabled = isChecked
            syncToServiceAndPreview()
        }

        rgLineDotted.setOnCheckedChangeListener { _, checkedId ->
            workingConfig.lineIsDotted = (checkedId == R.id.rbDotted)
            syncToServiceAndPreview()
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnReset.setOnClickListener {
            workingConfig.lineColor = Color.parseColor("#4285F4")
            workingConfig.lineGradientEnabled = true
            workingConfig.lineIsDotted = false
            updateColorIndicator(workingConfig.lineColor)
            switchLineGradient.isChecked = true
            rbSolid.isChecked = true
            syncToServiceAndPreview()
        }

        btnCancel.setOnClickListener {
            currentConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            workingConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
            onConfirmed(workingConfig)
            dialog.dismiss()
        }

        dialog.show()
    }
}
