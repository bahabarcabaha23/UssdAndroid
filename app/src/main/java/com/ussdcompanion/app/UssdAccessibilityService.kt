package com.ussdcompanion.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * تراقب ظهور نافذة رد الـ USSD النظامية (تظهرها شاشة الاتصال عند طلب رمز مثل *123#)
 * وتقرأ نصها، وتكتشف إن كانت تنتظر إدخال المستخدم (قائمة متعددة الخطوات) أو أنها انتهت.
 *
 * لم يُحدَّد packageNames في accessibility_service_config.xml عمداً: تختلف حزمة تطبيق
 * الاتصال بين الشركات المصنّعة (خصوصاً هواوي/EMUI)، فبدل الاعتماد على اسم حزمة قد لا يكون
 * صحيحاً على جهازك، تعتمد الخدمة على محتوى النافذة نفسه (نص + عناصر تطابق نمط حوار USSD)
 * وعلى وجود جلسة نشطة بدأناها نحن أصلاً (UssdSessionState.status != IDLE) لتفادي التقاط
 * نوافذ أخرى غير متعلقة. إن لم تُكتشف النافذة بشكل صحيح على جهازك، سجل الأحداث في التطبيق
 * (الشاشة الرئيسية) يساعد في التشخيص والتعديل.
 *
 * التعرّف على زر الإغلاق/الموافقة (findDismissButton) يتم بثلاث محاولات متدرجة:
 * 1) عبر معرف الزر القياسي في حوارات أندرويد (android:id/button1|2|3) - لا يعتمد على
 *    اللغة أو تخصيص الشركة المصنّعة إطلاقاً. يتطلب flagReportViewIds في
 *    accessibility_service_config.xml، وهو مُفعّل بالفعل في هذا المشروع.
 * 2) عبر نص معروف (DISMISS_BUTTON_TEXTS) - يغطي "OK" التي تظهر بدل "موافق" على هواوي/EMUI.
 * 3) كخيار أخير: أي زر وحيد ظاهر في الحوار.
 * فور العثور على الزر عند اكتمال الرد (لا يوجد حقل إدخال)، يُنقر عليه تلقائياً لإغلاق
 * الحوار مباشرة - بعد حفظ الحالة والنص أولاً حتى يتوفّرا فوراً عبر GET /ussd/response.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: UssdAccessibilityService? = null
        override fun onCreate() {
    super.onCreate()
    instance = this
}

