package io.github.lumiagg.gestureevo.custom.ball.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.custom.ball.ui.dialogs.BallAutoHideListDialog
import io.github.lumiagg.gestureevo.custom.ball.ui.dialogs.BallManualHideDialog
import io.github.lumiagg.gestureevo.custom.ball.ui.dialogs.BallPositionDialog
import io.github.lumiagg.gestureevo.custom.ball.ui.dialogs.BallSliderDialog
import io.github.lumiagg.gestureevo.custom.ball.ui.dialogs.BallStyleDialog
import io.github.lumiagg.gestureevo.custom.ball.util.FloatingBallPermissionHelper
import java.io.File
import java.io.FileOutputStream

/**
 * 悬浮球设置主页面
 * 分为3个分组：图标、线、隐藏设置，点击整行唤起独立功能弹窗
 */
class FloatingBallSettingsActivity : AppCompatActivity() {

    private lateinit var config: FloatingBallConfig

    private lateinit var switchBallEnable: MaterialSwitch

    // 小字 TextView
    private lateinit var tvBallSizeVal: TextView
    private lateinit var tvBallInnerRatioVal: TextView
    private lateinit var tvBallAlphaVal: TextView
    private lateinit var tvBallPosVal: TextView
    private lateinit var tvBallStyleVal: TextView

    private lateinit var tvManualHideVal: TextView
    private lateinit var tvAutoHideListVal: TextView

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleImagePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.custom_ball_activity_settings)

        config = FloatingBallConfig.load(this)

        initViews()
        setupToolbar()
        refreshItemSummary()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        config = FloatingBallConfig.load(this)
        refreshItemSummary()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun initViews() {
        switchBallEnable = findViewById(R.id.switchBallEnable)

        tvBallSizeVal = findViewById(R.id.tvBallSizeVal)
        tvBallInnerRatioVal = findViewById(R.id.tvBallInnerRatioVal)
        tvBallAlphaVal = findViewById(R.id.tvBallAlphaVal)
        tvBallPosVal = findViewById(R.id.tvBallPosVal)
        tvBallStyleVal = findViewById(R.id.tvBallStyleVal)

        tvManualHideVal = findViewById(R.id.tvManualHideVal)
        tvAutoHideListVal = findViewById(R.id.tvAutoHideListVal)

        switchBallEnable.isChecked = config.isEnabled
    }

    private fun setupListeners() {
        // 主开关
        findViewById<LinearLayout>(R.id.rowMainSwitch).setOnClickListener {
            switchBallEnable.toggle()
        }

        switchBallEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                FloatingBallPermissionHelper.checkAndGuidePermissions(this) {
                    config.isEnabled = true
                    config.save(this)
                    FloatingBallService.start(this)
                    FloatingBallService.notifyConfigChanged(this)
                }
            } else {
                config.isEnabled = false
                config.save(this)
                FloatingBallService.notifyConfigChanged(this)
            }
        }

        // 分组1：图标
        findViewById<LinearLayout>(R.id.rowBallSize).setOnClickListener {
            BallSliderDialog.show(this, BallSliderDialog.SliderType.BALL_SIZE) {
                config = it
                refreshItemSummary()
            }
        }

        findViewById<LinearLayout>(R.id.rowBallInnerRatio).setOnClickListener {
            BallSliderDialog.show(this, BallSliderDialog.SliderType.BALL_INNER_RATIO) {
                config = it
                refreshItemSummary()
            }
        }

        findViewById<LinearLayout>(R.id.rowBallAlpha).setOnClickListener {
            BallSliderDialog.show(this, BallSliderDialog.SliderType.BALL_ALPHA) {
                config = it
                refreshItemSummary()
            }
        }

        findViewById<LinearLayout>(R.id.rowBallPos).setOnClickListener {
            BallPositionDialog.show(this) {
                config = it
                refreshItemSummary()
            }
        }

        findViewById<LinearLayout>(R.id.rowBallStyle).setOnClickListener {
            BallStyleDialog.show(
                activity = this,
                onPickImageRequest = {
                    pickImageLauncher.launch("image/*")
                },
                onConfirmed = {
                    config = it
                    refreshItemSummary()
                }
            )
        }

        // 隐藏设置
        findViewById<LinearLayout>(R.id.rowManualHide).setOnClickListener {
            BallManualHideDialog.show(this) {
                config = it
                refreshItemSummary()
            }
        }

        findViewById<LinearLayout>(R.id.rowAutoHideList).setOnClickListener {
            BallAutoHideListDialog.show(this) {
                config = it
                refreshItemSummary()
            }
        }
    }

    private fun refreshItemSummary() {
        tvBallSizeVal.text = "${config.sizePercent}%"
        tvBallInnerRatioVal.text = "${config.innerRatioPercent}%"
        tvBallAlphaVal.text = "${config.iconAlphaPercent}%"
        tvBallPosVal.text = if (config.positionMode == 1) {
            if (config.followTriggerTag.isBlank()) "始终跟随触发器 (全部)" else "始终跟随触发器 (${config.followTriggerTag})"
        } else {
            "自定义 (X: ${config.posX}, Y: ${config.posY})"
        }
        tvBallStyleVal.text = if (config.iconStyleType == 1) "自定义图片" else "预设圆形模板"

        tvManualHideVal.text = if (config.manualHideEnabled) {
            "已开启 (${if (config.manualHideWakeType == 1) "手势唤起" else "通知点击唤起"})"
        } else {
            "未开启"
        }

        tvAutoHideListVal.text = if (config.autoHideApps.isNotEmpty()) {
            "已配置 ${config.autoHideApps.size} 个应用"
        } else {
            "未设置应用黑名单"
        }
    }

    private fun handleImagePicked(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val targetFile = File(filesDir, "custom_ball_icon_${System.currentTimeMillis()}.png")
            val outputStream = FileOutputStream(targetFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            BallStyleDialog.onImageSelected(targetFile.absolutePath, this)
        } catch (_: Exception) {}
    }
}
