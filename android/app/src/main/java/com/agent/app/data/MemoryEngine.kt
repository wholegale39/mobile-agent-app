package com.agent.app.data

import android.content.Context
import com.agent.app.api.StepRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 内存记忆引擎 — AppAgentX 链式记忆的轻量本地实现
 * 记录任务执行轨迹，下次相似任务直接复用
 */
class MemoryEngine(private val context: Context) {

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("agent_memory", Context.MODE_PRIVATE)
    val chains: MutableMap<String, Chain>
        get() = loadChains()

    /**
     * 检索最匹配的记忆链
     */
    fun query(taskDesc: String): Chain? {
        val allChains = loadChains()
        if (allChains.isEmpty()) return null

        var best: Chain? = null
        var bestScore = 0f

        for (chain in allChains.values) {
            val score = matchScore(taskDesc, chain)
            if (score > bestScore && score >= 0.6f) {
                bestScore = score
                best = chain
            }
        }
        return best
    }

    /**
     * 记录执行，尝试压缩为记忆链
     */
    fun record(taskDesc: String, history: List<StepRecord>) {
        if (history.size < 2) return

        val category = classify(taskDesc)
        val records = getRecords(category)
        records.add(ExecutionRecord(taskDesc, history.map { it.actionDesc }))

        // 重复 >= 2 次且成功 → 压缩为链
        if (records.size >= 2) {
            val commonSteps = findCommonSteps(records)
            if (commonSteps.size >= 2) {
                val chainId = "chain_${category}_${System.currentTimeMillis()}"
                val chain = Chain(
                    chainId = chainId,
                    name = taskDesc.take(60),
                    triggerWords = extractKeywords(records),
                    steps = commonSteps.map { mapOf("action" to it) },
                    usageCount = 0
                )
                saveChain(chain)
                // 清理记录
                clearRecords(category)
            }
        } else {
            saveRecords(category, records)
        }
    }

    private fun loadChains(): MutableMap<String, Chain> {
        val json = prefs.getString("chains", "{}") ?: "{}"
        val type = object : TypeToken<MutableMap<String, Chain>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    private fun saveChain(chain: Chain) {
        val all = loadChains()
        all[chain.chainId] = chain
        prefs.edit().putString("chains", gson.toJson(all)).apply()
    }

    private fun getRecords(category: String): MutableList<ExecutionRecord> {
        val json = prefs.getString("records_$category", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<ExecutionRecord>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }

    private fun saveRecords(category: String, records: List<ExecutionRecord>) {
        prefs.edit().putString("records_$category", gson.toJson(records)).apply()
    }

    private fun clearRecords(category: String) {
        prefs.edit().remove("records_$category").apply()
    }

    private fun matchScore(taskDesc: String, chain: Chain): Float {
        val desc = taskDesc.lowercase()
        val words = chain.triggerWords
        if (words.isEmpty()) return 0f
        val matches = words.count { it.lowercase() in desc }
        return matches.toFloat() / words.size
    }

    private fun classify(taskDesc: String): String {
        val categories = mapOf(
            "微信" to "wechat", "支付宝" to "alipay",
            "相册" to "gallery", "拍照" to "camera",
            "天气" to "weather", "音乐" to "music",
            "淘宝" to "taobao", "设置" to "settings"
        )
        for ((kw, cat) in categories) {
            if (kw in taskDesc) return cat
        }
        return taskDesc.take(10).hashCode().toString()
    }

    private fun extractKeywords(records: List<ExecutionRecord>): List<String> {
        val wordCount = mutableMapOf<String, Int>()
        records.forEach { r ->
            r.taskDesc.split(Regex("[\\s，。！？,.;:]+")).forEach { w ->
                if (w.length >= 2) wordCount[w] = (wordCount[w] ?: 0) + 1
            }
        }
        return wordCount.entries.sortedByDescending { it.value }
            .take(8).map { it.key }
    }

    private fun findCommonSteps(records: List<ExecutionRecord>): List<Map<String, String>> {
        if (records.isEmpty()) return emptyList()
        val ref = records.minByOrNull { it.steps.size }?.steps ?: return emptyList()
        return ref.filter { step ->
            records.all { r -> r.steps.any { it == step } }
        }.map { mapOf("type" to "click_text", "text" to it) }
    }

    data class Chain(
        val chainId: String,
        val name: String,
        val triggerWords: List<String>,
        val steps: List<Map<String, Any>>,
        val usageCount: Int = 0
    )

    data class ExecutionRecord(
        val taskDesc: String,
        val steps: List<String>
    )
}
