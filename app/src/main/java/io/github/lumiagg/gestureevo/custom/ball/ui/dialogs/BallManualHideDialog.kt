package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.FloatingBallPreviewView

/**
 * 手动隐藏悬浮球设置独立弹窗
 * 支持主开关、唤起方式选择与实时生效
 */
object BallManualHideDialog {

    fun show(
        context: Context,
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

        tvTitle.text = "手动隐藏悬浮球设置"
        previewView.mode = FloatingBallPreviewView.PreviewMode.BALL_ICON
        previewView.config = workingConfig

        val hideView = LayoutInflater.from(context).inflate(R.layout.custom_ball_view_manual_hide, contentContainer, true)
        val switchManualHide = hideView.findViewById<MaterialSwitch>(R.id.switchManualHide)
        val rgWakeType = hideView.findViewById<RadioGroup>(R.id.rgWakeType)
        val rbWakeByNotification = hideView.findViewById<RadioButton>(R.id.rbWakeByNotification)
        val rbWakeByGesture = hideView.findViewById<RadioButton>(R.id.rbWakeByGesture)

        switchManualHide.isChecked = workingConfig.manualHideEnabled
        if (workingConfig.manualHideWakeType == 1) {
            rbWakeByGesture.isChecked = true
        } else {
            rbWakeByNotification.isChecked = true
        }

        fun updatePreviewState() {
            previewView.alpha = if (workingConfig.manualHideEnabled) 0.2f else 1.0f
        }
        updatePreviewState()

        switchManualHide.setOnCheckedChangeListener { _, isChecked ->
            workingConfig.manualHideEnabled = isChecked
            updatePreviewState()
        }

        rgWakeType.setOnCheckedChangeListener { _, checkedId ->
            workingConfig.manualHideWakeType = if (checkedId == R.id.rbWakeByGesture) 1 else 0
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnReset.setOnClickListener {
            workingConfig.manualHideEnabled = false
            workingConfig.manualHideWakeType = 0
            switchManualHide.isChecked = false
            rbWakeByNotification.isChecked = true
            updatePreviewState()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            workingConfig.save(context)
            FloatingBallService.notifyConfigChanged(context)
            onConfirmed(workingConfig)
            dialog.dismiss()
        }

        dialog.show()
    }
}
