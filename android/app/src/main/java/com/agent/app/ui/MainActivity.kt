package com.agent.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.app.api.Action
import com.agent.app.api.LlmClient
import com.agent.app.api.StepRecord
import com.agent.app.data.MemoryEngine
import com.agent.app.service.AgentAccessibilityService
import com.agent.app.service.AgentEngine
import com.agent.app.service.ScreenCaptureService
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
    val history = remember { mutableListOf<String>() }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
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

    // ── Agent Engine 初始化 ──
    val engine = remember {
        val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("api_base_url", "https://api.openai.com/v1") ?: ""
        val model = prefs.getString("model", "gpt-4o") ?: "gpt-4o"

        // 实际初始化在点击执行时完成
        null as AgentEngine?
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
                        if (inputText.isBlank()) return@Button

                        // 检查 API Key
                        val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
                        val apiKey = prefs.getString("api_key", "") ?: ""
                        if (apiKey.isBlank()) {
                            Toast.makeText(context, "请先在设置中输入 API Key", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        // 请求截图权限
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())

                        isRunning = true
                        currentStep = 0
                        history.clear()
                        statusMessage = "正在执行…"

                        scope.launch {
                            runAgent(context, inputText) { step, msg, done, success ->
                                currentStep = step
                                statusMessage = msg
                                if (done) {
                                    isRunning = false
                                    history.add(0, "${if (success) "✅" else "❌"} ${inputText.take(30)} — $msg")
                                    inputText = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = inputText.isNotBlank()
                ) {
                    Text("执行")
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
 * 运行 Agent（协程）
 */
suspend fun runAgent(
    context: Context,
    instruction: String,
    onUpdate: (step: Int, message: String, done: Boolean, success: Boolean) -> Unit
) {
    // 读取配置
    val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)
    val apiKey = prefs.getString("api_key", "") ?: ""
    val baseUrl = prefs.getString("api_base_url", "https://api.openai.com/v1") ?: ""
    val model = prefs.getString("model", "gpt-4o") ?: "gpt-4o"

    val llmClient = LlmClient(apiKey, baseUrl, model)
    val memoryEngine = MemoryEngine(context)

    onUpdate(0, "初始化…", false, false)

    // 这里需要获取 AgentAccessibilityService 和 ScreenCaptureService 实例
    // 实际实现中需要通过 Application 持有或依赖注入
    // 简化实现：这部分在实际编译时需要补全
    onUpdate(0, "需要补全服务引用", true, false)
}
