package com.qingjizhang.app.data

import com.qingjizhang.app.util.Money

/**
 * 异常账单检测（P1-3）：纯本地规则，帮用户发现「可能记错的账」。
 *
 * 只做两条**保守、可解释**的规则——误报比漏报更伤信任：
 *
 * 1. **疑似重复扣款**：同账户 + 完全相同金额的支出，7 天内出现两次，
 *    **且这个「账户 + 金额」组合在近 90 天里出现次数 < 4**（低频）。
 *    这条「低频」条件是 v2.5.1 根据用户反馈补的：每天中午固定 15 元午饭、
 *    每周三次同价咖啡这类**规律消费**出现次数很多，绝不能报成重复扣款。
 *
 * 2. **金额偏高**：近 7 天的某笔支出同时满足
 *    - 高于该账本近 180 天支出的 P95，且高于中位数的 3 倍；
 *    - 这个金额档（±1%）在近 180 天里只出现过 1 次。
 *    「只出现 1 次」可排除每月房租 / 房贷这类**固定大额**——金额高但完全正常。
 *    样本 < 20 笔时不判（小额账本天天报「异常」没意义）。
 *
 * 每条结果带一个稳定的 [Flag.key]，用户「忽略」后不再提示（存在本地 prefs）。
 */
object AnomalyDetector {

    enum class Kind(val label: String) {
        DUPLICATE("疑似重复扣款"),
        LARGE("金额偏高")
    }

    data class Flag(
        /** 稳定标识：忽略过就不再提示（含账单 id，账单删了自然消失） */
        val key: String,
        val kind: Kind,
        val txnIds: List<Long>,
        val title: String,
        val detail: String,
        /** 用于排序（取最新那笔的时间） */
        val latestAt: Long
    )

    private const val DAY = 24L * 60 * 60 * 1000

    // 重复扣款
    private const val DUP_PAIR_WINDOW_DAYS = 7L      // 两笔最多相隔 7 天
    private const val DUP_RECENT_DAYS = 60L          // 只关心近 60 天内的重复
    private const val DUP_MIN_CENTS = 100L           // 1 元以下不提示
    private const val DUP_HABIT_LOOKBACK_DAYS = 90L  // 判断「是不是规律消费」的回看窗口
    private const val DUP_HABIT_COUNT = 4            // 90 天内出现 ≥4 次 = 规律消费，不报

    // 金额偏高
    private const val LARGE_RECENT_DAYS = 7L
    private const val BASELINE_DAYS = 180L
    private const val BASELINE_MIN_SAMPLES = 20
    private const val LARGE_MEDIAN_MULTIPLE = 3.0

    private const val MAX_FLAGS = 3

    fun detect(txns: List<Txn>, now: Long = System.currentTimeMillis()): List<Flag> {
        val expenses = txns.filter {
            it.type == TxnType.EXPENSE && it.deletedAt == 0L && it.amountCents > 0L
        }
        if (expenses.isEmpty()) return emptyList()

        val flags = mutableListOf<Flag>()

        // ---------------------------------------------------------- 1) 疑似重复扣款
        val dupRecentSince = now - DUP_RECENT_DAYS * DAY
        val habitSince = now - DUP_HABIT_LOOKBACK_DAYS * DAY
        // 先算出「账户+金额」在近 90 天的出现次数：次数多的是规律消费，不参与判定
        val habitCounts = expenses.asSequence()
            .filter { it.dateMillis >= habitSince }
            .groupingBy { it.accountId to it.amountCents }
            .eachCount()

        expenses.asSequence()
            .filter { it.dateMillis >= dupRecentSince && it.amountCents >= DUP_MIN_CENTS }
            .groupBy { it.accountId to it.amountCents }
            .forEach { (key, group) ->
                // v2.5.1：规律消费（每天午饭 15 元、通勤打车…）直接跳过
                if ((habitCounts[key] ?: 0) >= DUP_HABIT_COUNT) return@forEach

                val sorted = group.sortedBy { it.dateMillis }
                for (i in 0 until sorted.size - 1) {
                    val a = sorted[i]
                    val b = sorted[i + 1]
                    if (b.dateMillis - a.dateMillis > DUP_PAIR_WINDOW_DAYS * DAY) continue

                    flags += Flag(
                        key = "dup:${key.first}:${key.second}:${a.id}-${b.id}",
                        kind = Kind.DUPLICATE,
                        txnIds = listOf(a.id, b.id),
                        title = "疑似重复扣款 ${Money.text(key.second)}",
                        detail = (a.note.ifBlank { "无备注" }) + " · 相隔 " +
                            ((b.dateMillis - a.dateMillis) / DAY) + " 天",
                        latestAt = b.dateMillis
                    )
                    break   // 同金额同账户只报第一对，避免刷屏
                }
            }

        // ---------------------------------------------------------- 2) 金额偏高
        val baseline = expenses.filter { it.dateMillis >= now - BASELINE_DAYS * DAY }
        if (baseline.size >= BASELINE_MIN_SAMPLES) {
            val sortedAmounts = baseline.map { it.amountCents }.sorted()
            val p95 = sortedAmounts[((sortedAmounts.size - 1) * 0.95).toInt()
                .coerceIn(0, sortedAmounts.size - 1)]
            val median = sortedAmounts[sortedAmounts.size / 2]
            // 双门槛：既要超过 P95，也要超过中位数的 3 倍（避免「大家都差不多」时误报）
            val floor = maxOf(p95, (median * LARGE_MEDIAN_MULTIPLE).toLong())

            val largeSince = now - LARGE_RECENT_DAYS * DAY
            expenses.asSequence()
                .filter { it.dateMillis >= largeSince && it.amountCents > floor }
                .sortedByDescending { it.dateMillis }
                .take(2)
                .forEach { t ->
                    // 固定大额（房租 / 房贷）会重复出现同一金额，不算异常
                    val sameAmountCount = baseline.count {
                        Math.abs(it.amountCents - t.amountCents) * 100 <= t.amountCents
                    }
                    if (sameAmountCount >= 2) return@forEach

                    flags += Flag(
                        key = "big:${t.id}",
                        kind = Kind.LARGE,
                        txnIds = listOf(t.id),
                        title = "金额偏高 ${Money.text(t.amountCents)}",
                        detail = "比你这半年的绝大多数支出都高（" + t.note.ifBlank { "无备注" } + "）",
                        latestAt = t.dateMillis
                    )
                }
        }

        return flags.sortedByDescending { it.latestAt }.take(MAX_FLAGS)
    }
}
