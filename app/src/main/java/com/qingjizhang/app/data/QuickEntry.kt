package com.qingjizhang.app.data

import java.time.LocalDate
import java.time.ZoneId

/**
 * 一句话记账（v2.8.0 Beta 起，v2.9.0 打磨）：把「午饭 25 支付宝 昨天」拆成一笔账。
 *
 * 原则（与交互原型一致）：**不猜**——没识别到分类 / 账户就返回空，绝不默认成"现金"；
 * **金额优先**——有金额就能用，分类账户可以留空让用户在面板里补。
 * 纯本地字符串规则，不联网、不加权限、不需要模型。
 *
 * v2.9.0 打磨（T1）：
 * - 同义词表扩容：餐饮 / 交通 / 购物 / 娱乐 / 医疗 / 学习 / 人情 / 居家 / 通讯补齐常见说法；
 * - 明确**不建**同义词的理财互转词（理财 / 基金 / 股票 / 定投 / 余额宝 / 买入）：
 *   互转不是支出，解析器只产 EXPENSE / INCOME，输入这些词时只识别金额、分类留空；
 * - 历史搭配兜底：分类名与同义词都没命中时，按「备注词 → 分类 / 账户」的历史频率补齐
 *   （近 180 天、同类型、未删除；同一备注词出现 ≥2 次且都指向同一目标才采用），
 *   兜底命中的在 matched 里加一条「上次这么记」供 UI 黄条展示；
 * - 金额识别支持 `¥` / `￥` 前缀（识别后从备注中剔除）。
 */
object QuickEntry {

    data class Draft(
        val type: TxnType,
        val amountCents: Long,
        val parentId: Long,
        val childId: Long,
        val categoryName: String,
        val accountId: Long,
        val accountName: String,
        val note: String,
        val date: LocalDate,
        val matched: List<String>
    )

    private val INCOME_HINTS = listOf(
        "工资", "薪资", "薪", "奖金", "年终奖", "分红", "提成", "退款", "报销", "利息", "收到", "进账", "收入"
    )

    /**
     * 理财互转类词（v2.9.0）：这些是「互转」不是支出，而解析器只产 EXPENSE / INCOME，
     * 所以**不建同义词**；输入里出现这些词时只识别金额，分类留空，
     * 避免把「买基金」误导成一笔支出。账户 / 日期识别不受影响（它们不会误导）。
     */
    private val TRANSFER_WORDS = listOf("理财", "基金", "股票", "定投", "余额宝", "买入")

    /** 历史搭配兜底的最小支持次数：同一备注词至少出现这么多次才敢采用（与 SuggestionEngine.MIN_SUPPORT 一致） */
    private const val MIN_HISTORY_SUPPORT = 2

    /** 历史搭配兜底的时间窗：近 180 天（含今天；起点 = 今天减 180 天的 0 点） */
    private const val HISTORY_WINDOW_DAYS = 180L

    /** 常见说法 → 预设类目名（用户账本里没有这个类目名就自动失效，不会误匹配） */
    private val SYNONYMS: List<Pair<String, List<String>>> = listOf(
        "餐饮" to listOf("吃饭", "午饭", "午餐", "早饭", "早餐", "晚饭", "晚餐", "夜宵", "宵夜", "咖啡", "奶茶", "外卖", "食堂", "餐厅", "聚餐", "零食", "水果", "火锅", "烧烤", "面包", "吃", "早茶", "盒饭", "面", "粉", "汉堡", "炸鸡", "甜品", "饮料"),
        "交通" to listOf("打车", "地铁", "公交", "滴滴", "加油", "停车", "高铁", "火车", "车费", "机票", "出租车", "单车", "网约车", "过路费", "充电", "罚款", "共享单车"),
        "购物" to listOf("淘宝", "京东", "拼多多", "超市", "衣服", "鞋", "日用品", "纸巾", "网购", "买", "数码", "美妆", "宠物", "母婴", "家具", "家电"),
        "居家" to listOf("房租", "水费", "电费", "燃气", "物业", "宽带", "家政", "车位", "保洁", "维修"),
        "娱乐" to listOf("电影", "ktv", "游戏", "健身", "演出", "门票", "桌游", "唱歌", "旅游", "酒店", "民宿", "手游", "视频会员", "音乐会员", "演唱会"),
        "医疗" to listOf("药", "医院", "挂号", "体检", "看病", "牙", "口腔", "疫苗", "保健品", "眼镜"),
        "学习" to listOf("书", "课程", "学费", "培训", "考试", "文具", "电子书", "考研", "考证", "家教", "网课"),
        "通讯" to listOf("话费", "流量", "电话费", "套餐", "宽带费"),
        "人情" to listOf("红包", "礼金", "随礼", "请客", "份子", "生日", "婚礼", "丧礼")
    )

