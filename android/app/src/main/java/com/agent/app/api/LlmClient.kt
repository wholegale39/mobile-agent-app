package com.agent.app.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 多模态 LLM API 客户端
 * 支持 GPT-4o / DeepSeek / Qwen-VL
 * 直接调 API，不需要中间服务端
 */
class LlmClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    /**
     * 分析截图 + UI 树 → 决定下一步操作
     */
    suspend fun decideNextStep(
        systemPrompt: String,
        instruction: String,
        screenshotBase64: String,
        uiTreeSummary: String,
        history: List<StepRecord>,
        lastResult: String? = null
    ): StepDecision = withContext(Dispatchers.IO) {

        // 构建消息
        val messages = mutableListOf(
            Message("system", listOf(Content("text", systemPrompt)))
        )

        // 用户消息（文字 + 图片）
        val userParts = mutableListOf(
            Content("text", buildUserPrompt(instruction, uiTreeSummary, history, lastResult)),
            Content("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,$screenshotBase64"))
        )
        messages.add(Message("user", userParts))

        // 请求体
        val requestBody = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = 2048,
            temperature = 0.1,
            responseFormat = ResponseFormat("json_object")
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: $responseBody")
        }

        val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
        val content = chatResponse.choices?.firstOrNull()?.message?.content ?: ""

        // 解析 JSON
        try {
            gson.fromJson(content, StepDecision::class.java)
        } catch (e: Exception) {
            // 如果 LLM 没有输出纯 JSON，尝试提取
            val jsonStart = content.indexOf('{')
            val jsonEnd = content.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                gson.fromJson(content.substring(jsonStart, jsonEnd), StepDecision::class.java)
            } else {
                StepDecision(
                    stepReasoning = "解析失败",
                    status = "failed",
                    userMessage = "LLM 返回格式错误: ${content.take(100)}"
                )
            }
        }
    }

    /**
     * 验证任务是否完成
     */
    suspend fun verifyCompletion(
        instruction: String,
        screenshotBase64: String
    ): Boolean = withContext(Dispatchers.IO) {

        val messages = listOf(
            Message("user", listOf(
                Content("text", "请判断当前界面是否表示以下任务已完成：\n「$instruction」\n回答 only: yes / no / need_more_steps"),
                Content("image_url", imageUrl = ImageUrl("data:image/jpeg;base64,$screenshotBase64"))
            ))
        )

        val requestBody = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = 10,
            temperature = 0.0
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody(jsonType)

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val chatResponse = gson.fromJson(response.body?.string(), ChatResponse::class.java)
        val answer = chatResponse.choices?.firstOrNull()?.message?.content?.trim()?.lowercase() ?: "no"
        answer.contains("yes")
    }

    private fun buildUserPrompt(
        instruction: String,
        uiTree: String,
        history: List<StepRecord>,
        lastResult: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("【任务】$instruction\n")

        if (history.isEmpty()) {
            sb.appendLine("（这是第一步，尚无历史操作）")
        } else {
            sb.appendLine("【操作历史】（最近5步）：")
            history.takeLast(5).forEach { h ->
                val icon = if (h.success) "✅" else "❌"
                sb.appendLine("  Step ${h.step}: $icon ${h.actionDesc}")
            }
        }

        if (lastResult != null) {
            sb.appendLine("\n【上一步结果】$lastResult")
        }

        sb.appendLine("""
            |
            |请分析当前截图和界面元素，决定下一步操作。
            |一次只做一个操作。
            |输出 JSON 格式：
            |{
            |  "step_reasoning": "为什么做这步",
            |  "action": {"type": "click|swipe|input|back|home|wait|scroll", ...},
            |  "status": "in_progress|completed|failed",
            |  "user_message": "给用户看的进度提示"
            |}
        """.trimMargin())

        sb.appendLine("\n【界面元素】\n$uiTree")
        return sb.toString()
    }
}

// ──── 数据模型 ────

data class Message(
    val role: String,
    val content: List<Content>
)

data class Content(
    val type: String,  // "text" or "image_url"
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    val url: String
)

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    @SerializedName("max_tokens") val maxTokens: Int = 2048,
    val temperature: Double = 0.1,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null
)

data class ResponseFormat(
    val type: String = "json_object"
)

data class ChatResponse(
    val choices: List<Choice>? = null,
    val error: ApiError? = null
)

data class Choice(
    val message: ResponseMessage
)

data class ResponseMessage(
    val content: String?
)

data class ApiError(
    val message: String?
)

/**
 * LLM 返回的决策
 */
data class StepDecision(
    @SerializedName("step_reasoning") val stepReasoning: String = "",
    val action: Action? = null,
    val status: String = "in_progress",
    @SerializedName("user_message") val userMessage: String? = null
)

data class Action(
    val type: String,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    @SerializedName("direction") val scrollDirection: String? = null,
    @SerializedName("distance_percent") val scrollDistance: Double? = null,
    @SerializedName("ms") val waitMs: Int? = null
)

/**
 * 历史步骤记录
 */
data class StepRecord(
    val step: Int,
    val actionDesc: String,
    val reasoning: String,
    val success: Boolean = true
)
