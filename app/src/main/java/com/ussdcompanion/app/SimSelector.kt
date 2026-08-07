package com.ussdcompanion.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object SimSelector {
    @SuppressLint("MissingPermission")
    fun resolvePhoneAccountForSlot(context: Context, slotIndex: Int): PhoneAccountHandle? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityLog.add("تعذّر تحديد الشريحة $slotIndex: إذن READ_PHONE_STATE غير ممنوح")
            return null
        }
        return try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeList = subscriptionManager.activeSubscriptionInfoList ?: run {
                ActivityLog.add("لا توجد شرائح نشطة في الجهاز")
                return null
            }

            val subscription = activeList.firstOrNull { it.simSlotIndex == slotIndex }
            if (subscription == null) {
                ActivityLog.add("لا توجد شريحة نشطة في الفتحة $slotIndex")
                return null
            }

            val subId = subscription.subscriptionId
            val iccId = subscription.iccId ?: ""
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccounts = telecomManager.callCapablePhoneAccounts ?: emptyList()

            // 1. التطابق المباشر أو الربط المتقدم مع الحسابات المتاحة (يغطي صيغ ID مختلفة حسب المصنّع)
            var matchedHandle: PhoneAccountHandle? = phoneAccounts.firstOrNull { handle ->
                val handleId = handle.id
                handleId == subId.toString() ||
                (iccId.isNotEmpty() && handleId.contains(iccId)) ||
                handleId.endsWith("_$subId") ||
                handleId.endsWith(":$subId")
            }

            // 2. حل احتياطي: إذا تعذّرت المطابقة النصية، نعتمد ترتيب الفتحات الفعالة
            if (matchedHandle == null && phoneAccounts.isNotEmpty()) {
                val sortedSubscriptions = activeList.sortedBy { it.simSlotIndex }
                val targetIndexInActive = sortedSubscriptions.indexOfFirst { it.simSlotIndex == slotIndex }

                if (targetIndexInActive in phoneAccounts.indices) {
                    matchedHandle = phoneAccounts[targetIndexInActive]
                    ActivityLog.add("تنبيه: تم استخدام المطابقة الترتيبية للحساب في الفتحة $slotIndex")
                }
            }

            if (matchedHandle != null) {
                val label = telecomManager.getPhoneAccount(matchedHandle)?.label ?: matchedHandle.id
                ActivityLog.add("الفتحة $slotIndex ← $label (SubId: $subId)")
            } else {
                ActivityLog.add("لم يُعثر على حساب اتصال مطابق للفتحة $slotIndex (SubId: $subId)")
            }

            matchedHandle
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء تحديد الفتحة $slotIndex: ${e.message}")
            null
        }
    }
}
