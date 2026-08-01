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
     * 先生成总体计划（reasoning 模式）— 纯文本，不传截图，省 token。
     * 失败返回 null（降级为逐步执行）。
     */
    suspend fun planTask(instruction: String): TaskPlan? = withContext(Dispatchers.IO) {
        val messages = listOf(
            Message("system", listOf(Content("text", PLAN_PROMPT))),
            Message("user", listOf(Content("text", "任务：$instruction")))
        )

        val requestBody = ChatRequest(
            model = model,
            messages = messages,
            maxTokens = 1024,
            temperature = 0.1,
            responseFormat = ResponseFormat("json_object")
        )

        try {
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody(jsonType)
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val chatResponse = gson.fromJson(response.body?.string(), ChatResponse::class.java)
            val content = chatResponse.choices?.firstOrNull()?.message?.content ?: ""
            val jsonStart = content.indexOf('{')
            val jsonEnd = content.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val plan = gson.fromJson(content.substring(jsonStart, jsonEnd), TaskPlan::class.java)
                if (plan.steps.isNotEmpty()) plan else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 分析截图 + UI 树 → 决定下一步操作
     * @param plan 可选：总体计划摘要（reasoning 模式），帮助 agent 不跑偏
     */
    suspend fun decideNextStep(
        systemPrompt: String,
        instruction: String,
        screenshotBase64: String,
        uiTreeSummary: String,
        history: List<StepRecord>,
        lastResult: String? = null,
        plan: String? = null
    ): StepDecision = withContext(Dispatchers.IO) {

        // 构建消息
        val messages = mutableListOf(
            Message("system", listOf(Content("text", systemPrompt)))
        )

        // 用户消息（文字 + 图片）
        val userParts = mutableListOf(
            Content("text", buildUserPrompt(instruction, uiTreeSummary, history, lastResult, plan)),
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
        lastResult: String?,
        plan: String? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("【任务】$instruction\n")

        if (plan != null) {
            sb.appendLine("【总体计划参考】\n$plan\n")
            sb.appendLine("（计划仅供参考，以实际界面为准，不要死板执行）\n")
        }

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
            |界面元素每行带 ref（如 @e3），优先用 {"type": "click_ref", "ref": "@e3"}，ref 比坐标稳定。
            |输出 JSON 格式：
            |{
            |  "step_reasoning": "为什么做这步",
            |  "action": {"type": "click_ref|click|click_text|swipe|input|back|home|wait|scroll", ...},
            |  "status": "in_progress|completed|failed",
            |  "user_message": "给用户看的进度提示"
            |}
        """.trimMargin())

        // vision-only 兜底：UI 树无可交互元素时，提示仅凭截图判断
        if (uiTree.isBlank() || uiTree == "（无可交互元素）") {
            sb.appendLine("\n【界面元素】无有效 UI 树（该应用可能不支持无障碍树），请仅根据截图视觉判断操作。")
        } else {
            sb.appendLine("\n【界面元素】\n$uiTree")
        }
        return sb.toString()
    }

    companion object {
        private const val PLAN_PROMPT = """你是手机操作规划器。分析任务，先给出 3-10 步的总体操作计划。
每步描述：要做什么操作、期望的界面结果。不要执行，只规划。
不确定的步骤写保守做法。

输出严格 JSON：
{
  "goal": "任务目标一句话",
  "steps": [
    {"step": 1, "action_desc": "打开微信", "expected_result": "微信首页显示"},
    {"step": 2, "action_desc": "进入设置", "expected_result": "设置页显示"}
  ]
}"""
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

/**
 * 总体计划（reasoning 模式）
 */
data class TaskPlan(
    val goal: String = "",
    val steps: List<PlanStep> = emptyList()
)

data class PlanStep(
    val step: Int = 0,
    @SerializedName("action_desc") val actionDesc: String = "",
    @SerializedName("expected_result") val expectedResult: String = ""
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
    @SerializedName("ms") val waitMs: Int? = null,
    @SerializedName("ref") val ref: String? = null
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