    private val ACCOUNT_ALIASES = listOf(
        "支付宝", "微信", "现金", "信用卡", "工商", "建设", "农业", "中国银行", "招商", "花呗", "余额宝"
    )

    /**
     * 解析失败返回 null（连金额都没有，此时不接管，交给用户手填）。
     *
     * @param history 近期账单（调用方传入，本函数保持纯函数不碰 DB）：分类名与同义词都没命中时，
     *   用它做「备注词 → 分类 / 账户」的历史搭配兜底；默认空列表 = 不兜底（与 v2.8.0 行为一致）。
     */
    fun parse(
        text: String,
        categories: List<Category>,
        accounts: List<Account>,
        today: LocalDate = LocalDate.now(),
        history: List<Txn> = emptyList()
    ): Draft? {
        val input = normalize(text)
        if (input.isEmpty()) return null

        val amount = parseAmountCents(input) ?: return null
        val isIncome = INCOME_HINTS.any { input.contains(it) }
        val type = if (isIncome) TxnType.INCOME else TxnType.EXPENSE
        val want = if (isIncome) CategoryType.INCOME else CategoryType.EXPENSE
        val pool = categories.filter { it.type == want && !it.hidden }

        // 理财互转词：互转不是支出，整条分类链路（分类名 / 同义词 / 历史兜底）都不走，分类留空
        val isTransferWord = TRANSFER_WORDS.any { input.contains(it) }

        // 分类：先用分类名本身（长的优先，避免「饭」吃掉「午饭」），再用同义词，最后历史搭配兜底
        var hitCat: Category? = null
        var hitWord = ""
        // 兜底是否命中（只用于 matched 里加「上次这么记」；兜底词来自用户输入本身，保留在备注里不裁剪）
        var historyHit = false
        if (!isTransferWord) {
            for (c in pool.sortedByDescending { it.name.length }) {
                if (c.name.length >= 2 && input.contains(c.name)) {
                    hitCat = c; hitWord = c.name; break
                }
            }
            if (hitCat == null) {
                // 同义词：跨组取最长的命中词，避免短词抢先（如居家的「宽带」吃掉通讯的「宽带费」）；
                // 同长度时排前面的组优先。用户账本没有该类目名时该组自动失效
                var bestWord = ""
                var bestCat: Category? = null
                for ((canonical, words) in SYNONYMS) {
                    val cat = pool.firstOrNull { it.name == canonical } ?: continue
                    for (w in words) {
                        if (w.length > bestWord.length && input.contains(w)) {
                            bestWord = w; bestCat = cat
                        }
                    }
                }
                if (bestCat != null) { hitCat = bestCat; hitWord = bestWord }
            }
            if (hitCat == null && history.isNotEmpty()) {
                // 历史搭配兜底：备注词 → 分类，出现 ≥2 次且都指向同一分类才采用
                val stats = collectWordStats(history, type, today) { it.categoryId }
                val adopted = adoptFromHistory(stats, input) { id -> pool.firstOrNull { it.id == id } }
                if (adopted != null) { hitCat = adopted.first; historyHit = true }
            }
        }

        // 账户
        var hitAcc: Account? = null
        var accWord = ""
        for (a in accounts) {
            if (a.name.length >= 2 && input.contains(a.name)) {
                hitAcc = a; accWord = a.name; break
            }
            val alias = ACCOUNT_ALIASES.firstOrNull { input.contains(it) && a.name.contains(it) }
            if (alias != null) { hitAcc = a; accWord = alias; break }
        }
        if (hitAcc == null && history.isNotEmpty()) {
            // 历史搭配兜底：备注词 → 账户，同样 ≥2 次且都指向同一账户才采用
            val stats = collectWordStats(history, type, today) { it.accountId }
            val adopted = adoptFromHistory(stats, input) { id ->
                accounts.firstOrNull { it.id == id && it.deletedAt == 0L }
            }
            if (adopted != null) { hitAcc = adopted.first; historyHit = true }
        }

        val (date, timeWord) = parseDate(input, today)

        // 备注：剪掉已用掉的词（兜底词来自用户输入本身，保留在备注里）
        var rest = input
        listOf(amount.second, timeWord, hitWord, accWord).filter { it.isNotBlank() }
            .forEach { rest = rest.replace(it, " ") }
        INCOME_HINTS.filter { rest.contains(it) }.forEach { rest = rest.replace(it, " ") }
        val note = rest.replace(Regex("[，,、。；;]+"), " ").replace(Regex("\\s+"), " ").trim()

        val parentId: Long
        val childId: Long
        when {
            hitCat == null -> { parentId = 0L; childId = 0L }
            hitCat.parentId == 0L -> { parentId = hitCat.id; childId = 0L }
            else -> { parentId = hitCat.parentId; childId = hitCat.id }
        }

        val matched = buildList {
            add(amount.second)
            if (hitWord.isNotBlank()) add(hitWord)
            if (accWord.isNotBlank()) add(accWord)
            if (timeWord.isNotBlank()) add(timeWord)
            if (historyHit) add("上次这么记")   // 兜底来源说明，UI 侧黄条待确认
        }

        return Draft(
            type = type,
            amountCents = amount.first,
            parentId = parentId,
            childId = childId,
            categoryName = hitCat?.name.orEmpty(),
            accountId = hitAcc?.id ?: 0L,
            accountName = hitAcc?.name.orEmpty(),
            note = note,
            date = date,
            matched = matched
        )
    }

