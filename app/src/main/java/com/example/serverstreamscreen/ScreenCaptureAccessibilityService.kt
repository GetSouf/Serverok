package com.example.serverstreamscreen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class ScreenCaptureAccessibilityService : AccessibilityService() {

    private var startButtonClicked = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        Log.d("AutoClickService", "Accessibility event: ${event.eventType}, package: ${event.packageName}")

        if (event.packageName == "com.android.systemui") {
            val rootNode = rootInActiveWindow ?: return
            if (!startButtonClicked) {
                findAndClickStartButton(rootNode)
            } else {
                findAndClickFirstApp(rootNode)
            }
        }
    }

    private fun findAndClickStartButton(node: AccessibilityNodeInfo) {
        val startButtons = node.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (startButtons.isNotEmpty()) {
            for (button in startButtons) {
                if (button.text == "Start" && button.isClickable) {
                    Log.d("AutoClickService", "Найдена кнопка Start, выполняем нажатие")
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    startButtonClicked = true
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAndClickStartButton(child)
            child.recycle()
        }
    }

    private fun findAndClickFirstApp(node: AccessibilityNodeInfo) {
        val recyclerViews = node.findAccessibilityNodeInfosByViewId("com.android.systemui:id/media_projection_recent_tasks_recycler")
        if (recyclerViews.isNotEmpty()) {
            val recyclerView = recyclerViews[0]
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChild(i) ?: continue
                if (child.className == "android.widget.LinearLayout" && child.isClickable) {
                    Log.d("AutoClickService", "Найден первый элемент приложения, выполняем нажатие")
                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    recyclerView.recycle()
                    return
                }
                child.recycle()
            }
            recyclerView.recycle()
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAndClickFirstApp(child)
            child.recycle()
        }
    }

    override fun onInterrupt() {
        Log.d("AutoClickService", "Accessibility Service прерван")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoClickService", "Accessibility Service подключен")
        startButtonClicked = false

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf("com.android.systemui")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        setServiceInfo(info)
    }
}
