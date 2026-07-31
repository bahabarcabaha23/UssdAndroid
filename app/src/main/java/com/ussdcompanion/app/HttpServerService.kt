package com.ussdcompanion.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class HttpServerService : Service() {

    private var server: LocalServer? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val port = prefs.getInt(Prefs.PORT, 8080)
        val apiKey = prefs.getString(Prefs.API_KEY, "") ?: ""

        server = LocalServer(applicationContext, port, apiKey)
        try {
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            ActivityLog.add("الخادم المحلي يعمل على 127.0.0.1:$port")
        } catch (e: Exception) {
            ActivityLog.add("تعذّر بدء الخادم على المنفذ $port: ${e.message}")
        }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val channelId = "ussd_companion_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "USSD Companion", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("USSD Companion يعمل")
            .setContentText("جاهز لاستقبال أوامر عبر ADB")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    /** يرتبط بـ 127.0.0.1 فقط (لا يُستقبل إلا عبر adb forward)، ويتحقق من X-API-Key في كل طلب. */
    private class LocalServer(
        private val context: Context,
        port: Int,
        private val apiKey: String
    ) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            if (apiKey.isNotEmpty() && session.headers["x-api-key"] != apiKey) {
                return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "invalid api key"))
            }

            return try {
                val uri = session.uri
                when {
                    uri == "/status" && session.method == Method.GET -> handleStatus()
                    uri == "/health" && session.method == Method.GET -> handleHealth()
                    uri == "/ussd/send" && session.method == Method.POST -> handleUssdSend(session)
                    uri.startsWith("/ussd/response/") && session.method == Method.GET -> handleUssdResponse(uri)
                    uri.startsWith("/ussd/dismiss/") && session.method == Method.POST -> handleUssdDismiss()
                    uri == "/sms/list" && session.method == Method.GET -> handleSmsList(session)
                    uri == "/sms/delete" && session.method == Method.DELETE -> handleSmsDelete()
                    else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
                }
            } catch (e: Exception) {
                jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", e.message ?: "unknown"))
            }
        }

        private fun handleStatus(): Response {
            val json = JSONObject()
                .put("busy", UssdSessionState.status != UssdSessionState.STATUS_IDLE)
                .put("signal", 0)
                .put("sim", true)
                .put("battery", getBatteryLevel())
            return jsonResponse(Response.Status.OK, json)
        }

        private fun handleHealth(): Response {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val accessibilityOn = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
            val smsOn = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

            val json = JSONObject()
                .put("accessibility", accessibilityOn)
                .put("readSms", smsOn)
                .put("notificationListener", true)
                .put("network", true)
                .put("simReady", true)
            return jsonResponse(Response.Status.OK, json)
        }

        private fun handleUssdSend(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = JSONObject(files["postData"] ?: "{}")
            val code = body.optString("code", "")
            val existingSessionId: String? = if (body.isNull("sessionId")) null else body.optString("sessionId")
            val simSlot = body.optInt("simSlot", -1)

            if (code.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "code is required"))
            }

            if (existingSessionId != null &&
                existingSessionId == UssdSessionState.currentRequestId &&
                UssdSessionState.status == UssdSessionState.STATUS_WAITING_USER_INPUT) {
                // متابعة جلسة قائمة بالفعل: نرسل النص كإدخال بدل بدء مكالمة USSD جديدة
                UssdSessionState.pendingInputToSend.set(code)
                return jsonResponse(Response.Status.OK, JSONObject().put("requestId", existingSessionId))
            }

            if (UssdSessionState.status != UssdSessionState.STATUS_IDLE) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "device busy"))
            }

            val requestId = UUID.randomUUID().toString()
            UssdSessionState.startNewSession(requestId)

            val dialIntent = Intent(Intent.ACTION_CALL)
            dialIntent.data = Uri.parse("tel:" + Uri.encode(code))
            dialIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            // إن أُرسل simSlot مع الطلب، نحدد الشريحة برمجياً فلا تظهر نافذة الاختيار إطلاقاً
            if (simSlot != -1) {
                val handle = SimSelector.resolvePhoneAccountForSlot(context, simSlot)
                if (handle != null) {
                    dialIntent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
            }

            context.startActivity(dialIntent)
            ActivityLog.add("تم طلب USSD: $code" + if (simSlot != -1) " (SIM $simSlot)" else "")

            return jsonResponse(Response.Status.OK, JSONObject().put("requestId", requestId))
        }

        private fun handleUssdResponse(uri: String): Response {
            val requestId = uri.substringAfterLast("/")
            if (requestId != UssdSessionState.currentRequestId) {
                return jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "unknown requestId"))
            }
            val json = JSONObject()
                .put("status", UssdSessionState.status)
                .put("message", UssdSessionState.message)
            if (UssdSessionState.status == UssdSessionState.STATUS_COMPLETED) {
                UssdSessionState.reset()
            }
            return jsonResponse(Response.Status.OK, json)
        }

        private fun handleUssdDismiss(): Response {
            // ملاحظة إصلاح: كان هذا الموضع يستدعي UssdSessionState.reset() فوراً بعد رفع
            // العلم، فيصفّر status إلى IDLE قبل أن تصل خدمة الوصول لحدثها التالي - ما يجعل
            // onAccessibilityEvent يتجاهل الجلسة فوراً (status == IDLE) ولا تُنقر زر
            // الإغلاق فعلياً على الشاشة أبداً. الآن: نكتفي برفع العلم هنا، وخدمة الوصول
            // (applyPendingActions) هي من تستدعي reset() بعد محاولة النقر الفعلية.
            UssdSessionState.dismissRequested = true
            return jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
        }

        private fun handleSmsList(session: IHTTPSession): Response {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", "READ_SMS permission not granted"))
            }
            val limit = session.parms["limit"]?.toIntOrNull() ?: 10
            val list = JSONArray()
            val uri = Uri.parse("content://sms/inbox")
            val cursor: Cursor? = context.contentResolver.query(
                uri, arrayOf("_id", "address", "body", "date"), null, null, "date DESC LIMIT $limit"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val item = JSONObject()
                        .put("id", it.getInt(0))
                        .put("address", it.getString(1) ?: "")
                        .put("body", it.getString(2) ?: "")
                        .put("date", it.getLong(3).toString())
                    list.put(item)
                }
            }
            return jsonResponse(Response.Status.OK, list)
        }

        private fun handleSmsDelete(): Response {
            // حذف الرسائل يتطلب أن يكون التطبيق هو تطبيق الرسائل الافتراضي على أندرويد.
            // غير مُفعّل عمداً في هذا الإصدار الخفيف - راجع README.
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", "sms delete not enabled in this build - see README")
            )
        }

        private fun getBatteryLevel(): Int {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (e: Exception) {
                -1
            }
        }

        private fun jsonResponse(status: Response.Status, json: Any): Response {
            return newFixedLengthResponse(status, "application/json", json.toString())
        }
    }
}
