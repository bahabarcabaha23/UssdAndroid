package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "USSD_ACCESSIBILITY"

        @Volatile
        var instance: UssdAccessibilityService? = null
            private set

        // حالة الجلسة الحالية لاستخدامها من قبل الخادم المحلي (HTTP Server)
        var activeRequestId: String? = null
        var lastUssdResponse: String? = null
        var lastUssdStatus: String = "IDLE" // IDLE, WAITING_USER_INPUT, COMPLETED, ERROR
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "تم تشغيل خدمة USSD Accessibility بنجاح")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val root = rootInActiveWindow ?: return

        // التحقق من أن النافذة النشطة تنتمي لحوار USSD
        if (!isUssdDialog(event, root)) return

        val text = extractText(root)
        Log.d(TAG, "الحديث الملتقط من النافذة:\n$text")

        // 🟢 الاعتماد المباشر على البنية: هل يوجد حقل إدخال EditText؟
        val inputNode = findEditText(root)
        val hasInputField = inputNode != null

        if (hasInputField) {
            // 🟢 WAITING_USER_INPUT: الشبكة تنتظر رداً (مثل 1 للتأكيد)
            Log.d(TAG, "🟢 تم اكتشاف حقل إدخال -> الجلسة في حالة WAITING_USER_INPUT")
            lastUssdResponse = text
            lastUssdStatus = "WAITING_USER_INPUT"

            // نترك النافذة مفتوحة على الشاشة لكي يتمكن كود C# من إرسال الرد (مثل 1)
        } else {
            // 🔴 COMPLETED: إشعار نهائي (مثل نجاح التعبئة أو نفاد الرصيد)
            Log.d(TAG, "🔴 لا يوجد حقل إدخال -> الجلسة في حالة COMPLETED")
            lastUssdResponse = text
            lastUssdStatus = "COMPLETED"

            // إغلاق النافذة تلقائياً لتنظيف الشاشة
            val dismissBtn = findDismissButton(root)
            dismissBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    /**
     * تُستدعى من خادم الـ HTTP المحلي للتطبيق عندما يرسل تطبيق C# كود الإدخال (مثلاً "1")
     */
    fun sendInputToDialog(input: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val inputNode = findEditText(root) ?: return false

        // 1. كتابة النص داخل حقل الإدخال
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (!textSet) {
            Log.e(TAG, "فشل كتابة النص في حقل الإدخال")
            return false
        }

        // 2. الضغط على زر الإرسال (ENVOYER / SEND / OK)
        val sendBtn = findSendButton(root)
        return if (sendBtn != null) {
            sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            Log.e(TAG, "لم يتم العثور على زر الإرسال")
            false
        }
    }

    /**
     * إلغاء وإغلاق حوار USSD اليدوي عند الحاجة
     */
    fun dismissDialog(): Boolean {
        val root = rootInActiveWindow ?: return false
        val dismissBtn = findDismissButton(root)
        return dismissBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // =========================================================
    // الدوال المساعدة للبحث واستخراج العناصر من الشاشة
    // =========================================================

    private fun isUssdDialog(event: AccessibilityEvent, root: AccessibilityNodeInfo): Boolean {
        val packageName = event.packageName?.toString() ?: ""
        val isTelephonyPackage = packageName.contains("telephony") ||
                packageName.contains("phone") ||
                packageName.contains("android")

        val hasUssdElements = findDismissButton(root) != null || findEditText(root) != null
        return isTelephonyPackage && hasUssdElements
    }

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.className == "android.widget.EditText") {
            return node
        }

        val nodesById = node.findAccessibilityNodeInfosByViewId("android:id/input")
        if (!nodesById.isNullOrEmpty()) {
            return nodesById[0]
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findEditText(child)
            if (result != null) return result
        }

        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val sendKeywords = listOf("ENVOYER", "SEND", "ارسال", "إرسال", "OK")
        for (keyword in sendKeywords) {
            val nodes = node.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (n.isClickable) return n
                }
            }
        }

        val nodesById = node.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (!nodesById.isNullOrEmpty() && nodesById[0].isClickable) {
            return nodesById[0]
        }

        return null
    }

    private fun findDismissButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val dismissKeywords = listOf("ANNULER", "CANCEL", "إلغاء", "DISMISS", "FERMER")
        for (keyword in dismissKeywords) {
            val nodes = node.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (n.isClickable) return n
                }
            }
        }

        val nodesById = node.findAccessibilityNodeInfosByViewId("android:id/button2")
        if (!nodesById.isNullOrEmpty() && nodesById[0].isClickable) {
            return nodesById[0]
        }

        return null
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()

        fun traverse(n: AccessibilityNodeInfo) {
            if (n.className == "android.widget.TextView" && !n.text.isNullOrEmpty()) {
                val txt = n.text.toString().trim()
                if (txt.isNotEmpty() && !isButtonText(txt)) {
                    sb.append(txt).append("\n")
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { traverse(it) }
            }
        }

        traverse(node)
        return sb.toString().trim()
    }

    private fun isButtonText(text: String): Boolean {
        val buttonTexts = listOf("ENVOYER", "SEND", "ارسال", "إرسال", "ANNULER", "CANCEL", "إلغاء")
        return buttonTexts.any { it.equals(text, ignoreCase = true) }
    }

    override fun onInterrupt() {
        Log.w(TAG, "انقطع اتصال خدمة Accessibility")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "تم توقيف خدمة USSD Accessibility")
    }
}
