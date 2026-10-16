package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.FloatingBallPreviewView
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.readKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮球位置设置独立弹窗
 * 支持两种模式：“自定义位置”与“始终跟随触发器”（可单选全部或某个具体触发器）
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
        val chipGroupPositionMode = posView.findViewById<ChipGroup>(R.id.chipGroupPositionMode)
        val chipModeCustom = posView.findViewById<Chip>(R.id.chipModeCustom)
        val chipModeFollowTrigger = posView.findViewById<Chip>(R.id.chipModeFollowTrigger)

        val containerCustomPos = posView.findViewById<LinearLayout>(R.id.containerCustomPos)
        val etPosX = posView.findViewById<TextInputEditText>(R.id.etPosX)
        val etPosY = posView.findViewById<TextInputEditText>(R.id.etPosY)

        val containerFollowTrigger = posView.findViewById<LinearLayout>(R.id.containerFollowTrigger)
        val chipGroupTriggers = posView.findViewById<ChipGroup>(R.id.chipGroupTriggers)
        val chipTriggerAll = posView.findViewById<Chip>(R.id.chipTriggerAll)

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

        fun refreshModeUI() {
            if (workingConfig.positionMode == 0) {
                chipModeCustom.isChecked = true
                containerCustomPos.visibility = View.VISIBLE
                containerFollowTrigger.visibility = View.GONE
                previewView.visibility = View.VISIBLE
            } else {
                chipModeFollowTrigger.isChecked = true
                containerCustomPos.visibility = View.GONE
                containerFollowTrigger.visibility = View.VISIBLE
                previewView.visibility = View.GONE
            }
        }

        updateEditTexts(workingConfig.posX, workingConfig.posY)
        refreshModeUI()

        chipGroupPositionMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipModeFollowTrigger)) {
                workingConfig.positionMode = 1
            } else {
                workingConfig.positionMode = 0
            }
            refreshModeUI()
            syncToServiceAndPreview()
        }

        // 加载触发器列表供单选
        CoroutineScope(Dispatchers.Main).launch {
            val triggers = withContext(Dispatchers.IO) {
                val json = context.gestureStore.readKey("key_free_triggers", "")
                FreeTriggerConfig.toModelList(json) ?: emptyList()
            }

            chipGroupTriggers.removeAllViews()

            // 选项：全部触发器
            val allChip = Chip(context).apply {
                text = "跟随全部触发器"
                isCheckable = true
                id = View.generateViewId()
                isChecked = workingConfig.followTriggerTag.isBlank()
            }
            chipGroupTriggers.addView(allChip)

            // 各具体触发器
            triggers.forEach { trigger ->
                val trigChip = Chip(context).apply {
                    text = trigger.name.ifBlank { "触发器 (${trigger.tag.take(4)})" }
                    isCheckable = true
                    id = View.generateViewId()
                    isChecked = (workingConfig.followTriggerTag == trigger.tag)
                }
                chipGroupTriggers.addView(trigChip)
            }

            chipGroupTriggers.setOnCheckedStateChangeListener { group, checkedIds ->
                val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
                val checkedChip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
                if (checkedChip == allChip) {
                    workingConfig.followTriggerTag = ""
                } else {
                    val found = triggers.find { t ->
                        val name = t.name.ifBlank { "触发器 (${t.tag.take(4)})" }
                        name == checkedChip.text.toString()
                    }
                    workingConfig.followTriggerTag = found?.tag ?: ""
                }
                syncToServiceAndPreview()
            }
        }

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
            workingConfig.positionMode = 0
            workingConfig.followTriggerTag = ""
            workingConfig.posX = 0
            workingConfig.posY = screenH / 2
            updateEditTexts(workingConfig.posX, workingConfig.posY)
            refreshModeUI()
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
