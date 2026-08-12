package com.agent.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.agent.app.api.Action
import com.agent.app.api.LlmClient
import com.agent.app.api.StepRecord
import com.agent.app.data.MemoryEngine
import com.agent.app.service.AgentAccessibilityService
import com.agent.app.service.AgentEngine
import com.agent.app.service.ScreenCaptureService
import com.agent.app.ui.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 状态 ──
    var serviceEnabled by remember { mutableStateOf(AgentAccessibilityService.isEnabled(context)) }
    var connectionStatus by remember { mutableStateOf("未连接") }
    var inputText by remember { mutableStateOf("") }
    var currentStep by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    var activeEngine by remember { mutableStateOf<AgentEngine?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<Action?>(null) }
    var pendingReason by remember { mutableStateOf("") }
    var onConfirmResult by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    // ── 注册广播监听 ──
    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AgentAccessibilityService.ACTION_SERVICE_CONNECTED -> {
                        serviceEnabled = true
                        connectionStatus = "已就绪"
                    }
                    AgentAccessibilityService.ACTION_SERVICE_DISCONNECTED -> {
                        serviceEnabled = false
                        connectionStatus = "未连接"
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val filter = IntentFilter().apply {
            addAction(AgentAccessibilityService.ACTION_SERVICE_CONNECTED)
            addAction(AgentAccessibilityService.ACTION_SERVICE_DISCONNECTED)
        }
        Log.d("AgentFix", "registerReceiver fix applied")  // [验证标记]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ── onResume 重新检查无障碍状态（从系统设置返回时自动刷新） ──
    val lifecycleObserver = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = AgentAccessibilityService.isEnabled(context)
                serviceEnabled = enabled
                if (enabled) connectionStatus = "已就绪"
                Log.d("AgentFix", "onResume 重新检查无障碍: $enabled")
            }
        }
    }
    DisposableEffect(Unit) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        lifecycle?.addObserver(lifecycleObserver)
        onDispose { lifecycle?.removeObserver(lifecycleObserver) }
    }

    // ── 通知权限（Android 13+）──
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── 截图授权 Launcher ──
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("result_code", result.resultCode)
                putExtra("result_data", result.data)
            }
            context.startForegroundService(intent)
            connectionStatus = "截图已授权"
        }
    }

    // ── 任务执行入口（按钮 / 应用列表共用） ──
    fun startTask(task: String) {
        if (task.isBlank()) return
        // 检查 API Key
        val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(context, "请先在设置中输入 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        // 请求截图权限（服务已运行时 MediaProjection 授权仍是系统要求，Android 14+ 需重新授权）
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())

        isRunning = true
        currentStep = 0
        history.clear()
        statusMessage = "正在执行…"

        scope.launch {
            val engine = runAgent(
                context = context,
                instruction = task,
                onNeedConfirm = { action, reason, callback ->
                    pendingAction = action
                    pendingReason = reason
                    onConfirmResult = callback
                    showConfirmDialog = true
                },
                onUpdate = { step, msg, done, success ->
                    currentStep = step
                    statusMessage = msg
                    if (done) {
                        isRunning = false
                        activeEngine = null
                        history.add(0, "${if (success) "✅" else "❌"} ${task.take(30)} — $msg")
                    }
                }
            )
            activeEngine = engine
        }
    }

    // ── 应用列表 Launcher：选应用 → 打开 → （可选）执行任务 ──
    val appListLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val data = result.data ?: return@rememberLauncherForActivityResult
            val pkg = data.getStringExtra(AppListActivity.EXTRA_PACKAGE) ?: return@rememberLauncherForActivityResult
            val appName = data.getStringExtra(AppListActivity.EXTRA_APP_NAME) ?: pkg
            val task = data.getStringExtra(AppListActivity.EXTRA_TASK) ?: ""
            // 1. 打开应用
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    connectionStatus = "已打开 $appName"
                } else {
                    Toast.makeText(context, "无法打开 $appName（无启动入口）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "打开 $appName 失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
            // 2. 有任务 → 交给 Agent 执行
            if (task.isNotBlank()) {
                startTask("在${appName}中：$task")
            }
        }
    }

    // ── 界面 ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手机助手") },
                actions = {
                    // 连接状态
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            !serviceEnabled -> MaterialTheme.colorScheme.errorContainer
                            connectionStatus == "已就绪" -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        }
                    ) {
                        Text(
                            text = if (!serviceEnabled) "服务关闭"
                                   else connectionStatus,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            fontSize = 12.sp
                        )
                    }
                    // 设置入口
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // ── 状态卡片 ──
            StatusBanner(serviceEnabled, connectionStatus)

            Spacer(Modifier.height(16.dp))

            // ── 指令输入 ──
            if (serviceEnabled && !isRunning) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("例如：打开微信发消息给张三") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val task = inputText
                        inputText = ""
                        startTask(task)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = inputText.isNotBlank()
                ) {
                    Text("执行")
                }

                Spacer(Modifier.height(12.dp))

                // ── 应用列表入口：打开任意已安装应用 / 让 Agent 在应用内干活 ──
                OutlinedButton(
                    onClick = { context.startActivity(Intent(context, AppListActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📱 打开应用…（领克/微信等）", fontSize = 14.sp)
                }
            }

            // ── 执行状态 ──
            if (isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("执行中", fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(modifier = Modifier.width(120.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("步骤 $currentStep")
                        Text(statusMessage, fontSize = 13.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                activeEngine?.cancel()
                                activeEngine = null
                                isRunning = false
                                statusMessage = "已取消"
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("取消") }
                    }
                }
            }

            // ── 快捷入口 ──
            if (!isRunning && serviceEnabled) {
                Spacer(Modifier.height(16.dp))
                Text("快捷指令", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))

                val suggestions = listOf(
                    "打开微信", "打开支付宝付款码",
                    "帮我查天气", "打开相册"
                )
                suggestions.forEach { s ->
                    AssistChip(
                        onClick = { inputText = s },
                        label = { Text(s, fontSize = 13.sp) },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            // ── 历史记录 ──
            if (history.isNotEmpty() && !isRunning) {
                Spacer(Modifier.height(16.dp))
                Text("执行记录", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(history.take(20)) { item ->
                        Text(item, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }

            // ── 前往设置 ──
            if (!serviceEnabled) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开启无障碍服务")
                }
            }
        }
    }

    // ── 安全确认弹窗 ──
    if (showConfirmDialog && pendingAction != null) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false; onConfirmResult?.invoke(false) },
            title = { Text("操作确认") },
            text = {
                Column {
                    Text("手机助手将执行：")
                    Spacer(Modifier.height(8.dp))
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )) {
                        Text(
                            "${describeAction(pendingAction!!)}",
                            modifier = Modifier.padding(12.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(pendingReason, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmDialog = false
                    onConfirmResult?.invoke(true)
                }) { Text("允许") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showConfirmDialog = false
                    onConfirmResult?.invoke(false)
                }) { Text("取消") }
            }
        )
    }
}

