package com.ussdcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * يحدد PhoneAccountHandle المطابق لفتحة شريحة معينة (0 أو 1)، لتمريره إلى
 * Intent.ACTION_CALL عبر TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE فيتم تحديد
 * الشريحة برمجياً مباشرة دون ظهور نافذة اختيار الشريحة إطلاقاً.
 *
 * كل استدعاء يسجّل النتيجة في ActivityLog (اسم الشريحة المطابقة للفتحة، أو سبب
 * الفشل) حتى يمكن التأكد بصرياً من صحة مطابقة الفتحة 0/1 للشريحة الفعلية
 * (موبيليس/أوريدو/جيزي...) قبل الاعتماد عليها في التشغيل الفعلي.
 */
object SimSelector {
    @SuppressLint("MissingPermission")
    fun resolvePhoneAccountForSlot(context: Context, slotIndex: Int): PhoneAccountHandle? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityLog.add("تعذّر تحديد الشريحة $slotIndex: إذن READ_PHONE_STATE غير ممنوح")
            return null
        }
        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscription = subscriptionManager.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slotIndex }
            if (subscription == null) {
                ActivityLog.add("لا توجد شريحة نشطة في الفتحة $slotIndex")
                return null
            }

            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val handle = telecomManager.callCapablePhoneAccounts?.firstOrNull { it.id == subscription.subscriptionId.toString() }

            if (handle != null) {
                val label = telecomManager.getPhoneAccount(handle)?.label ?: handle.id
                ActivityLog.add("الفتحة $slotIndex ← $label")
            } else {
                ActivityLog.add("لم يُعثر على حساب اتصال مطابق للفتحة $slotIndex (subscriptionId=${subscription.subscriptionId})")
            }
            handle
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء تحديد الفتحة $slotIndex: ${e.message}")
            null
        }
    }
}