override fun onDestroy() {
    instance = null
    super.onDestroy()
}
fun performPendingActionsDirectly() {
    val root = rootInActiveWindow
    if (root == null) {
        ActivityLog.add("[إدخال] فشل: لا توجد نافذة نشطة (rootInActiveWindow == null)")
        return
    }
    applyPendingActions(root)
}
        private val DISMISS_BUTTON_TEXTS = listOf(
            "OK", "Ok", "ok", "موافق", "Cancel", "CANCEL", "إلغاء", "Dismiss", "Close", "إغلاق",
            // فرنسية (تظهر على واجهات هواوي/EMUI بدل الإنجليزية أو العربية، مثال: Djezzy *766#)
            "ANNULER", "Annuler", "annuler", "Fermer", "fermer"
        )
        private val SEND_BUTTON_TEXTS = listOf(
            "Send", "SEND", "إرسال", "موافق", "OK",
            "ENVOYER", "Envoyer", "envoyer"
        )
        private val STANDARD_DIALOG_BUTTON_IDS = listOf(
            "android:id/button1", "android:id/button2", "android:id/button3"
        )

        // مؤشر على أن النص يحمل رداً حقيقياً على استعلام رصيد (رقم مقترن بعملة، أو كلمة SOLDE/رصيد)
        // يُستخدم لعدم اعتبار حوار "تفاعلي الشكل" (فيه EditText وخيار "1:Plus de detail" كما عند
        // Djezzy) بانتظار إدخال، بينما هو في الواقع رد نهائي وصل بنجاح.
        private val BALANCE_PATTERN = Regex(
            "(SOLDE|رصيد|Solde|Crédit|Credit)|(\\d[\\d.,]*\\s*(DA|دج))",
            RegexOption.IGNORE_CASE
        )

        // إصلاح: الشق الثاني من BALANCE_PATTERN (مبلغ + عملة، بلا كلمة SOLDE/Crédit) كان
        // يطابق أيضاً نصوص قوائم تأكيد واختيار حقيقية تذكر مبلغاً ضمن سؤالها، مثل
        // "VOUS VOULEZ TRANSFERER 100 DA... 1:POUR CONFIRMER 2:POUR ANNULER" أو قائمة
        // اختيار العروض "1: ...2000 Da  2: ...1500 Da...". كانت هذه تُصنَّف خطأً كرد نهائي
        // ويُغلق حوارها تلقائياً (أحياناً بالنقر على زر ANNULER) قبل إرسال رقم التأكيد، فيصل
        // رقم التأكيد لاحقاً كطلب USSD جديد مستقل وهو ليس شيفرة صالحة.
        // الفارق الحقيقي بين رد الرصيد وهذه القوائم ليس ذكر المبلغ، بل عدد الخيارات المرقّمة:
        // رد الرصيد يحمل خياراً اختيارياً واحداً لا نحتاج متابعته ("1:Plus de detail Bonus")،
        // بينما قوائم التأكيد/الاختيار تحمل خيارين مرقّمين أو أكثر دائماً (1:...2:... أو أكثر).
        // لذا: أي نص فيه خياران مرقّمان أو أكثر يُعامل كقائمة اختيار حقيقية بانتظار إدخال،
        // بصرف النظر عمّا إذا كان BALANCE_PATTERN قد طابقه أيضاً بسبب ذكر مبلغ فيه.
        private val MULTI_OPTION_MENU_PATTERN = Regex("""[1-9]\d?\s*:""")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        // تجاهل أي حدث صادر من تطبيقنا نفسه (مثل شاشة الإعدادات الرئيسية) - شاشتنا تحتوي
        // حقول EditText أيضاً (مفتاح API والمنفذ)، فبدون هذا الفحص قد تُقرأ بالخطأ كحوار
        // USSD بانتظار إدخال إن كانت ظاهرة لحظة إرسال أمر USSD
        if (event.packageName == packageName) return

        // لا داعي للمعالجة إن لم تكن هناك جلسة USSD نشطة من جهتنا
        if (UssdSessionState.status == UssdSessionState.STATUS_IDLE) return

        val root = rootInActiveWindow ?: return

        // فحص event.packageName أعلاه لا يكفي وحده: هذا الفحص على حزمة الحدث المُطلِق فقط،
        // بينما rootInActiveWindow يُقرأ لحظة المعالجة وقد يعود عندها لتطبيقنا نفسه (مثلاً أثناء
        // انتظار applyPendingActions لتنفيذ طلب إغلاق معلّق تأخر بضع ثوانٍ). لذلك نتحقق أيضاً
        // من حزمة الجذر الفعلي الذي سنقرأ محتواه قبل معاملته كنافذة USSD محتملة.
        if (root.packageName == packageName) return

        try {
            handlePossibleUssdDialog(root)
            applyPendingActions(root)
        } catch (e: Exception) {
            ActivityLog.add("خطأ أثناء قراءة نافذة USSD: ${e.message}")
        }
    }

    private fun handlePossibleUssdDialog(root: AccessibilityNodeInfo) {
        if (UssdSessionState.status == UssdSessionState.STATUS_COMPLETED) return

        val allText = StringBuilder()
        collectText(root, allText)
        val text = allText.toString().trim()
        if (text.isEmpty() || text.length > 2000) return

        val hasInputField = findEditText(root) != null
        val looksLikeBalanceReply = BALANCE_PATTERN.containsMatchIn(text)
        val looksLikeMultiChoiceMenu = MULTI_OPTION_MENU_PATTERN.findAll(text).count() >= 2

        // Djezzy تُرجع رصيدها الآن ضمن قائمة تفاعلية الشكل (فيها EditText وخيار
        // "1:Plus de detail")، رغم أنها رد نهائي فعلياً. لا نعتبرها "بانتظار إدخال" إن كانت
        // تحمل رصيداً واضحاً بالفعل، وإلا بقيت الجلسة عالقة رغم وصول الرصيد الصحيح من اللحظة الأولى.
        // استثناء: إن كانت قائمة اختيار حقيقية بخيارين مرقّمين أو أكثر (looksLikeMultiChoiceMenu)
        // فهي بالتأكيد بانتظار إدخال (تأكيد تحويل، اختيار عرض...)، حتى لو ذكرت مبلغاً بالدينار.
        if (hasInputField && (looksLikeMultiChoiceMenu || !looksLikeBalanceReply)) {
            if (UssdSessionState.status != UssdSessionState.STATUS_WAITING_USER_INPUT || UssdSessionState.message != text) {
                UssdSessionState.status = UssdSessionState.STATUS_WAITING_USER_INPUT
                UssdSessionState.message = text
                ActivityLog.add("رد USSD (بانتظار إدخال): $text")
            }
            return
        }

        val dismissButton = findDismissButton(root)
        if (dismissButton != null || looksLikeBalanceReply) {
            // نحفظ الحالة والنص أولاً كي يصبحا متاحين فوراً عبر GET /ussd/response قبل أي نقر
            UssdSessionState.status = UssdSessionState.STATUS_COMPLETED
            UssdSessionState.message = text
            ActivityLog.add("رد USSD (نهائي): $text")

            if (dismissButton != null) {
                // إغلاق تلقائي وفوري بعد الاستخراج مباشرة، بدل انتظار /ussd/dismiss من الكمبيوتر
                val clicked = dismissButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActivityLog.add(if (clicked) "تم إغلاق حوار USSD تلقائياً" else "تعذّر النقر التلقائي على زر الإغلاق")
            } else {
                // لم يُعثر على زر إغلاق في هذه اللحظة (الأزرار قد تكون لا تزال تُرسم)؛
                // سيتم إغلاقه لاحقاً عبر applyPendingActions عند وصول /ussd/dismiss من الكمبيوتر
                ActivityLog.add("تم استخراج الرصيد؛ بانتظار توفر زر إغلاق مناسب")
            }
        }
    }

    private fun applyPendingActions(root: AccessibilityNodeInfo) {
    val toSend = UssdSessionState.pendingInputToSend.getAndSet(null)
    if (toSend != null) {
        val field = findEditText(root)
        val sendBtn = findButtonByText(root, SEND_BUTTON_TEXTS)
        
        if (field != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, toSend)
            }
            
            // تسجيل نتيجة كتابة النص
            val setTextSuccess = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            ActivityLog.add("[إدخال] نتيجة كتابة النص ('$toSend'): $setTextSuccess")

            if (setTextSuccess && sendBtn != null) {
                // تسجيل نتيجة الضغط على زر الإرسال
                val clickSuccess = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ActivityLog.add("[إدخال] نتيجة الضغط على زر الإرسال: $clickSuccess")

                if (clickSuccess) {
                    UssdSessionState.status = UssdSessionState.STATUS_PENDING
                }
            } else if (sendBtn == null) {
                ActivityLog.add("[إدخال] فشل: لم يُعثر على زر الإرسال (ENVOYER)")
            }
        } else {
            ActivityLog.add("[إدخال] فشل: لم يُعثر على حقل الإدخال (EditText)")
        }
    }

    // طلب الإغلاق إن وجد
    if (UssdSessionState.dismissRequested) {
        val closeBtn = findDismissButton(root)
        val clicked = closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        ActivityLog.add(if (clicked) "تم إغلاق حوار USSD بطلب من البرنامج" else "طلب إغلاق لكن لم يُعثر على زر مناسب")
        UssdSessionState.reset()
    }
}

        // طلب إغلاق صريح من الكمبيوتر (POST /ussd/dismiss) - غالباً لإلغاء جلسة عالقة بانتظار إدخال
        if (UssdSessionState.dismissRequested) {
            val closeBtn = findDismissButton(root)
            val clicked = closeBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            ActivityLog.add(if (clicked) "تم إغلاق حوار USSD بطلب من البرنامج" else "طلب إغلاق لكن لم يُعثر على زر مناسب")
            UssdSessionState.reset()
        }
    }

    /**
     * يبحث عن زر الإغلاق/الموافقة بثلاث محاولات متدرجة:
     * 1) عبر معرف الزر القياسي لحوارات أندرويد (button1/2/3) - لا يعتمد على اللغة أو نص الزر
     *    إطلاقاً، وهو الخيار الأكثر أماناً لأنه لا يتأثر بترجمة الواجهة أو تخصيصات EMUI.
     * 2) عبر نص معروف من DISMISS_BUTTON_TEXTS (يغطي "OK" على هواوي/EMUI).
     * 3) كخيار أخير: إن كان هناك زر واحد فقط ظاهر في الحوار، فهو على الأغلب زر الإغلاق الوحيد.
     */
    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in STANDARD_DIALOG_BUTTON_IDS) {
            val found = try {
                root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull { it.isEnabled }
            } catch (e: Exception) {
                null
            }
            if (found != null) return found
        }

        findButtonByText(root, DISMISS_BUTTON_TEXTS)?.let { return it }

        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectButtons(root, buttons)
        if (buttons.size == 1) return buttons[0]

        return null
    }

    private fun collectButtons(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.className == "android.widget.Button") out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectButtons(child, out)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        node.text?.let { if (it.isNotBlank()) out.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
        }
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, options: List<String>): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString()
        if (nodeText != null && options.any { nodeText.equals(it, ignoreCase = true) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findButtonByText(child, options)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {
        ActivityLog.add("تم إيقاف خدمة الوصول")
    }
}
