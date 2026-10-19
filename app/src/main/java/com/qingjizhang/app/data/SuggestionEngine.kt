package com.qingjizhang.app.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * 记账建议（P0-1）：纯本地、可解释的「这笔账该记到哪个分类 / 哪个账户」。
 *
 * 与 LLM 无关，只用本账本的历史账单打分，四条信号加权（权重值见下方常量区）：
 * 1. **备注关键词**（W_NOTE=60，权重最高）：当前输入与历史备注按标点 / 空格切词后，
 *    任一词（≥2 字）互相包含即命中——query「咖啡」能命中历史「星巴克咖啡」；
 *    命中的词写入 [Suggestion.keyword]，reason 展示「备注有「咖啡」」。
 *    query 为空或所有词不足 2 字时本信号不得分（防止「车」误命中「打车」）；
 * 2. **时段规律**（W_SLOT=24 + W_SLOT_AMOUNT=10）：按「早上 / 中午 / 下午 / 晚上 / 深夜」
 *    分桶，只和同一个时段的历史账单比——早上 8 点该建议打车 / 早饭，而不是半夜的夜宵；
 *    同段里金额档也接近（分档容差见 amountClose）再加 10 分；
 * 3. **工作日 / 周末**（W_WEEKEND=6，v2.9.0 新增的第四个信号）：历史样本与今天同属
 *    工作日或同属周末才加分——周末的消费结构和工作日不同，周末的高频搭配不该被
 *    工作日样本的数量压制；
 * 4. **近期频率**（W_RECENT=8）：这个「分类 + 账户 + 时段」组合最近 7 天出现过，
 *    说明正在保持习惯。
 *
 * 设计约束（规划报告 + 用户反馈）：
 * - **按时间段给不同建议**：早上推荐早餐 / 打车，中午推荐午饭，晚上推荐晚饭；
 * - **可解释**：[Suggestion.reason] 直接给出「备注有「咖啡」」/「早上常记」；
 * - **不扰民**：样本不足（MIN_SUPPORT=2）或分数不够（MIN_SCORE=36）就返回 null，
 *   宁可不建议；备注一旦有命中，只认命中的那些搭配（避免「咖啡」被高频的「餐饮」挤掉）；
 * - **改完即学**：用户改了分类，就等于在历史里多了一条新样本。
 */
object SuggestionEngine {

    /** 建议来源（用于 UI 文案与埋点） */
    enum class Source(val label: String) {
        NOTE("记得你上次这么记"),
        ROUTINE("按你的习惯"),
        MEMORY("常用搭配")
    }

    /** 一天里的时段分桶：早上 / 中午 / 下午 / 晚上 / 深夜（跨零点归到深夜） */
    enum class Slot(val label: String) {
        MORNING("早上"),
        NOON("中午"),
        AFTERNOON("下午"),
        EVENING("晚上"),
        NIGHT("深夜");

        companion object {
            fun of(hour: Int): Slot = when (hour) {
                in 5..9 -> MORNING
                in 10..14 -> NOON
                in 15..17 -> AFTERNOON
                in 18..21 -> EVENING
                else -> NIGHT     // 22~23 与 0~4
            }
        }
    }

    data class Suggestion(
        val categoryId: Long,
        val accountId: Long,
        val source: Source,
        /** 0~100，仅供 UI 显示「像不像」，不参与决策之外的逻辑 */
        val confidence: Int,
        /** 命中的备注关键词（备注信号才有） */
        val keyword: String = "",
        /** 当前时段（文案用） */
        val slot: Slot = Slot.MORNING
    ) {
        /** 一句话说明为什么推荐（UI 直接显示） */
        val reason: String
            get() = when {
                source == Source.NOTE && keyword.isNotBlank() -> "备注有「$keyword」"
                source == Source.ROUTINE -> slot.label + "常记"
                else -> "常用搭配"
            }
    }

    private const val WINDOW_DAYS = 180L
    private const val MAX_SCAN = 400
    private const val MIN_SCORE = 36
    private const val MIN_SUPPORT = 2

    // 各信号权重（集中在这里，方便调）
    private const val W_NOTE = 60            // 备注词级重叠
    private const val W_SLOT = 24            // 同时段
    private const val W_SLOT_AMOUNT = 10     // 同时段且金额分档接近
    private const val W_WEEKEND = 6          // 同属工作日 / 周末（v2.9.0 新增）
    private const val W_RECENT = 8           // 最近 7 天同组合
    private const val RECENT_DAYS = 7L

    /** 金额分档边界（单位：分）：≤¥100、¥100~1000、>¥1000 三档各自不同容差 */
    private const val AMOUNT_TIER_SMALL = 10_000L
    private const val AMOUNT_TIER_LARGE = 100_000L

    /** 切词分隔符：标点 / 空格 / 一切非文字数字字符（中文无分隔符，整段连成一个词） */
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /** 周六 / 周日算周末，其余为工作日 */
    private fun DayOfWeek.isWeekend(): Boolean =
        this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

    /** 把文本按标点 / 空格等切词，丢掉空片段 */
    private fun words(text: String): List<String> =
        text.split(WORD_SPLIT).filter { it.isNotEmpty() }

    /**
     * 词级备注重叠（v2.9.0 起，替代原来的整串互相包含）：
     * query 与历史备注切词后，任一词（≥2 字）互相包含即命中，返回命中的词
     * （较短的那个，供 reason 展示，如 query「咖啡」对历史「星巴克咖啡」返回「咖啡」）；
     * 未命中返回 null。
     * query 为空或所有词不足 2 字时直接放弃——防止「车」误命中「打车」这类单字误配。
     */
    private fun noteKeyword(query: String, historyNote: String): String? {
        if (query.isBlank() || historyNote.isBlank()) return null
        val queryWords = words(query).filter { it.length >= 2 }
        if (queryWords.isEmpty()) return null
        val historyWords = words(historyNote).filter { it.length >= 2 }
        for (q in queryWords) {
            for (h in historyWords) {
                if (q.contains(h, ignoreCase = true) || h.contains(q, ignoreCase = true)) {
                    return if (q.length <= h.length) q else h
                }
            }
        }
        return null
    }

