package com.agent.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.app.api.LlmClient
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SettingsScreen()
            }
        }
    }
}

/** 预设模型：id / 说明 / 对应 API 地址（选中自动联动） */
data class ModelOption(val id: String, val label: String, val baseUrl: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var apiBaseUrl by remember { mutableStateOf(
        prefs.getString("api_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    ) }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o") ?: "gpt-4o") }
    var modelCustom by remember { mutableStateOf(prefs.getString("model_custom", "") ?: "") }
    var confirmEachStep by remember { mutableStateOf(prefs.getBoolean("confirm_each_step", false)) }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val models = listOf(
        ModelOption("gpt-4o", "GPT-4o（最强，最贵）", "https://api.openai.com/v1"),
        ModelOption("gpt-4o-mini", "GPT-4o Mini（平衡）", "https://api.openai.com/v1"),
        ModelOption("deepseek-chat", "DeepSeek-V3（便宜中文好）", "https://api.deepseek.com/v1"),
        ModelOption("qwen-vl-max", "通义千问VL-Max（国产）", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    )

    // 实际生效模型：自定义优先
    val effectiveModel = if (modelCustom.isNotBlank()) modelCustom else model

    // 版本号
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: PackageManager.NameNotFoundException) {
            "?"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── API Key ──
            Text("API 配置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = apiBaseUrl,
                onValueChange = { apiBaseUrl = it; saved = false },
                label = { Text("API 地址") },
                placeholder = { Text("https://api.openai.com/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：选择下方预设模型会自动填入对应 API 地址，也可手动修改。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // ── 模型选择 ──
            Text("模型", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))

            models.forEach { (value, label, presetUrl) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = effectiveModel == value,
                        onClick = {
                            model = value
                            modelCustom = ""   // 选中预设时清除自定义
                            apiBaseUrl = presetUrl  // 联动 API 地址
                            saved = false
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(label, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 自定义模型 ──
            OutlinedTextField(
                value = modelCustom,
                onValueChange = { modelCustom = it; saved = false },
                label = { Text("自定义模型名（可选，留空用上方预设）") },
                placeholder = { Text("如 deepseek-v3 / glm-4v-plus") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            // ── 行为设置 ──
            Text("行为设置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("每步操作都确认", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("开启后每一步都弹窗确认，关闭后仅高危操作确认",
                         fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = confirmEachStep,
                    onCheckedChange = { confirmEachStep = it; saved = false }
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── 保存 / 测试 ──
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        prefs.edit().apply {
                            putString("api_key", apiKey)
                            putString("api_base_url", apiBaseUrl)
                            putString("model", model)
                            putString("model_custom", modelCustom)
                            putBoolean("confirm_each_step", confirmEachStep)
                            apply()
                        }
                        saved = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (saved) "✅ 已保存" else "保存")
                }

                Spacer(Modifier.width(12.dp))

                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val client = LlmClient(apiKey, apiBaseUrl, effectiveModel)
                            testResult = client.testConnection()
                            testing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = apiKey.isNotBlank() && !testing
                ) {
                    Text(if (testing) "测试中…" else "测试连接")
                }
            }

            testResult?.let { (ok, msg) ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (ok) "✅ $msg" else "❌ $msg",
                    fontSize = 13.sp,
                    color = if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── 使用说明 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("使用说明", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        """1. 开启无障碍服务（首次打开 App 时会引导）
2. 在上方填入你的 API Key
3. 输入指令，点击执行
4. 首次使用建议开启"每步确认"，熟悉后可关闭

支持模型：
• OpenAI: gpt-4o / gpt-4o-mini
• DeepSeek: deepseek-chat
• 阿里云: qwen-vl-max
• 任意 OpenAI 兼容模型（自定义模型名）

隐私说明：
截图会发送到 LLM API 进行处理，
不会存储在其他地方。
敏感信息（密码、验证码）在发送前自动脱敏。""",
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "版本 v$versionName",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
