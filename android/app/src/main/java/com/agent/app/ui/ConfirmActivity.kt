package com.agent.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.app.api.Action
import com.google.gson.Gson

/**
 * 安全确认界面 — 高危操作需要用户手动批准
 */
class ConfirmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val actionJson = intent.getStringExtra("action") ?: "{}"
        val reason = intent.getStringExtra("reason") ?: ""
        val action = try {
            Gson().fromJson(actionJson, Action::class.java)
        } catch (e: Exception) {
            null
        }

        setContent {
            MaterialTheme {
                ConfirmScreen(
                    action = action,
                    reason = reason,
                    onApprove = {
                        setResult(RESULT_OK, Intent().putExtra("approved", true))
                        finish()
                    },
                    onReject = {
                        setResult(RESULT_OK, Intent().putExtra("approved", false))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun ConfirmScreen(
    action: Action?,
    reason: String,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val description = describeAction(action ?: Action("unknown"))
    val isDangerous = reason.contains("高危")

    AlertDialog(
        onDismissRequest = onReject,
        icon = { Text(if (isDangerous) "⚠️" else "🤖", fontSize = 32.sp) },
        title = {
            Text(
                if (isDangerous) "高危操作确认" else "操作确认",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text("手机助手将执行：")
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDangerous)
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        description,
                        modifier = Modifier.padding(16.dp),
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp
                    )
                }
                if (isDangerous) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "此操作涉及敏感功能，请确认是您本人的意图。",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                colors = if (isDangerous)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) { Text(if (isDangerous) "确认执行" else "允许") }
        },
        dismissButton = {
            OutlinedButton(onClick = onReject) { Text("取消") }
        }
    )
}
