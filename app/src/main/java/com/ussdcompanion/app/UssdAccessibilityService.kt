package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * تراقب ظهور نافذة رد الـ USSD النظامية وتستخرج النص
 * وتدعم واجهات Huawei EMUI بإغلاق النافذة تلقائياً عبر ID الأزرار القياسية أو النصوص.
 */
class UssdAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // لا داعي للمعالجة إن لم تكن هناك جلسة USSD نشطة من جهتنا
        if (UssdSessionState.status == UssdSessionState.STATUS_IDLE) return

        val root = rootInActiveWindow ?: return
        try {
            handlePossibleUssdDialog(root)
            applyPendingActions(root)
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء قراءة نافذة USSD: ${e.message}")
        }
    }

    private fun handlePossibleUssdDialog(root: AccessibilityNodeInfo) {
        if (UssdSessionState.status == UssdSessionState.STATUS_COMPLETED) return

        val allText = StringBuilder()
        collectText(root, allText)
        val text = allText.toString().trim()
        if (text.isEmpty() || text.length > 2000) return

        val hasInputField = findEditText(root) != null

        if (hasInputField) {
            if (UssdSessionState.status != UssdSessionState.STATUS_WAITING_USER_INPUT || UssdSessionState.message != text) {
                UssdSessionState.status = UssdSessionState.STATUS_WAITING_USER_INPUT
                UssdSessionState.message = text
                ActivityLog.add("رد USSD (بانتظار إدخال): $text")
            }
        } else {
            // البحث عن زر الإغلاق بالـ ID أولاً (خصائص EMUI/Android) ثم بالأزرار النصية المقبولة
            val dismissButton = findDismissButton(root)
            
            if (dismissButton != null || text.isNotEmpty()) {
                UssdSessionState.status = UssdSessionState.STATUS_COMPLETED
                UssdSessionState.message = text
                ActivityLog.add("رد USSD (نهائي): $text")

                // إغلاق النافذة المنبثقة فوراً بالنقر البرمجي على زر OK / Dismiss
                dismissButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun applyPendingActions(root: AccessibilityNodeInfo) {
        val toSend = UssdSessionState.pendingInputToSend.getAndSet(null)
        if (toSend != null) {
            val field = findEditText(root)
            val sendBtn = findDismissButton(root) ?: findButtonByText(root, listOf("Send", "SEND", "إرسال", "موافق", "OK", "Ok"))
            if (field != null) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, toSend)
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActivityLog.add("تم إرسال الإدخال: $toSend")
            } else {
                ActivityLog.add("تعذّر العثور على حقل الإدخال لإرسال: $toSend")
            }
        }

        if (UssdSessionState.dismissRequested) {
            val closeBtn = findDismissButton(root)
            closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            UssdSessionState.dismissRequested = false
            ActivityLog.add("تم إغلاق حوار USSD")
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        node.text?.let { if (it.isNotBlank()) out.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
        }
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * يبحث عن زر الإغلاق أولاً عبر معرّف النظام القياسي (android:id/button1)
     * ثم عبر النصوص الشائعة في واجهات Huawei والأجهزة الأخرى.
     */
    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. البحث بواسطة المعرفات البرمجية لنظام أندرويد
        val buttonIds = listOf("android:id/button1", "android:id/button2", "android:id/button3")
        for (id in buttonIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }

        // 2. البحث بواسطة الكلمات النصية المرادفة لزر الإغلاق/الموافقة
        val options = listOf("OK", "Ok", "ok", "موافق", "تم", "Cancel", "إلغاء", "Dismiss", "Close")
        return findButtonByText(root, options)
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, options: List<String>): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()?.trim()
        if (nodeText != null && options.any { nodeText.equals(it, ignoreCase = true) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findButtonByText(child, options)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {
        ActivityLog.add("تم إيقاف خدمة الوصول")
    }
}
