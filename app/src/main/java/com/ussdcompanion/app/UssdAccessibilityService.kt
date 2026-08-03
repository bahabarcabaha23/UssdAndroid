package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * تراقب ظهور نافذة رد الـ USSD النظامية وتتعامل مع الإدخال والإغلاق التلقائي.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile 
        var instance: UssdAccessibilityService? = null

        private val DISMISS_BUTTON_TEXTS = listOf(
            "OK", "Ok", "ok", "موافق", "Cancel", "CANCEL", "إلغاء", "Dismiss", "Close", "إغلاق",
            "ANNULER", "Annuler", "annuler", "Fermer", "fermer"
        )
        private val SEND_BUTTON_TEXTS = listOf(
            "Send", "SEND", "إرسال", "موافق", "OK",
            "ENVOYER", "Envoyer", "envoyer"
        )
        private val STANDARD_DIALOG_BUTTON_IDS = listOf(
            "android:id/button1", "android:id/button2", "android:id/button3"
        )

        private val BALANCE_PATTERN = Regex(
            "(SOLDE|رصيد|Solde|Crédit|Credit)|(\\d[\\d.,]*\\s*(DA|دج))",
            RegexOption.IGNORE_CASE
        )

        private val MULTI_OPTION_MENU_PATTERN = Regex("""[1-9]\d?\s*:""")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * تُستدعى مباشرة من HttpServerService عند استقبال كود التتابع/التأكيد
     */
    fun performPendingActionsDirectly() {
        val root = rootInActiveWindow
        if (root == null) {
            ActivityLog.add("[إدخال] فشل: لا توجد نافذة نشطة (rootInActiveWindow == null)")
            return
        }
        applyPendingActions(root)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        if (event.packageName == packageName) return
        if (UssdSessionState.status == UssdSessionState.STATUS_IDLE) return

        val root = rootInActiveWindow ?: return
        if (root.packageName == packageName) return

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
        val looksLikeBalanceReply = BALANCE_PATTERN.containsMatchIn(text)
        val looksLikeMultiChoiceMenu = MULTI_OPTION_MENU_PATTERN.findAll(text).count() >= 2

        if (hasInputField && (looksLikeMultiChoiceMenu || !looksLikeBalanceReply)) {
            if (UssdSessionState.status != UssdSessionState.STATUS_WAITING_USER_INPUT || UssdSessionState.message != text) {
                UssdSessionState.status = UssdSessionState.STATUS_WAITING_USER_INPUT
                UssdSessionState.message = text
                ActivityLog.add("رد USSD (بانتظار إدخال): $text")
            }
            return
        }

        val dismissButton = findDismissButton(root)
        if (dismissButton != null || looksLikeBalanceReply) {
            UssdSessionState.status = UssdSessionState.STATUS_COMPLETED
            UssdSessionState.message = text
            ActivityLog.add("رد USSD (نهائي): $text")

            if (dismissButton != null) {
                val clicked = dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActivityLog.add(if (clicked) "تم إغلاق حوار USSD تلقائياً" else "تعذّر النقر التلقائي على زر الإغلاق")
            } else {
                ActivityLog.add("تم استخراج الرصيد؛ بانتظار توفر زر إغلاق مناسب")
            }
        }
    }

    private fun applyPendingActions(root: AccessibilityNodeInfo) {
        val toSend = UssdSessionState.pendingInputToSend.getAndSet(null)
        if (toSend != null) {
            val field = findEditText(root)
            val sendBtn = findButtonByText(root, SEND_BUTTON_TEXTS)
            
            if (field != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, toSend)
                }
                
                // تسجيل نتيجة كتابة النص
                val setTextSuccess = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ActivityLog.add("[إدخال] نتيجة كتابة النص ('$toSend'): $setTextSuccess")

                if (setTextSuccess && sendBtn != null) {
                    // تسجيل نتيجة الضغط على زر الإرسال
                    val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ActivityLog.add("[إدخال] نتيجة الضغط على زر الإرسال: $clickSuccess")

                    if (clickSuccess) {
                        UssdSessionState.status = UssdSessionState.STATUS_PENDING
                    }
                } else if (sendBtn == null) {
                    ActivityLog.add("[إدخال] فشل: لم يُعثر على زر الإرسال (ENVOYER)")
                }
            } else {
                ActivityLog.add("[إدخال] فشل: لم يُعثر على حقل الإدخال (EditText)")
            }
        }

        // طلب إغلاق صريح من الكمبيوتر (POST /ussd/dismiss)
        if (UssdSessionState.dismissRequested) {
            val closeBtn = findDismissButton(root)
            val clicked = closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            ActivityLog.add(if (clicked) "تم إغلاق حوار USSD بطلب من البرنامج" else "طلب إغلاق لكن لم يُعثر على زر مناسب")
            UssdSessionState.reset()
        }
    }

    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in STANDARD_DIALOG_BUTTON_IDS) {
            val found = try {
                root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull { it.isEnabled }
            } catch (e: Exception) {
                null
            }
            if (found != null) return found
        }

        findButtonByText(root, DISMISS_BUTTON_TEXTS)?.let { return it }

        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        if (buttons.size == 1) return buttons[0]

        return null
    }

    private fun collectButtons(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.className == "android.widget.Button") out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectButtons(child, out)
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

    private fun findButtonByText(node: AccessibilityNodeInfo, options: List<String>): AccessibilityNodeInfo? {
        // 🟢 إضافة trim() لإزالة أي مسافات فارغة قد تمنع التطابق
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