    /** 备注切词：按标点 / 空白（含全角空格）切，只保留 ≥2 字且不全是数字的词 */
    private fun splitNoteWords(note: String): List<String> =
        note.split(Regex("[\\p{P}\\p{Z}]+"))
            .filter { it.length >= 2 && it.any { ch -> !ch.isDigit() } }

    /**
     * 统计「备注词 → 目标 id」的历史频率。
     * 只收近 [HISTORY_WINDOW_DAYS] 天、同类型（收入 / 支出）、未删除、目标 id 非 0 的账单。
     * @param pick 取这笔账的目标（分类 id 或账户 id）。
     * @return 词 → (出现次数, 目标 id)；同一个词指向过多个不同 id 时记 -1（不可用）。
     */
    private fun collectWordStats(
        history: List<Txn>,
        type: TxnType,
        today: LocalDate,
        pick: (Txn) -> Long
    ): Map<String, Pair<Int, Long>> {
        val cutoff = today.minusDays(HISTORY_WINDOW_DAYS)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = LinkedHashMap<String, Pair<Int, Long>>()
        for (t in history) {
            if (t.deletedAt != 0L || t.type != type || t.dateMillis < cutoff) continue
            val id = pick(t)
            if (id == 0L) continue
            for (w in splitNoteWords(t.note)) {
                val cur = stats[w]
                stats[w] = when {
                    cur == null -> 1 to id
                    cur.second == id -> (cur.first + 1) to id
                    else -> (cur.first + 1) to -1L   // 指向过不同目标，不敢用
                }
            }
        }
        return stats
    }

