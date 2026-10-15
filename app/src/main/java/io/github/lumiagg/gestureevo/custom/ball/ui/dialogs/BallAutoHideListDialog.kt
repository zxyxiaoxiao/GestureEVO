package io.github.lumiagg.gestureevo.custom.ball.ui.dialogs

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallConfig
import io.github.lumiagg.gestureevo.custom.ball.FloatingBallService
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用自动隐藏列表弹窗
 * 展示全部已安装应用，带多选框、搜索框、全选清空，保存黑名单
 */
object BallAutoHideListDialog {

    data class AppItem(
        val name: String,
        val pkg: String,
        var isChecked: Boolean
    )

    fun show(
        activity: AppCompatActivity,
        onConfirmed: (updatedConfig: FloatingBallConfig) -> Unit
    ) {
        val currentConfig = FloatingBallConfig.load(activity)
        val selectedPkgs = currentConfig.autoHideApps.toMutableSet()

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.custom_ball_dialog_auto_hide_list, null)
        val etSearch = dialogView.findViewById<TextInputEditText>(R.id.etSearch)
        val tvSelectedCount = dialogView.findViewById<TextView>(R.id.tvSelectedCount)
        val btnSelectAll = dialogView.findViewById<MaterialButton>(R.id.btnSelectAll)
        val btnClearAll = dialogView.findViewById<MaterialButton>(R.id.btnClearAll)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val rvApps = dialogView.findViewById<RecyclerView>(R.id.rvApps)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)

        rvApps.layoutManager = LinearLayoutManager(activity)

        val allApps = mutableListOf<AppItem>()
        val filteredApps = mutableListOf<AppItem>()

        fun updateCountText() {
            tvSelectedCount.text = "已选择 ${selectedPkgs.size} 个应用"
        }
        updateCountText()

        val adapter = object : RecyclerView.Adapter<AppViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.custom_ball_item_app_select, parent, false)
                return AppViewHolder(view)
            }

            override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
                val item = filteredApps[position]
                holder.tvAppName.text = item.name
                holder.tvAppPkg.text = item.pkg
                holder.cbSelect.isChecked = item.isChecked

                holder.itemView.setOnClickListener {
                    item.isChecked = !item.isChecked
                    holder.cbSelect.isChecked = item.isChecked
                    if (item.isChecked) {
                        selectedPkgs.add(item.pkg)
                    } else {
                        selectedPkgs.remove(item.pkg)
                    }
                    updateCountText()
                }

                // 异步加载应用图标
                loadIconAsync(holder.ivAppIcon, item.pkg)
            }

            override fun getItemCount(): Int = filteredApps.size
        }
        rvApps.adapter = adapter

        fun filterList(query: String) {
            filteredApps.clear()
            if (query.isBlank()) {
                filteredApps.addAll(allApps)
            } else {
                val q = query.lowercase().trim()
                filteredApps.addAll(allApps.filter {
                    it.name.lowercase().contains(q) || it.pkg.lowercase().contains(q)
                })
            }
            adapter.notifyDataSetChanged()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterList(s?.toString() ?: "")
            }
        })

        btnSelectAll.setOnClickListener {
            filteredApps.forEach {
                it.isChecked = true
                selectedPkgs.add(it.pkg)
            }
            adapter.notifyDataSetChanged()
            updateCountText()
        }

        btnClearAll.setOnClickListener {
            filteredApps.forEach {
                it.isChecked = false
                selectedPkgs.remove(it.pkg)
            }
            adapter.notifyDataSetChanged()
            updateCountText()
        }

        // 异步加载已安装应用
        progressBar.visibility = View.VISIBLE
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val list = mutableListOf<AppItem>()
            try {
                // 优先从项目本地数据库读取
                val dbApps = getAPPDao().getAll()
                if (dbApps.isNotEmpty()) {
                    dbApps.forEach { app ->
                        list.add(AppItem(app.name, app.pkgName, selectedPkgs.contains(app.pkgName)))
                    }
                }
            } catch (_: Exception) {}

            if (list.isEmpty()) {
                val pm = activity.packageManager
                val packages = pm.getInstalledPackages(0)
                packages.forEach { pi ->
                    val appInfo = pi.applicationInfo ?: return@forEach
                    val name = appInfo.loadLabel(pm).toString()
                    val pkg = pi.packageName
                    list.add(AppItem(name, pkg, selectedPkgs.contains(pkg)))
                }
            }

            // 按勾选排在最前，之后按名称排序
            list.sortWith(compareByDescending<AppItem> { it.isChecked }.thenBy { it.name })

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(list)
                filteredApps.clear()
                filteredApps.addAll(list)
                progressBar.visibility = View.GONE
                adapter.notifyDataSetChanged()
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            currentConfig.autoHideApps = selectedPkgs
            currentConfig.save(activity)
            FloatingBallService.notifyConfigChanged(activity)
            onConfirmed(currentConfig)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun loadIconAsync(imageView: ImageView, pkgName: String) {
        imageView.setImageResource(R.drawable.baseline_circle_24)
        val context = imageView.context
        (context as? AppCompatActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            val icon = try {
                IconHelper.getIcon(context, pkgName)
            } catch (_: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                if (icon != null) {
                    imageView.setImageDrawable(icon)
                }
            }
        }
    }

    private class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivAppIcon: ImageView = itemView.findViewById(R.id.ivAppIcon)
        val tvAppName: TextView = itemView.findViewById(R.id.tvAppName)
        val tvAppPkg: TextView = itemView.findViewById(R.id.tvAppPkg)
        val cbSelect: MaterialCheckBox = itemView.findViewById(R.id.cbSelect)
    }
}
