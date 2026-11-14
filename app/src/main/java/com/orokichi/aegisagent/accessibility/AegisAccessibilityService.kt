package com.orokichi.aegisagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.orokichi.aegisagent.agent.AgentEngine
import com.orokichi.aegisagent.data.SettingsStore
import com.orokichi.aegisagent.model.AgentRunState
import com.orokichi.aegisagent.model.PendingApproval
import com.orokichi.aegisagent.model.ScreenSnapshot
import com.orokichi.aegisagent.model.UiElementNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AegisAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var stopOverlayView: View? = null
    private var approvalOverlayView: View? = null
    private val nodeCache = mutableMapOf<Int, AccessibilityNodeInfo>()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                val settings = SettingsStore(context)
                if (settings.stopOnScreenOff && AgentEngine.isActive()) {
                    AgentEngine.emergencyStop(context, "画面オフを検知したため安全装置により自動停止しました")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected.value = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        serviceScope.launch {
            combine(AgentEngine.runState, AgentEngine.pendingApproval) { state, approval ->
                state to approval
            }.collect { (state, approval) ->
                updateStopOverlay(state)
                updateApprovalOverlay(approval)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // オンデマンドでcaptureSnapshot()を使うため軽量に保つ
    }

    override fun onInterrupt() {
        AgentEngine.emergencyStop(this, "ユーザー補助サービスが中断されました")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenOffReceiver) }
        removeStopOverlay()
        removeApprovalOverlay()
        clearNodeCache()
        serviceScope.cancel()
        if (instance == this) instance = null
        isConnected.value = false
    }

    fun captureSnapshot(): ScreenSnapshot {
        clearNodeCache()
        val root = rootInActiveWindow ?: return ScreenSnapshot(
            packageName = "unknown",
            elements = emptyList()
        )
        val pkg = root.packageName?.toString() ?: "unknown"
        val collected = mutableListOf<UiElementNode>()
        traverseNode(root, collected)
        return ScreenSnapshot(packageName = pkg, elements = collected)
    }

    private fun traverseNode(node: AccessibilityNodeInfo, out: MutableList<UiElementNode>) {
        if (out.size >= 85) return
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val meaningful = node.isVisibleToUser &&
            !bounds.isEmpty &&
            (node.isClickable || node.isEditable || text.isNotBlank() || desc.isNotBlank())

        if (meaningful) {
            val idx = out.size
            out.add(
                UiElementNode(
                    index = idx,
                    text = text,
                    contentDesc = desc,
                    viewId = viewId,
                    className = cls,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isPassword = node.isPassword,
                    bounds = bounds
                )
            )
            nodeCache[idx] = AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, out)
            child.recycle()
        }
    }

    fun performClickOnIndex(index: Int): Boolean {
        val node = nodeCache[index] ?: return false
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
            current = current.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    fun performTypeTextOnIndex(index: Int, text: String): Boolean {
        val node = nodeCache[index] ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performScroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (direction.equals("UP", ignoreCase = true)) {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return scrollRecursive(root, action)
    }

    private fun scrollRecursive(node: AccessibilityNodeInfo, action: Int): Boolean {
        if (node.isScrollable && node.performAction(action)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val ok = scrollRecursive(child, action)
            child.recycle()
            if (ok) return true
        }
        return false
    }

    fun launchAppByPackage(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clearNodeCache() {
        nodeCache.values.forEach { runCatching { it.recycle() } }
        nodeCache.clear()
    }

    private fun updateStopOverlay(state: AgentRunState) {
        val settings = SettingsStore(this)
        val shouldShow = settings.showFloatingStopButton &&
            (state == AgentRunState.RUNNING || state == AgentRunState.WAITING_APPROVAL)

        if (shouldShow && stopOverlayView == null) {
            showStopOverlay()
        } else if (!shouldShow && stopOverlayView != null) {
            removeStopOverlay()
        }
    }

    private fun showStopOverlay() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()

        val button = TextView(this).apply {
            text = "🛑 緊急停止 STOP"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = 999f
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 12 * density
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (120 * density).toInt()
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchX - event.rawX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { wm.updateViewLayout(button, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        AgentEngine.emergencyStop(this@AegisAccessibilityService, "フローティング緊急停止ボタンが押されました")
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(button, params)
            stopOverlayView = button
        }
    }

    private fun removeStopOverlay() {
        stopOverlayView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        stopOverlayView = null
    }

    private fun updateApprovalOverlay(approval: PendingApproval?) {
        removeApprovalOverlay()
        if (approval == null) return
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1B24"))
                cornerRadius = 24 * density
                setStroke((2 * density).toInt(), Color.parseColor("#FFB300"))
            }
            elevation = 16 * density
        }

        val title = TextView(this).apply {
            text = "🛡️ セキュリティセーブ：確認待ち"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 16f
        }
        val body = TextView(this).apply {
            text = "操作: ${approval.decision.actionType.label}\n対象: ${approval.targetDescription}\n理由: ${approval.verdict.reason}"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, (8 * density).toInt(), 0, (14 * density).toInt())
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val denyBtn = Button(this).apply {
            text = "停止・拒否"
            setOnClickListener { AgentEngine.resolveApproval(this@AegisAccessibilityService, false) }
        }
        val allowBtn = Button(this).apply {
            text = "1回許可"
            setOnClickListener { AgentEngine.resolveApproval(this@AegisAccessibilityService, true) }
        }
        row.addView(denyBtn)
        row.addView(allowBtn)

        container.addView(title)
        container.addView(body)
        container.addView(row)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        runCatching {
            wm.addView(container, params)
            approvalOverlayView = container
        }
    }

    private fun removeApprovalOverlay() {
        approvalOverlayView?.let { v ->
            runCatching { windowManager?.removeView(v) }
        }
        approvalOverlayView = null
    }

    companion object {
        val isConnected = MutableStateFlow(false)
        var instance: AegisAccessibilityService? = null
    }
}
