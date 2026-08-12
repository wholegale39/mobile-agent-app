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

    /** ref -> 元素快照，每次 getUiTreeSummary() 重建 */
    private val elementRefs = mutableMapOf<String, ElementSnapshot>()

    data class ElementSnapshot(
        val label: String,
        val centerX: Int,
        val centerY: Int,
        val tags: String
    )

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

    /**
     * 通过 ref（如 @e3）点击元素。
     * ref 只对最近一次 getUiTreeSummary() 有效（agent-device 同款原则）。
     */
    fun clickByRef(ref: String, callback: (Boolean) -> Unit) {
        val entry = elementRefs[ref]
        if (entry == null) {
            Log.w(TAG, "clickByRef: 无效 ref $ref")
            callback(false)
            return
        }
        // 用快照坐标点击（避免长期持有 AccessibilityNodeInfo 引用）
        click(entry.centerX, entry.centerY)
        Log.d(TAG, "clickByRef($ref) -> (${entry.centerX}, ${entry.centerY})")
        callback(true)
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

    /** 通过包名打开应用（Agent 的 open_app 动作） */
    fun openApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "openApp: $packageName")
        } else {
            Log.w(TAG, "openApp: 未找到包 $packageName，尝试去桌面找图标")
            goHome()
        }
    }

    // ──── 获取 UI 树（给 LLM 看的摘要） ────

    fun getUiTreeSummary(): String {
        val root = rootInActiveWindow ?: return "（无界面）"
        elementRefs.clear()
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
            val ref = "@e${elementRefs.size + 1}"
            elementRefs[ref] = ElementSnapshot(label, bounds.centerX(), bounds.centerY(), tags)
            result.add("  ${ref} $label @(${bounds.centerX()},${bounds.centerY()}) $tags")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectElements(it, result, depth + 1) }
        }
    }

    /**
     * UI 树签名 — 用于 settle 判断界面是否稳定。
     * 只比较结构，不生成完整摘要（省 token）。
     */
    fun getUiTreeSignature(): String {
        val root = rootInActiveWindow ?: return "empty"
        val sb = StringBuilder()
        collectSignature(root, sb, 0)
        return sb.toString()
    }

    private fun collectSignature(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 6 || !node.isVisibleToUser) return
        val text = node.text?.toString()?.take(20) ?: ""
        val desc = node.contentDescription?.toString()?.take(20) ?: ""
        if (node.isClickable || node.isScrollable || node.isEditable || text.isNotEmpty()) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            sb.append("${node.className}|$text|$desc|${bounds.centerX()},${bounds.centerY()};")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectSignature(it, sb, depth + 1) }
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
