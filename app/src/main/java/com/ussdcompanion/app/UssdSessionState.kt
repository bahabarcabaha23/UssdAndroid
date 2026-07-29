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
