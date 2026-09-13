package com.ussdcompanion.app

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class HttpServerService(port: Int = 8080) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val method = session.method

        try {
            // مسار إرسال USSD
            if (uri == "/ussd/send" && method == Method.POST) {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: "{}"
                val json = JSONObject(postData)
                val code = json.optString("code")
                val simSlot = json.optInt("simSlot", 0)

                val requestId = "req_" + System.currentTimeMillis()
                UssdSessionState.startNewSession(requestId)

                // تنفيذ الرد أو الإرسال عبر خدمة الوصول أو Accessibility
                // (يتم استدعاء خدمة الاتصال هنا)

                val respJson = JSONObject().apply {
                    put("success", true)
                    put("requestId", requestId)
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", respJson.toString())
            }

            // مسار استعلام الردود
            if (uri.startsWith("/ussd/response/") && method == Method.GET) {
                val respJson = JSONObject().apply {
                    put("status", UssdSessionState.status)
                    put("message", UssdSessionState.message)
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", respJson.toString())
            }

            // مسار إغلاق أو تصفير الجلسة
            if (uri.startsWith("/ussd/dismiss/") && method == Method.POST) {
                UssdSessionState.reset()
                val respJson = JSONObject().put("success", true)
                return newFixedLengthResponse(Response.Status.OK, "application/json", respJson.toString())
            }

        } catch (e: Exception) {
            val errJson = JSONObject().put("error", e.message)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", errJson.toString())
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{\"error\":\"Not Found\"}")
    }
}
