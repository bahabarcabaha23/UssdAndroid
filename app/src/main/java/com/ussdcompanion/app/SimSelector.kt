package com.ussdcompanion.app

import android.content.Context
import android.telephony.SubscriptionManager
import android.os.Build

object SimSelector {
    fun getSubscriptionId(context: Context, simSlot: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Lollipop_MR1) return null
        try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val infoList = sm?.activeSubscriptionInfoList
            if (!infoList.isNullOrEmpty()) {
                // البحث حسب الفهرس (simSlot)
                for (info in infoList) {
                    if (info.simSlotIndex == simSlot) {
                        return info.subscriptionId
                    }
                }
                // إن لم يطابق الفهرس، نأخذ الأولى كاحتياط
                return infoList[0].subscriptionId
            }
        } catch (e: Exception) {
            // تجاهل أخطاء الصلاحيات أو الفشل المؤقت
        }
        return null
    }
}
