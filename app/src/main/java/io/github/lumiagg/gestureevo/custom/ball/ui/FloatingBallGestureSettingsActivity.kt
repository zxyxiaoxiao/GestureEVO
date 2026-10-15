package io.github.lumiagg.gestureevo.custom.ball.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.settings.GestureAddActionActivity
import io.github.lumiagg.gestureevo.utils.GestureActionLink

/**
 * 悬浮球手势动作配置页面
 * 套用自由触发器动作选择方案，支持单击/双击/长按/滑动手势真实绑定系统与自定义动作
 */
class FloatingBallGestureSettingsActivity : AppCompatActivity() {

    private lateinit var config: FloatingBallConfig

    private lateinit var tvClickActionVal: TextView
    private lateinit var tvDoubleClickActionVal: TextView
    private lateinit var tvLongPressActionVal: TextView
    private lateinit var tvSwipeUpActionVal: TextView
    private lateinit var tvSwipeDownActionVal: TextView
    private lateinit var tvSwipeLeftActionVal: TextView
    private lateinit var tvSwipeRightActionVal: TextView

    private var currentEditingGestureType: GestureSlot = GestureSlot.CLICK

    private enum class GestureSlot {
        CLICK, DOUBLE_CLICK, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT
    }

    private val actionEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val link = result.data?.getStringExtra(GestureAddActionActivity.EXTRA_EXTERNAL_ACTION_LINK)
                ?: result.data?.getStringExtra("actionLink")
                ?: ""
            if (link.isNotBlank()) {
                applyActionLinkToConfig(currentEditingGestureType, link)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.custom_ball_activity_gesture_settings)

        config = FloatingBallConfig.load(this)

        initViews()
        setupToolbar()
        refreshActionSummaries()
        setupListeners()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun initViews() {
        tvClickActionVal = findViewById(R.id.tvClickActionVal)
        tvDoubleClickActionVal = findViewById(R.id.tvDoubleClickActionVal)
        tvLongPressActionVal = findViewById(R.id.tvLongPressActionVal)
        tvSwipeUpActionVal = findViewById(R.id.tvSwipeUpActionVal)
        tvSwipeDownActionVal = findViewById(R.id.tvSwipeDownActionVal)
        tvSwipeLeftActionVal = findViewById(R.id.tvSwipeLeftActionVal)
        tvSwipeRightActionVal = findViewById(R.id.tvSwipeRightActionVal)
    }

    private fun setupListeners() {
        bindRow(findViewById(R.id.rowClickAction), GestureSlot.CLICK)
        bindRow(findViewById(R.id.rowDoubleClickAction), GestureSlot.DOUBLE_CLICK)
        bindRow(findViewById(R.id.rowLongPressAction), GestureSlot.LONG_PRESS)
        bindRow(findViewById(R.id.rowSwipeUpAction), GestureSlot.SWIPE_UP)
        bindRow(findViewById(R.id.rowSwipeDownAction), GestureSlot.SWIPE_DOWN)
        bindRow(findViewById(R.id.rowSwipeLeftAction), GestureSlot.SWIPE_LEFT)
        bindRow(findViewById(R.id.rowSwipeRightAction), GestureSlot.SWIPE_RIGHT)
    }

    private fun bindRow(view: View, slot: GestureSlot) {
        view.setOnClickListener {
            currentEditingGestureType = slot
            val intent = Intent(this, GestureAddActionActivity::class.java).apply {
                putExtra(GestureAddActionActivity.EXTRA_EXTERNAL_ACTION_MODE, true)
            }
            actionEditorLauncher.launch(intent)
        }
        view.setOnLongClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("清空手势动作")
                .setMessage("确定要清空该手势绑定的动作吗？")
                .setPositiveButton("清空") { _, _ ->
                    applyActionLinkToConfig(slot, "")
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }
    }

    private fun applyActionLinkToConfig(slot: GestureSlot, link: String) {
        when (slot) {
            GestureSlot.CLICK -> config.clickAction = link
            GestureSlot.DOUBLE_CLICK -> config.doubleClickAction = link
            GestureSlot.LONG_PRESS -> config.longPressAction = link
            GestureSlot.SWIPE_UP -> config.swipeUpAction = link
            GestureSlot.SWIPE_DOWN -> config.swipeDownAction = link
            GestureSlot.SWIPE_LEFT -> config.swipeLeftAction = link
            GestureSlot.SWIPE_RIGHT -> config.swipeRightAction = link
        }
        config.save(this)
        FloatingBallService.notifyConfigChanged(this)
        refreshActionSummaries()
    }

    private fun resolveActionDisplayName(link: String, defaultName: String): String {
        if (link.isBlank()) return defaultName
        return try {
            val actionType = GestureActionLink().apply { parseLink(link) }.actionType
            actionType.showName(this).takeIf { it.isNotBlank() } ?: defaultName
        } catch (_: Exception) {
            defaultName
        }
    }

    private fun refreshActionSummaries() {
        tvClickActionVal.text = resolveActionDisplayName(config.clickAction, "返回键")
        tvDoubleClickActionVal.text = resolveActionDisplayName(config.doubleClickAction, "最近任务")
        tvLongPressActionVal.text = resolveActionDisplayName(config.longPressAction, "拖出十字指针/框选识词（默认）")
        tvSwipeUpActionVal.text = resolveActionDisplayName(config.swipeUpAction, "回到桌面")
        tvSwipeDownActionVal.text = resolveActionDisplayName(config.swipeDownAction, "打开通知栏")
        tvSwipeLeftActionVal.text = resolveActionDisplayName(config.swipeLeftAction, "未设置")
        tvSwipeRightActionVal.text = resolveActionDisplayName(config.swipeRightAction, "未设置")
    }
}
