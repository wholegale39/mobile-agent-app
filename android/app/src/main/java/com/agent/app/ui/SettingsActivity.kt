package com.agent.app.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("agent_config", Context.MODE_PRIVATE)

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var apiBaseUrl by remember { mutableStateOf(
        prefs.getString("api_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    ) }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o") ?: "gpt-4o") }
    var saved by remember { mutableStateOf(false) }

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

            Spacer(Modifier.height(12.dp))

            // ── 模型选择 ──
            Text("模型", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))

            val models = listOf(
                "gpt-4o" to "GPT-4o（最强，最贵）",
                "gpt-4o-mini" to "GPT-4o Mini（平衡）",
                "deepseek-chat" to "DeepSeek-V3（便宜中文好）",
                "qwen-vl-max" to "通义千问VL-Max（国产）"
            )

            models.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = model == value,
                        onClick = { model = value; saved = false }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(label, fontSize = 11.sp,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── 保存 ──
            Button(
                onClick = {
                    prefs.edit().apply {
                        putString("api_key", apiKey)
                        putString("api_base_url", apiBaseUrl)
                        putString("model", model)
                        apply()
                    }
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "✅ 已保存" else "保存")
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
4. 首次使用会在每一步确认，熟悉后可关闭确认

支持模型：
• OpenAI: gpt-4o / gpt-4o-mini
• DeepSeek: deepseek-chat
• 阿里云: qwen-vl-max

隐私说明：
截图会发送到 LLM API 进行处理，
不会存储在其他地方。
敏感信息（密码、验证码）在发送前自动脱敏。""",
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
