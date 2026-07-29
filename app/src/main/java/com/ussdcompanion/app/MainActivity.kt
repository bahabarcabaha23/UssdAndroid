package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusServer: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusSms: TextView
    private lateinit var editApiKey: EditText
    private lateinit var editPort: EditText
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        statusServer = findViewById(R.id.statusServer)
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusSms = findViewById(R.id.statusSms)
        editApiKey = findViewById(R.id.editApiKey)
        editPort = findViewById(R.id.editPort)
        logView = findViewById(R.id.logView)

        editApiKey.setText(prefs.getString(Prefs.API_KEY, ""))
        editPort.setText(prefs.getInt(Prefs.PORT, 8080).toString())

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnGrantSms).setOnClickListener {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_SMS), 100)
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveSettingsAndRestartServer()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        startHttpServerServiceIfConfigured()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun saveSettingsAndRestartServer() {
        val key = editApiKey.text.toString().trim()
        val portText = editPort.text.toString().trim()
        val port = portText.toIntOrNull() ?: 8080

        if (TextUtils.isEmpty(key)) {
            Toast.makeText(this, "الرجاء إدخال مفتاح API أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putString(Prefs.API_KEY, key)
            .putInt(Prefs.PORT, port)
            .apply()

        stopService(Intent(this, HttpServerService::class.java))
        startHttpServerServiceIfConfigured()
        Toast.makeText(this, "تم الحفظ، الخادم يعمل الآن على المنفذ $port", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun startHttpServerServiceIfConfigured() {
        val key = prefs.getString(Prefs.API_KEY, "") ?: ""
        if (key.isEmpty()) return
        ContextCompat.startForegroundService(this, Intent(this, HttpServerService::class.java))
    }

    private fun refreshStatus() {
        val port = prefs.getInt(Prefs.PORT, 8080)
        statusServer.text = "حالة الخادم: يعمل محلياً على المنفذ $port (عبر adb forward فقط)"

        statusAccessibility.text = "خدمة الوصول: " +
            if (isAccessibilityServiceEnabled()) "مفعّلة ✓" else "غير مفعّلة - اضغط الزر أعلاه"

        val hasSms = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        statusSms.text = "إذن قراءة الرسائل: " + if (hasSms) "ممنوح ✓" else "غير ممنوح"

        logView.text = ActivityLog.dump()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