    /**
     * 金额分档容差（v2.9.0 起，替代原来的固定 ±25%；整数比较，避免浮点）：
     * 以当前输入金额定档——≤¥100 ±50%、¥100~1000 ±30%、>¥1000 ±20%。
     * 大额如房租不该被固定 ±25% 误判为「接近」。
     */
    private fun amountClose(inputCents: Long, sampleCents: Long): Boolean {
        if (inputCents <= 0L || sampleCents <= 0L) return false
        val diff = Math.abs(sampleCents - inputCents)
        return when {
            inputCents <= AMOUNT_TIER_SMALL -> diff * 2 <= inputCents          // ±50%
            inputCents <= AMOUNT_TIER_LARGE -> diff * 10 <= inputCents * 3     // ±30%
            else -> diff * 5 <= inputCents                                     // ±20%
        }
    }

    /**
     * 给出建议；样本不足或没有明显倾向时返回 null。
     *
     * @param type 只对收入 / 支出建议（互转、借贷有专用面板）
     */
    fun suggest(
        history: List<Txn>,
        type: TxnType,
        note: String,
        amountCents: Long,
        now: Long = System.currentTimeMillis()
    ): Suggestion? {
        if (type != TxnType.EXPENSE && type != TxnType.INCOME) return null

        val zone = ZoneId.systemDefault()
        val since = now - WINDOW_DAYS * 24L * 60 * 60 * 1000
        val recentSince = now - RECENT_DAYS * 24L * 60 * 60 * 1000
        val samples = history.asSequence()
            .filter {
                it.type == type &&
                    it.deletedAt == 0L &&
                    it.categoryId > 0L &&
                    it.dateMillis >= since
            }
            .sortedByDescending { it.dateMillis }
            .take(MAX_SCAN)
            .toList()
        if (samples.size < MIN_SUPPORT) return null

        val nowLocal = Instant.ofEpochMilli(now).atZone(zone)
        val nowSlot = Slot.of(nowLocal.hour)
        val nowWeekend = nowLocal.dayOfWeek.isWeekend()
        val query = note.trim()

        // (categoryId to accountId) -> 分数 / 命中次数 / 备注命中关键词 / 同时段命中次数
        val score = HashMap<Pair<Long, Long>, Int>()
        val support = HashMap<Pair<Long, Long>, Int>()
        val keywordHit = HashMap<Pair<Long, Long>, String>()
        val slotHits = HashMap<Pair<Long, Long>, Int>()
        var noteMatchedAny = false

        for (t in samples) {
            val key = t.categoryId to t.accountId
            var s = 0

            // 1) 备注关键词（词级重叠）
            if (query.isNotBlank() && t.note.isNotBlank()) {
                val kw = noteKeyword(query, t.note)
                if (kw != null) {
                    s += W_NOTE
                    noteMatchedAny = true
                    if (keywordHit[key].isNullOrEmpty()) keywordHit[key] = kw
                }
            }

            val tLocal = Instant.ofEpochMilli(t.dateMillis).atZone(zone)

            // 2) 时段（分桶比对，符合"早上/中午/晚上"的直觉）
            if (Slot.of(tLocal.hour) == nowSlot) {
                s += W_SLOT
                slotHits[key] = (slotHits[key] ?: 0) + 1
                // 同段且金额分档接近（档位见 amountClose）
                if (amountClose(amountCents, t.amountCents)) s += W_SLOT_AMOUNT
            }

            // 3) 工作日 / 周末：同属工作日或同属周末才加分（周末消费结构不同）
            if (tLocal.dayOfWeek.isWeekend() == nowWeekend) s += W_WEEKEND

            // 4) 最近 7 天出现过同组合：正在保持的习惯
            if (t.dateMillis >= recentSince) s += W_RECENT

            if (s > 0) {
                score[key] = (score[key] ?: 0) + s
                support[key] = (support[key] ?: 0) + 1
            }
        }

        // 备注命中时：只认命中的那些搭配（避免"咖啡"被高频的"餐饮"挤掉）
        val candidates = if (noteMatchedAny) {
            score.filterKeys { keywordHit[it] != null }
        } else {
            score
        }

        val best = candidates.entries
            .filter { (support[it.key] ?: 0) >= MIN_SUPPORT && it.value >= MIN_SCORE }
            .maxByOrNull { it.value }
            ?: return null

        val (categoryId, accountId) = best.key
        val total = best.value
        val source = when {
            keywordHit[best.key] != null -> Source.NOTE
            // 「按你的习惯」= 同时段命中 ≥2 次。旧判定是总分 ≥ W_SLOT*2；
            // v2.9.0 新增 W_WEEKEND 后只看总分会被周末样本抬高（1 次同时段加几个
            // 周末样本也可能过线），「晚上常记」就名不副实，所以改成数同时段命中
            // 次数——同时段 ≥2 次时总分必然 ≥ W_SLOT*2，原来能过的仍过得去。
            (slotHits[best.key] ?: 0) >= MIN_SUPPORT -> Source.ROUTINE
            else -> Source.MEMORY
        }
        return Suggestion(
            categoryId = categoryId,
            accountId = accountId,
            source = source,
            confidence = (total / 2).coerceIn(0, 100),
            keyword = keywordHit[best.key].orEmpty(),
            slot = nowSlot
        )
    }
}
