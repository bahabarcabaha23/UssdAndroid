package com.ussdcompanion.app

object UssdSessionState {
    const val STATUS_IDLE = "IDLE"
    const val STATUS_PENDING = "PENDING"
    const val STATUS_WAITING_USER_INPUT = "WAITING_USER_INPUT"
    const val STATUS_WAITING_SMS_BALANCE = "WAITING_SMS_BALANCE"
    const val STATUS_COMPLETED = "COMPLETED"

    @Volatile
    var status: String = STATUS_IDLE
        private set

    @Volatile
    var message: String = ""
        private set

    @Volatile
    var currentRequestId: String = ""
        private set

    @Synchronized
    fun updateStatus(newStatus: String, newMessage: String = message) {
        status = newStatus
        message = newMessage
    }

    @Synchronized
    fun startNewSession(requestId: String) {
        currentRequestId = requestId
        status = STATUS_PENDING
        message = ""
    }

    @Synchronized
    fun reset() {
        status = STATUS_IDLE
        message = ""
        currentRequestId = ""
    }
}
