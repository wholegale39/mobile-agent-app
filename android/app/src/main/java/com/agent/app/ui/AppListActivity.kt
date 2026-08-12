package com.agent.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用列表 — 浏览/搜索本机应用，选择后：
 * 1) 仅打开
 * 2) 打开并让 Agent 执行任务（如"找到一篇文章分享到微信"）
 *
 * 返回结果（Intent extra）：
 * - EXTRA_APP_NAME   应用名
 * - EXTRA_PACKAGE    包名
 * - EXTRA_TASK       任务描述（空 = 仅打开）
 */
class AppListActivity : ComponentActivity() {

    companion object {
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_TASK = "task"
    }

    private lateinit var apps: List<AppInfo>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = loadLaunchableApps(this)
        setContent {
            MaterialTheme {
                AppListScreen(apps = apps, onPick = { app -> showTaskDialog(app) })
            }
        }
    }

    /** 弹出任务输入框：仅打开 / 打开并执行任务 */
    private fun showTaskDialog(app: AppInfo) {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val editText = android.widget.EditText(this).apply {
            hint = "例如：找到一篇文章分享到微信"
            setPadding(padding, padding / 2, padding, padding / 2)
            textSize = 14f
        }
        val container = android.widget.FrameLayout(this)
        container.addView(editText, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((padding / 2).toInt(), 0, (padding / 2).toInt(), 0) })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("打开「${app.label}」")
            .setMessage("包名：${app.packageName}\n\n输入想让它做的事，留空则仅打开应用：")
            .setView(container)
            .setPositiveButton("打开并执行", { _, _ ->
                finishWith(app, editText.text.toString().trim())
            })
            .setNegativeButton("仅打开", { _, _ -> finishWith(app, "") })
            .setNeutralButton("取消", null)
            .show()
    }

    private fun finishWith(app: AppInfo, task: String) {
        val intent = Intent().apply {
            putExtra(EXTRA_APP_NAME, app.label)
            putExtra(EXTRA_PACKAGE, app.packageName)
            putExtra(EXTRA_TASK, task)
        }
        setResult(RESULT_OK, intent)
        finish()
    }
}

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: android.graphics.Bitmap?
)

/** 查询所有可启动（有桌面入口）的应用，按名称排序 */
fun loadLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveList = pm.queryIntentActivities(intent, 0)
    return resolveList
        .map { ri ->
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            val icon = try {
                (pm.getApplicationIcon(pkg) as? BitmapDrawable)?.bitmap
                    ?: pm.getApplicationIcon(pkg).toBitmap(96, 96)
            } catch (e: Exception) { null }
            AppInfo(label, pkg, icon)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(apps: List<AppInfo>, onPick: (AppInfo) -> Unit) {
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("📱 已安装应用（${apps.size}）") },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Text("←", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有匹配的应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(app) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                                )
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(app.packageName, fontSize = 11.sp,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}
