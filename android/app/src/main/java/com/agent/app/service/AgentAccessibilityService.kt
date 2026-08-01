package com.agent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务 — Agent 的"手"
 * 执行点击、滑动、输入等操作
 */
class AgentAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    var onActionResult: ((Boolean, String?) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ 无障碍服务已连接")
        // 通知 UI
        sendBroadcast(Intent(ACTION_SERVICE_CONNECTED))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 可以在这里监听界面变化
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ 无障碍服务被中断")
    }

    // ──── 公开操作 API ────

    fun click(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "click($x, $y)")
    }

    fun clickByText(text: String, callback: (Boolean) -> Unit) {
        val root = rootInActiveWindow ?: run {
            callback(false)
            return
        }
        callback(findAndClick(root, text))
    }

    fun longPress(x: Int, y: Int, durationMs: Int = 1000) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 500) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun inputText(text: String, clearFirst: Boolean = true) {
        val root = rootInActiveWindow ?: return
        val focused = findFocused(root)
        if (focused != null && focused.isEditable) {
            if (clearFirst) {
                focused.performAction(0x00001000) // ACTION_SELECT_ALL
                focused.performAction(0x00001002) // ACTION_CUT
            }
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "inputText: \"$text\"")
        }
    }

    fun scroll(direction: String) {
        val root = rootInActiveWindow ?: return
        val display = resources.displayMetrics
        val cx = display.widthPixels / 2
        val cy = display.heightPixels / 2
        val dist = (display.heightPixels * 0.4).toInt()

        when (direction) {
            "up" -> swipe(cx, cy + dist, cx, cy - dist, 400)
            "down" -> swipe(cx, cy - dist, cx, cy + dist, 400)
            "left" -> swipe(cx + dist, cy, cx - dist, cy, 400)
            "right" -> swipe(cx - dist, cy, cx + dist, cy, 400)
        }
    }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ──── 获取 UI 树（给 LLM 看的摘要） ────

    fun getUiTreeSummary(): String {
        val root = rootInActiveWindow ?: return "（无界面）"
        val elements = mutableListOf<String>()
        collectElements(root, elements, 0)
        if (elements.isEmpty()) return "（无可交互元素）"
        return elements.joinToString("\n")
    }

    private fun collectElements(node: AccessibilityNodeInfo, result: MutableList<String>, depth: Int) {
        if (depth > 6) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.take(30) ?: ""

        if (node.isClickable || node.isScrollable || node.isEditable || text.isNotEmpty()) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val label = when {
                text.isNotEmpty() -> "\"$text\""
                desc.isNotEmpty() -> "[$desc]"
                else -> "(icon)"
            }
            val tags = buildString {
                if (node.isClickable) append("可点击 ")
                if (node.isScrollable) append("可滚动 ")
                if (node.isEditable) append("可输入 ")
            }
            result.add("  ${"  ".repeat(depth)}$label @(${bounds.centerX()},${bounds.centerY()}) $tags")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectElements(it, result, depth + 1) }
        }
    }

    // ──── 辅助方法 ────

    private fun findAndClick(node: AccessibilityNodeInfo, text: String): Boolean {
        val nodeText = node.text?.toString() ?: ""
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (text in nodeText || text in nodeDesc) {
            return if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                true
            } else {
                node.parent?.let { p ->
                    if (p.isClickable) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); true }
                    else false
                } ?: false
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { if (findAndClick(it, text)) return true }
        }
        return false
    }

    private fun findFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findFocused(it) }?.let { return it }
        }
        return null
    }

    override fun onDestroy() {
        instance = null
        sendBroadcast(Intent(ACTION_SERVICE_DISCONNECTED))
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AgentAccessibilityService"
        const val ACTION_SERVICE_CONNECTED = "com.agent.app.SERVICE_CONNECTED"
        const val ACTION_SERVICE_DISCONNECTED = "com.agent.app.SERVICE_DISCONNECTED"

        /** 当前活跃服务实例（onServiceConnected 后可用） */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        fun isEnabled(context: android.content.Context): Boolean {
            val serviceStr = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return serviceStr?.contains(context.packageName + "/" +
                AgentAccessibilityService::class.java.name) == true
        }
    }
}