@Composable
fun StatusBanner(serviceEnabled: Boolean, status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (!serviceEnabled) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (!serviceEnabled) "⚠️ 无障碍服务未开启，请先开启"
                       else "🤖 服务就绪，输入指令开始",
                fontSize = 13.sp
            )
        }
    }
}

fun describeAction(action: Action): String = when (action.type) {
    "click" -> "点击 (${action.x}, ${action.y})"
    "click_text" -> "点击「${action.text}」"
    "input" -> "输入「${action.text}」"
    "swipe" -> "滑动屏幕"
    "back" -> "返回"
    "home" -> "回到桌面"
    else -> action.type
}

/**
 * 运行 Agent（协程）— 接入 AgentEngine 完整链路
 * @return 活跃的 AgentEngine（可用于取消），失败返回 null
 */
suspend fun runAgent(
    context: Context,
    instruction: String,
    onNeedConfirm: (Action, String, (Boolean) -> Unit) -> Unit,
    onUpdate: (step: Int, message: String, done: Boolean, success: Boolean) -> Unit
): AgentEngine? {
    // 读取配置
    val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("api_key", "") ?: ""
    val baseUrl = prefs.getString("api_base_url", "https://api.openai.com/v1") ?: ""
    val modelCustom = prefs.getString("model_custom", "") ?: ""
    val model = if (modelCustom.isNotBlank()) modelCustom
                else prefs.getString("model", "gpt-4o") ?: "gpt-4o"
    val confirmEachStep = prefs.getBoolean("confirm_each_step", false)

    val accService = AgentAccessibilityService.instance
    val capService = ScreenCaptureService.instance
    if (accService == null || capService == null) {
        onUpdate(0, "服务未就绪：请确认无障碍服务已开启、截图已授权", true, false)
        return null
    }
    if (apiKey.isBlank()) {
        onUpdate(0, "未配置 API Key，请到设置中填写", true, false)
        return null
    }

    val engine = AgentEngine(
        accessibilityService = accService,
        captureService = capService,
        llmClient = LlmClient(apiKey, baseUrl, model),
        memoryEngine = MemoryEngine(context)
    )
    engine.confirmEveryStep = confirmEachStep

    engine.onStep = { step, desc -> onUpdate(step, desc, false, false) }
    engine.onStatus = { _, msg -> onUpdate(0, msg, false, false) }
    engine.onNeedConfirm = { action, reason, callback -> onNeedConfirm(action, reason, callback) }
    engine.onComplete = { success, msg -> onUpdate(0, msg, true, success) }

    onUpdate(0, "初始化…", false, false)
    engine.execute(instruction)
    return engine
}