    /**
     * 历史兜底选目标：输入包含该词、出现 ≥[MIN_HISTORY_SUPPORT] 次、历次都指向同一目标（id > 0），
     * 且目标在当前账本里还能解析到（resolve 非空；分类已隐藏 / 账户已删则跳过）。
     * 多个候选时按「频率优先 → 词长 → 字典序」取第一个，保证结果确定。
     */
    private fun <T> adoptFromHistory(
        stats: Map<String, Pair<Int, Long>>,
        input: String,
        resolve: (Long) -> T?
    ): Pair<T, String>? {
        val candidates = stats.entries
            .filter { (word, stat) ->
                input.contains(word) && stat.first >= MIN_HISTORY_SUPPORT && stat.second > 0L
            }
            .sortedWith(
                compareByDescending<Map.Entry<String, Pair<Int, Long>>> { it.value.first }
                    .thenByDescending { it.key.length }
                    .thenBy { it.key }
            )
        for ((word, stat) in candidates) {
            val entity = resolve(stat.second) ?: continue
            return entity to word
        }
        return null
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in '０'..'９' -> sb.append('0' + (ch - '０'))
                ch == '　' -> sb.append(' ')
                // 货币符号只用于提示金额，识别后从文本里剔除，不进备注（v2.9.0）
                ch == '¥' || ch == '￥' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /** (分, 命中的原文)；支持 25 / 25.5 / 二十五 / 二十五块五 / 1.2万 / 100元 */
    private fun parseAmountCents(text: String): Pair<Long, String>? {
        Regex("(\\d+(?:\\.\\d{1,2})?)\\s*(万|千)?").find(text)?.let { m ->
            val base = m.groupValues[1]
            var cents = toCents(base)
            if (m.groupValues[2] == "万") cents *= 10_000
            if (m.groupValues[2] == "千") cents *= 1_000
            if (cents > 0) return cents to m.value
        }
        val cn = Regex("([零〇一二两三四五六七八九十百千万]{1,8})\\s*(?:块|元)?\\s*([零〇一二三四五六七八九])?(?:毛|角)?").find(text)
        if (cn != null && cn.groupValues[1].isNotEmpty()) {
            val whole = cnNum(cn.groupValues[1])
            if (whole > 0) {
                var cents = whole * 100L
                val frac = cn.groupValues[2]
                if (frac.isNotEmpty()) cents += cnNum(frac) * 10L
                return cents to cn.value
            }
        }
        return null
    }

    private fun toCents(num: String): Long {
        val dot = num.indexOf('.')
        return if (dot < 0) {
            (num.toLongOrNull() ?: 0L) * 100L
        } else {
            val intPart = num.substring(0, dot).toLongOrNull() ?: 0L
            val fracStr = (num.substring(dot + 1) + "0").take(2)
            intPart * 100L + (fracStr.toLongOrNull() ?: 0L)
        }
    }

    private fun cnNum(s: String): Long {
        val digits = mapOf('零' to 0L, '〇' to 0L, '一' to 1L, '二' to 2L, '两' to 2L, '三' to 3L,
            '四' to 4L, '五' to 5L, '六' to 6L, '七' to 7L, '八' to 8L, '九' to 9L)
        val units = mapOf('十' to 10L, '百' to 100L, '千' to 1000L, '万' to 10_000L)
        var total = 0L; var section = 0L; var num = 0L; var lastUnit = 1L
        for (ch in s) {
            when {
                digits.containsKey(ch) -> num = digits[ch]!!
                units.containsKey(ch) -> {
                    val u = units[ch]!!
                    if (u == 10_000L) {
                        total += (section + num) * u; section = 0L
                    } else {
                        if (num == 0L) num = 1L
                        section += num * u
                    }
                    lastUnit = u
                    num = 0L
                }
            }
        }
        // 尾数要按「最后一个数量级的十分之一」计：三千二 = 3200，一万二 = 12000
        if (num > 0L && lastUnit >= 10L) num *= lastUnit / 10
        return total + section + num
    }

    /** (日期, 命中的原文) */
    private fun parseDate(text: String, today: LocalDate): Pair<LocalDate, String> {
        val rels = listOf("大前天" to -3L, "前天" to -2L, "昨天" to -1L, "今天" to 0L, "今日" to 0L)
        for ((word, off) in rels) {
            if (text.contains(word)) {
                return today.plusDays(off) to word
            }
        }
        Regex("(上|这|本)?周([一二三四五六日天])").find(text)?.let { m ->
            val base = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)[m.groupValues[2][0]] ?: 1
            val dow = today.dayOfWeek.value
            var delta = base - dow
            if (m.groupValues[1] == "上") delta -= 7
            return today.plusDays(delta.toLong()) to m.value
        }
        Regex("(\\d{1,2})月(\\d{1,2})[日号]").find(text)?.let { m ->
            return LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) to m.value
        }
        Regex("(\\d{1,2})[日号]").find(text)?.let { m ->
            return today.withDayOfMonth(m.groupValues[1].toInt()) to m.value
        }
        return today to ""
    }
}
