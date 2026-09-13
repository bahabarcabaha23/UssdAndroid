package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

class UssdAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: UssdAccessibilityService? = null
        private val DISMISS_BUTTON_TEXTS = listOf(
            "OK", "Ok", "ok", "Cancel", "CANCEL", "Dismiss", "Close", "Done",
            "موافق", "إلغاء", "تم", "إغلاق", "حسنًا",
            "ANNULER", "Annuler", "annuler", "FERMER", "Fermer", "fermer",
            "VALIDER", "Valider", "D'ACCORD", "D'accord", "d'accord",
            "Aceptar", "Cancelar", "Fechar", "Confirmar"
        )
        private val SEND_BUTTON_TEXTS = listOf(
            "Send", "SEND", "إرسال", "موافق", "OK",
            "ENVOYER", "Envoyer", "envoyer",
            "VALIDER", "Valider", "valider"
        )
        private val STANDARD_DIALOG_BUTTON_IDS = listOf(
            "android:id/button1", "android:id/button2", "android:id/button3"
        )
        private val IGNORED_SYSTEM_MESSAGES = listOf(
            "ussd code running",
            "exécution du code ussd",
            "execution du code ussd",
            "رمز ussd قيد التشغيل",
            "يتم تشغيل رمز ussd"
        )
        private val BALANCE_VIA_SMS_PATTERN = Regex(
            """(?:(?:par|via|dans\s*un|recevrez\s*un|envoy[eé]\s*par|عبر|في)\s*sms)|(?:sms\s*(?:vous\s*sera|envoy[eé]|يصلك|ستصلك))|(?:(?:solde|cr[ée]dit|رصيد).{0,50}(?:par\s*sms|via\s*sms|عبر\s*sms|عبر\s*رسالة))""",
            RegexOption.IGNORE_CASE
        )
        private val RECHARGE_NOTIFICATION_EXCLUSIONS = Regex(
            "recharger|recharge|prise en charge|transaction|transferer|transférer|voice/sms|voice / sms",
            RegexOption.IGNORE_CASE
        )
        private val BALANCE_VALUE_PATTERN = Regex("""(\d+[.,]?\d*)\s*(DA|دج)""", RegexOption.IGNORE_CASE)
        private const val BALANCE_SMS_WAIT_MS = 40_000L
        private const val SMS_POLL_INTERVAL_MS = 2_000L
        private const val FINALIZE_SETTLE_MS = 2500L

        private val KNOWN_TELEPHONY_KEYWORDS = listOf(
            "telephony", "phone", "telecom", "incallui", "dialer",
            "huawei.android.phone", "hwext", "hwcallui",
            "samsung.android.incallui", "sec.android.phone", "samsung.android.dialer",
            "coloros.phone", "oppo.phone", "coloros.dialer", "oppo.dialer",
            "oplus.phone", "oplus.dialer", "coloros.telephony",
            "realme.phone", "oneplus.phone", "heytap.phone", "heytap.telephony",
            "miui.phone", "xiaomi.phone", "vivo.phone", "bbk.phone"
        )
        private val SYSTEM_DIALOG_EXCLUSION_KEYWORDS = listOf(
            "permission", "allow", "deny", "settings",
            "إذن", "أذونات", "السماح", "رفض", "الإعدادات", "الوصول إلى"
        )
        private val lastUnmatchedPackageLogTime = ConcurrentHashMap<String, Long>()
        private const val UNMATCHED_PACKAGE_LOG_THROTTLE_MS = 5_000L
    }

    @Volatile private var pendingCandidateText: String? = null
    @Volatile private var pendingCandidateAt: Long = 0L
    @Volatile var pendingInputToSend: String? = null
    @Volatile var dismissRequested: Boolean = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performPendingActionsDirectly() {
        val root = rootInActiveWindow ?: return
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
        val currentPackageName = root.packageName?.toString()?.lowercase() ?: ""
        if (!isTelephonyRelatedWindow(root, currentPackageName)) {
            logUnmatchedWindowPackageForDiagnostics(currentPackageName)
            return
        }
        try {
            handlePossibleUssdDialog(root)
            applyPendingActions(root)
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء قراءة نافذة USSD: ${e.message}")
        }
    }

    private fun logUnmatchedWindowPackageForDiagnostics(pkg: String) {
        if (pkg.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = lastUnmatchedPackageLogTime[pkg] ?: 0L
        if (now - last < UNMATCHED_PACKAGE_LOG_THROTTLE_MS) return
        lastUnmatchedPackageLogTime[pkg] = now
        ActivityLog.add("[تشخيص] حزمة نافذة غير معروفة تم تجاهلها أثناء جلسة نشطة: $pkg")
    }

    private fun isTelephonyRelatedWindow(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val isKnownTelephonyPackage = KNOWN_TELEPHONY_KEYWORDS.any { packageName.contains(it) }
        if (isKnownTelephonyPackage) return true
        if (packageName == "android" || packageName == "com.android.systemui") {
            val quickText = StringBuilder()
            collectText(root, quickText)
            val lower = quickText.toString().lowercase()
            val looksLikeUnrelatedSystemDialog = SYSTEM_DIALOG_EXCLUSION_KEYWORDS.any { lower.contains(it) }
            val looksLikeUssd = lower.contains("ussd") ||
                    lower.contains("code") ||
                    lower.contains("solde") ||
                    lower.contains("رصيد") ||
                    lower.contains("عرض") ||
                    lower.contains("offre") ||
                    lower.contains("pixx") ||
                    lower.contains("menu")
            return !looksLikeUnrelatedSystemDialog && looksLikeUssd
        }
        return false
    }

    private fun handlePossibleUssdDialog(root: AccessibilityNodeInfo) {
        if (UssdSessionState.status == UssdSessionState.STATUS_WAITING_SMS_BALANCE) return
        val allText = StringBuilder()
        collectText(root, allText)
        val text = allText.toString().trim()
        if (text.isEmpty() || text.length > 2000) return

        val lowerText = text.lowercase()
        val isSystemMessage = IGNORED_SYSTEM_MESSAGES.any { lowerText.contains(it) }
        val isLoadingProgress = hasProgressIndicator(root)
        if (isSystemMessage || isLoadingProgress) {
            ActivityLog.add("تم تجاهل نافذة تحميل مؤقتة بانتظار الرد الفعلي من الشبكة.")
            return
        }

        val hasInputField = findEditText(root) != null
        if (hasInputField) {
            if (UssdSessionState.status != UssdSessionState.STATUS_WAITING_USER_INPUT ||
                UssdSessionState.message != text) {
                UssdSessionState.updateStatus(UssdSessionState.STATUS_WAITING_USER_INPUT, text)
                ActivityLog.add("✋ حوار USSD (بانتظار إدخال): $text")
            }
            pendingCandidateText = null
            return
        }

        val hasDirectBalanceValue = BALANCE_VALUE_PATTERN.containsMatchIn(text) ||
                Regex("""(?:cr[ée]dit|solde|رصيد)[^\d]*?\d+""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val looksLikeBalanceViaSms = !hasDirectBalanceValue &&
                BALANCE_VIA_SMS_PATTERN.containsMatchIn(text) &&
                !RECHARGE_NOTIFICATION_EXCLUSIONS.containsMatchIn(text)
        if (looksLikeBalanceViaSms) {
            UssdSessionState.updateStatus(UssdSessionState.STATUS_WAITING_SMS_BALANCE)
            ActivityLog.add("رد USSD (إشعار رصيد عبر SMS): $text — بانتظار الرسالة حتى ${BALANCE_SMS_WAIT_MS / 1000} ثانية")
            startBalanceSmsWait()
            return
        }

        if (pendingCandidateText == text) {
            val age = System.currentTimeMillis() - pendingCandidateAt
            if (age >= FINALIZE_SETTLE_MS &&
                UssdSessionState.status == UssdSessionState.STATUS_PENDING) {
                UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, text)
                ActivityLog.add("✅ رد USSD (نهائي - استقر بعد ${age}ms): $text")
                pendingCandidateText = null
            }
            return
        }

        pendingCandidateText = text
        pendingCandidateAt = System.currentTimeMillis()
        if (UssdSessionState.status != UssdSessionState.STATUS_PENDING ||
            UssdSessionState.message != text) {
            UssdSessionState.updateStatus(UssdSessionState.STATUS_PENDING, text)
            ActivityLog.add("رد USSD (مرشّح - بانتظار استقرار ${FINALIZE_SETTLE_MS}ms): $text")
        }

        val capturedText = text
        val requestIdAtStart = UssdSessionState.currentRequestId
        Thread {
            try {
                Thread.sleep(FINALIZE_SETTLE_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (UssdSessionState.currentRequestId != requestIdAtStart) return@Thread
            if (pendingCandidateText != capturedText) return@Thread
            if (UssdSessionState.status != UssdSessionState.STATUS_PENDING) return@Thread

            UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, capturedText)
            ActivityLog.add("✅ رد USSD (نهائي - استقر): $capturedText")
        }.start()
    }

    private fun applyPendingActions(root: AccessibilityNodeInfo) {
        if (dismissRequested) {
            dismissRequested = false
            clickDismissButton(root)
            return
        }
        val input = pendingInputToSend
        if (input != null) {
            val editText = findEditText(root)
            if (editText != null) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
                }
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                pendingInputToSend = null
                
                // محاولة النقر على زر الإرسال بعد تعبئة النص
                clickSendButton(root)
            }
        }
    }

    private fun clickDismissButton(root: AccessibilityNodeInfo): Boolean {
        return findAndClickButtonByTexts(root, DISMISS_BUTTON_TEXTS)
    }

    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        return findAndClickButtonByTexts(root, SEND_BUTTON_TEXTS)
    }

    private fun findAndClickButtonByTexts(node: AccessibilityNodeInfo, texts: List<String>): Boolean {
        val textStr = node.text?.toString()?.trim() ?: ""
        if (node.isClickable && texts.any { textStr.equals(it, true) }) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickButtonByTexts(child, texts)) return true
        }
        return false
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (cls == "android.widget.EditText" ||
            cls.endsWith("EditText", ignoreCase = true) ||
            cls.contains("AutoComplete", ignoreCase = true)) {
            return node
        }
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun hasProgressIndicator(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("ProgressBar", ignoreCase = true) || cls.contains("ProgressIndicator", ignoreCase = true)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasProgressIndicator(child)) return true
        }
        return false
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val t = node.text
        if (!t.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(t)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb)
        }
    }

    private fun startBalanceSmsWait() {
        val waitStartMs = System.currentTimeMillis()
        val requestIdAtStart = UssdSessionState.currentRequestId
        Thread {
            try {
                val deadline = waitStartMs + BALANCE_SMS_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    if (UssdSessionState.currentRequestId != requestIdAtStart) return@Thread
                    val newSmsBody = findNewSmsSince(waitStartMs)
                    if (newSmsBody != null) {
                        val balance = extractBalanceValue(newSmsBody)
                        val finalMessage = if (balance != null)
                            "الرصيد: $balance (نص رسالة: $newSmsBody)"
                        else
                            "وصلت رسالة SMS لكن تعذّر استخراج قيمة الرصيد - النص الكامل: $newSmsBody"
                        ActivityLog.add("رد USSD (نهائي - رصيد عبر SMS): $finalMessage")
                        UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, finalMessage)
                        return@Thread
                    }
                    Thread.sleep(SMS_POLL_INTERVAL_MS)
                }
                if (UssdSessionState.currentRequestId != requestIdAtStart) return@Thread
                val timeoutMessage = "لم تصل رسالة الرصيد عبر SMS خلال ${BALANCE_SMS_WAIT_MS / 1000} ثانية."
                ActivityLog.add("رد USSD (نهائي - مهلة انتظار الرصيد): $timeoutMessage")
                UssdSessionState.updateStatus(UssdSessionState.STATUS_COMPLETED, timeoutMessage)
            } catch (e: Exception) {
                ActivityLog.add("خطأ أثناء انتظار رسالة الرصيد: ${e.message}")
            }
        }.start()
    }

    private fun findNewSmsSince(sinceMs: Long): String? {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.parse("content://sms/inbox")
            contentResolver.query(uri, arrayOf("body", "date"), null, null, "date DESC LIMIT 1")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val date = cursor.getLong(1)
                    val body = cursor.getString(0)
                    if (date > sinceMs && body != null && BALANCE_VALUE_PATTERN.containsMatchIn(body)) body else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBalanceValue(smsBody: String): String? {
        val match = BALANCE_VALUE_PATTERN.find(smsBody) ?: return null
        return "${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
    }

    override fun onInterrupt() {
        instance = null
    }
}
