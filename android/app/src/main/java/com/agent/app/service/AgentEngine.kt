package com.agent.app.service

import android.util.Log
import com.agent.app.api.Action
import com.agent.app.api.LlmClient
import com.agent.app.api.StepDecision
import com.agent.app.api.StepRecord
import com.agent.app.data.MemoryEngine
import kotlinx.coroutines.*

/**
 * Agent 核心引擎 — 在手机上直接运行
 * 截图 → LLM 分析 → 执行 → 验证 完整循环
 */
class AgentEngine(
    private val accessibilityService: AgentAccessibilityService,
    private val captureService: ScreenCaptureService,
    private val llmClient: LlmClient,
    private val memoryEngine: MemoryEngine
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null

    // 状态回调
    var onStatus: ((String, String) -> Unit)? = null          // (status, message)
    var onStep: ((Int, String) -> Unit)? = null               // (step, description)
    var onNeedConfirm: ((Action, String, (Boolean) -> Unit) -> Unit)? = null
    var onComplete: ((Boolean, String) -> Unit)? = null       // (success, message)

    private val history = mutableListOf<StepRecord>()
    private val systemPrompt = buildSystemPrompt()

    /**
     * 执行任务
     */
    fun execute(instruction: String) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                onStatus?.invoke("running", "开始执行：$instruction")

                // 1. 检查记忆缓存
                val chain = memoryEngine.query(instruction)
                if (chain != null) {
                    onStatus?.invoke("chain_hit", "找到快捷路径：${chain.name}")
                    executeChain(chain.steps)
                    return@launch
                }

                // 2. 主循环
                history.clear()
                var stepCount = 0
                val maxSteps = 30

                while (stepCount < maxSteps) {
                    stepCount++
                    onStep?.invoke(stepCount, "分析界面…")

                    // 2a. 截图
                    val screenshot = captureService.captureScreen()
                    if (screenshot.isEmpty()) {
                        onStatus?.invoke("error", "截图失败")
                        onComplete?.invoke(false, "无法获取截图")
                        return@launch
                    }

                    // 2b. 获取 UI 树
                    val uiTree = accessibilityService.getUiTreeSummary()

                    // 2c. 调 LLM 决策
                    val lastResult = if (history.isNotEmpty())
                        "Step ${history.last().step}: ${if (history.last().success) "✅" else "❌"} ${history.last().actionDesc}"
                    else null

                    val decision: StepDecision = llmClient.decideNextStep(
                        systemPrompt = systemPrompt,
                        instruction = instruction,
                        screenshotBase64 = screenshot,
                        uiTreeSummary = uiTree,
                        history = history.toList(),
                        lastResult = lastResult
                    )

                    // 2d. 检查状态
                    when (decision.status) {
                        "completed" -> {
                            // 验证
                            if (llmClient.verifyCompletion(instruction, screenshot)) {
                                onStatus?.invoke("completed", "✅ 任务完成！")
                                memoryEngine.record(instruction, history.toList())
                                onComplete?.invoke(true, "任务完成")
                                return@launch
                            }
                            // 验证不通过，继续
                            continue
                        }
                        "failed" -> {
                            onStatus?.invoke("failed", decision.userMessage ?: "执行失败")
                            onComplete?.invoke(false, decision.userMessage ?: "失败")
                            return@launch
                        }
                    }

                    val action = decision.action ?: continue

                    // 2e. 安全检查
                    val checkResult = checkAction(action)
                    if (!checkResult.first) {
                        // 需要用户确认
                        val confirmed = CompletableDeferred<Boolean>()
                        onNeedConfirm?.invoke(action, checkResult.second) { approved ->
                            confirmed.complete(approved)
                        }
                        if (!confirmed.await()) {
                            onStatus?.invoke("cancelled", "用户取消")
                            onComplete?.invoke(false, "用户取消")
                            return@launch
                        }
                    }

                    // 2f. 执行操作
                    executeAction(action)

                    // 2g. 等待界面变化
                    delay(1500)

                    // 2h. 记录历史
                    history.add(StepRecord(
                        step = stepCount,
                        actionDesc = describeAction(action),
                        reasoning = decision.stepReasoning,
                        success = true
                    ))

                    onStatus?.invoke("in_progress", decision.userMessage ?: "执行中…")
                }

                onStatus?.invoke("timeout", "超出最大步数")
                onComplete?.invoke(false, "超出最大步数")

            } catch (e: CancellationException) {
                // 正常取消
            } catch (e: Exception) {
                Log.e(TAG, "执行异常", e)
                onStatus?.invoke("error", e.message ?: "未知错误")
                onComplete?.invoke(false, e.message ?: "错误")
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        onStatus?.invoke("cancelled", "用户取消")
    }

    private suspend fun executeChain(steps: List<Map<String, Any>>) {
        for ((i, step) in steps.withIndex()) {
            val actionJson = step["action"] as? Map<String, Any> ?: continue
            val action = Action(
                type = actionJson["type"] as? String ?: "wait",
                text = actionJson["text"] as? String,
                x = (actionJson["x"] as? Double)?.toInt(),
                y = (actionJson["y"] as? Double)?.toInt(),
                x1 = (actionJson["x1"] as? Double)?.toInt(),
                y1 = (actionJson["y1"] as? Double)?.toInt(),
                x2 = (actionJson["x2"] as? Double)?.toInt(),
                y2 = (actionJson["y2"] as? Double)?.toInt(),
                scrollDirection = actionJson["direction"] as? String,
                waitMs = (actionJson["ms"] as? Double)?.toInt()
            )
            onStep?.invoke(i + 1, "快捷执行：${describeAction(action)}")
            executeAction(action)
            delay(1000)
        }
        onStatus?.invoke("completed", "✅ 任务完成（快捷路径）")
        onComplete?.invoke(true, "快捷路径完成")
    }

    private suspend fun executeAction(action: Action) {
        Log.d(TAG, "执行: ${action.type} | text=${action.text} | x=${action.x} y=${action.y}")
        when (action.type) {
            "click" -> action.x?.let { x -> action.y?.let { y -> accessibilityService.click(x, y) } }
            "click_text" -> action.text?.let { accessibilityService.clickByText(it) {} }
            "long_press" -> action.x?.let { x -> action.y?.let { y ->
                accessibilityService.longPress(x, y, action.waitMs ?: 1000)
            }}
            "swipe" -> {
                action.x1?.let { x1 -> action.y1?.let { y1 ->
                    action.x2?.let { x2 -> action.y2?.let { y2 ->
                        accessibilityService.swipe(x1, y1, x2, y2)
                    }}
                }}
            }
            "input" -> action.text?.let { accessibilityService.inputText(it) }
            "back" -> accessibilityService.goBack()
            "home" -> accessibilityService.goHome()
            "recents" -> accessibilityService.goRecents()
            "scroll" -> action.scrollDirection?.let { accessibilityService.scroll(it) }
            "wait" -> delay(action.waitMs?.toLong() ?: 2000)
        }
    }

    private fun describeAction(action: Action): String = when (action.type) {
        "click" -> "点击(${action.x}, ${action.y})"
        "click_text" -> "点击「${action.text}」"
        "swipe" -> "滑动 ${action.x1},${action.y1} → ${action.x2},${action.y2}"
        "input" -> "输入「${action.text}」"
        "back" -> "返回"
        "home" -> "回到桌面"
        "scroll" -> "向${action.scrollDirection}滚动"
        "wait" -> "等待${action.waitMs}ms"
        else -> action.type
    }

    private fun checkAction(action: Action): Pair<Boolean, String> {
        val dangerous = listOf("支付", "付款", "转账", "删除", "发送", "下单", "购买")
        val text = action.text ?: ""
        for (kw in dangerous) {
            if (kw in text) return false to "高危操作「$kw」，请确认"
        }
        return true to ""
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "AgentEngine"

        fun buildSystemPrompt(): String = """你是一个手机操作助手，通过分析截屏来操作手机。
每次只做一个操作，等界面变化后再做下一步。
不确定时选保守做法。

动作：
- click(x, y)
- click_text(text) — 点击有该文字的元素
- swipe(x1,y1,x2,y2)
- input(text) — 输入文字（会先清空）
- back() / home() / recents()
- scroll(up/down)
- wait(ms)

输出严格 JSON：
{
  "step_reasoning": "为什么做这步",
  "action": {"type": "...", ...},
  "status": "in_progress|completed|failed",
  "user_message": "给用户看的提示"
}"""
    }
}
