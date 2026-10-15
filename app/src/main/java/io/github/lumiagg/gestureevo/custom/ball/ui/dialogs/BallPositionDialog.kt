package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.FloatingBallPreviewView
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px

/**
 * 悬浮球位置设置独立弹窗
 * 支持拖拽预览小球摆放坐标，也支持 XY 像素手动输入；支持重置默认位置与实时预览
 */
object BallPositionDialog {

    fun show(
        context: Context,
        onConfirmed: (updatedConfig: FloatingBallConfig) -> Unit
    ) {
        val currentConfig = FloatingBallConfig.load(context)
        val workingConfig = currentConfig.copy()

        val screenW = ScreenUtil.getScreenWidth(context)
        val screenH = ScreenUtil.getScreenHeight(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.custom_ball_dialog_common, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val previewView = dialogView.findViewById<FloatingBallPreviewView>(R.id.previewView)
        val contentContainer = dialogView.findViewById<LinearLayout>(R.id.contentContainer)
        val btnReset = dialogView.findViewById<MaterialButton>(R.id.btnReset)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirm)

        tvTitle.text = "悬浮球位置设置"
        previewView.mode = FloatingBallPreviewView.PreviewMode.SCREEN_POS
        previewView.screenRealWidth = screenW
        previewView.screenRealHeight = screenH
        previewView.config = workingConfig

        val posView = LayoutInflater.from(context).inflate(R.layout.custom_ball_view_position, contentContainer, true)
        val etPosX = posView.findViewById<TextInputEditText>(R.id.etPosX)
        val etPosY = posView.findViewById<TextInputEditText>(R.id.etPosY)

        var isUpdatingText = false

        fun syncToServiceAndPreview() {
            previewView.config = workingConfig
            workingConfig.save(context)
            FloatingBallService.notifyConfigChanged(context)
        }

        fun updateEditTexts(x: Int, y: Int) {
            isUpdatingText = true
            if (etPosX.text.toString() != x.toString()) {
                etPosX.setText(x.toString())
            }
            if (etPosY.text.toString() != y.toString()) {
                etPosY.setText(y.toString())
            }
            isUpdatingText = false
        }

        updateEditTexts(workingConfig.posX, workingConfig.posY)

        // 监听微缩视口拖拽
        previewView.onPositionDragged = { x, y ->
            workingConfig.posX = x
            workingConfig.posY = y
            updateEditTexts(x, y)
            syncToServiceAndPreview()
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingText) return
                val x = etPosX.text?.toString()?.toIntOrNull() ?: workingConfig.posX
                val y = etPosY.text?.toString()?.toIntOrNull() ?: workingConfig.posY
                workingConfig.posX = x.coerceIn(0, screenW)
                workingConfig.posY = y.coerceIn(0, screenH)
                syncToServiceAndPreview()
            }
        }
        etPosX.addTextChangedListener(textWatcher)
        etPosY.addTextChangedListener(textWatcher)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnReset.setOnClickListener {
            val ballSizePx = workingConfig.getActualSizeDp().dp2px.toInt()
            val defaultX = (screenW - ballSizePx).coerceAtLeast(0)
            val defaultY = screenH / 2
            workingConfig.posX = defaultX
            workingConfig.posY = defaultY
            updateEditTexts(defaultX, defaultY)
            syncToServiceAndPreview()
        }

        btnCancel.setOnClickListener {
            currentConfig.save(context)
            FloatingBallService.notifyConfigChanged(context)
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
