package com.ussdcompanion.app

import java.util.concurrent.atomic.AtomicReference

/**
 * حالة مشتركة بين UssdAccessibilityService (تقرأ نافذة USSD وتكتب هنا)
 * وHttpServerService (تقرأ من هنا لترد على /ussd/response، وتكتب هنا لتمرير إدخال جديد).
 */
object UssdSessionState {

    const val STATUS_IDLE = "IDLE"
    const val STATUS_PENDING = "PENDING"
    const val STATUS_WAITING_USER_INPUT = "WAITING_USER_INPUT"
    const val STATUS_COMPLETED = "COMPLETED"
    // حالة وسيطة: رد الـ USSD وصل وكان مجرد إشعار بأن الرصيد سيصل عبر SMS منفصلة - الجلسة تبقى
    // نشطة (غير IDLE) بانتظار تلك الرسالة. لا تُطابق شرط C# الخاص بـ COMPLETED/WAITING_USER_INPUT
    // عمداً، فيستمر برنامج السي شارب بالاستقصاء بصبر دون إرسال أي إدخال إضافي عبثاً.
    const val STATUS_WAITING_SMS_BALANCE = "WAITING_SMS_BALANCE"

    @Volatile var currentRequestId: String? = null
    @Volatile var status: String = STATUS_IDLE
    @Volatile var message: String = ""

    val pendingInputToSend = AtomicReference<String?>(null)
    @Volatile var dismissRequested: Boolean = false

    @Synchronized
    fun startNewSession(requestId: String) {
        currentRequestId = requestId
        status = STATUS_PENDING
        message = ""
        pendingInputToSend.set(null)
        dismissRequested = false
    }

    @Synchronized
    fun reset() {
        currentRequestId = null
        status = STATUS_IDLE
        message = ""
        pendingInputToSend.set(null)
        dismissRequested = false
    }
}
