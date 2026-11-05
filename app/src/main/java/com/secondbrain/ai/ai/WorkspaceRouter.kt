package com.secondbrain.ai.ai

/** Conservative, per-task workspace routing. Never default a personal or ambiguous task to View. */
object WorkspaceRouter {
    data class Route(val workspaceId: String?, val needsReview: Boolean, val reason: String)
    private val home = Regex("(?:البيت|بيتي|المنزل|شقتنا|الأسرة|العائلة|شخصي|شخصية|مراتي|زوجتي|أولادي|ولادي|عيالي|ابني|ابنتي|المدرسة|مصروف البيت|الكهرباء في البيت|حاجات البيت|طلبات البيت|السوبرماركت|البقالة|والدتي|ماما|بابا)", RegexOption.IGNORE_CASE)
    private val view = Regex("(?:فيو|view\\s*tower|rwdi|برج النخيل|برج المربع|وَافي|وافي|عمارة الورود|فيو أرش|فيو 14|فيو 15)", RegexOption.IGNORE_CASE)
    private val tech = Regex("(?:quantex|كوانتكس|github|جيت.?هب|الريبو|مستودع الكود|كود التطبيق|تطوير المنصة|مشروع برمجي)", RegexOption.IGNORE_CASE)
    private val consult = Regex("(?:مكتبي الهندسي|المكتب الاستشاري الخاص|عميل المكتب|الاستشارات الخاصة|مكتب الاستشارات)", RegexOption.IGNORE_CASE)
    private val known = setOf("view", "tech", "consult", "personal")

    fun route(description: String, aiSuggestion: String?, available: Set<String> = known): Route {
        val text = description.trim()
        val clues = buildList {
            if (home.containsMatchIn(text)) add("personal")
            if (view.containsMatchIn(text)) add("view")
            if (tech.containsMatchIn(text)) add("tech")
            if (consult.containsMatchIn(text)) add("consult")
        }.filter { it in available }.distinct()
        if (clues.size > 1) return Route(null, true, "إشارات لمساحات متعددة؛ اختر مساحة هذه المهمة")
        if (clues.size == 1) return Route(clues.first(), false, "تم التعرف على المساحة من كلمات المهمة")
        val suggestion = aiSuggestion?.takeIf { it in available }
        return Route(suggestion, true,
            if (suggestion != null) "اقتراح الذكاء الاصطناعي يحتاج تأكيدك؛ اضغط على المساحة المقصودة"
            else "لم تتضح مساحة العمل؛ اخترها قبل الحفظ")
    }
}
