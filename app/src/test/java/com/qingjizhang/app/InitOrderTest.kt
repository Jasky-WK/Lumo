package com.qingjizhang.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.8.1 回归测试：AppViewModel 的属性初始化顺序。
 *
 * Kotlin 的属性与 init 块按**声明顺序**执行：初始化器里引用声明更靠后的属性，
 * 拿到的是还没赋值的 null。v2.8.0 的启动闪退正是这么来的——
 * `_prefsTick` 声明在 `boolFlow()` 调用之后，`combine(dbReady, _prefsTick)`
 * 收到 null，启动时 combine 内部为每个源 flow 起的收集协程对 null 调 collect，
 * 直接 NPE（崩在 DefaultDispatcher-worker-7，R8 之后堆栈只剩混淆名，极难排查）。
 *
 * 这类错误编译期查不出，运行时也要真机构建 VM 才复现，
 * 所以在单元测试里做源码静态检查：任何属性 / init 块的初始化代码
 * （含它调用的同类函数体，递归展开）不允许引用声明更靠后的**非 lazy** 属性。
 *
 * `by lazy` 属性首次访问时才求值，与声明顺序无关，因此不参与检查。
 */
class InitOrderTest {

    @Test
    fun appViewModel_noForwardReferenceInInitializers() {
        val problems = checkInitOrder(locateAppViewModel())
        assertTrue(
            "发现 ${problems.size} 处「先用后声明」（Kotlin 属性按声明顺序初始化，" +
                "先用后声明会拿到 null，v2.8.0 启动闪退根因）：" +
                problems.joinToString("") { "\n  - $it" },
            problems.isEmpty()
        )
    }

    // ---------------- 实现 ----------------

    private class Decl(val name: String, val line: Int, val lazy: Boolean)

    private class Func(val name: String, val start: Int, val end: Int, val params: Set<String>)

    private fun locateAppViewModel(): File {
        val candidates = listOf(
            "src/main/java/com/qingjizhang/app/vm/AppViewModel.kt",
            "app/src/main/java/com/qingjizhang/app/vm/AppViewModel.kt"
        ).map(::File)
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 AppViewModel.kt，CWD = ${File("").absolutePath}")
    }

    private fun checkInitOrder(file: File): List<String> {
        // 去掉块注释与字符串字面量，避免注释 / 文案里的名字造成误报（行号保持不变）
        val raw = file.readText().replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        val allLines = raw.lines().map { stripCommentAndStrings(it) }
        // 只分析 AppViewModel 类体：文件顶部的 data class 构造参数也是 4 空格缩进的 val，
        // 但它们不是属性初始化，不参与检查
        val classLine = allLines.indexOfFirst { it.startsWith("class AppViewModel") }
        require(classLine >= 0) { "没找到 AppViewModel 类声明" }
        val lines = allLines.drop(classLine + 1)
        val offset = classLine + 1 // 报告行号时加回去

        // 1. 类体级（4 空格缩进）属性声明
        val declRe = Regex("^    (?:private |internal |public )?(?:val|var)\\s+(\\w+)")
        val decls = mutableListOf<Decl>()
        lines.forEachIndexed { i, l ->
            declRe.find(l)?.let { decls += Decl(it.groupValues[1], i, l.contains("by lazy")) }
        }

        // 2. 类体级函数：名字 + 函数体区间 + 参数名（参数会屏蔽同名属性，如 buildStats(cats)）
        val funRe = Regex("^    (?:private |internal |public )?(?:suspend )?fun\\s+.*?(\\w+)\\s*[<(]")
        val stopRe = Regex("^    (?:private |internal |public )?(?:val|var|fun|init|companion|class|object)\\b|^    \\}")
        val funcs = mutableListOf<Func>()
        lines.forEachIndexed { i, l ->
            val m = funRe.find(l) ?: return@forEachIndexed
            var end = i
            while (end + 1 < lines.size && !stopRe.containsMatchIn(lines[end + 1])) end++
            val params = Regex("(\\w+)\\s*:").findAll(l).mapTo(mutableSetOf()) { it.groupValues[1] }
            funcs += Func(m.groupValues[1], i, end, params)
        }
        val funcByName = funcs.associateBy { it.name }

        // 3. init 块：执行位置等同于「声明在该行的属性」
        val initRe = Regex("^    init\\s*\\{")
        val pseudoDecls = mutableListOf<Decl>()
        lines.forEachIndexed { i, l ->
            if (!initRe.containsMatchIn(l)) return@forEachIndexed
            var end = i
            while (end + 1 < lines.size && lines[end + 1] != "    }") end++
            pseudoDecls += Decl("<init 块>", i, lazy = false)
        }

        // 4. 初始化区：从声明行到下一个空行 / 下一个类体级声明为止
        fun regionOf(start: Int): IntRange {
            var end = start
            while (end + 1 < lines.size &&
                lines[end + 1].isNotBlank() &&
                !declRe.containsMatchIn(lines[end + 1]) &&
                !funRe.containsMatchIn(lines[end + 1]) &&
                !initRe.containsMatchIn(lines[end + 1]) &&
                lines[end + 1] != "    }"
            ) end++
            return start..end
        }

        // 5. 逐个属性 / init 块：展开初始化代码（含调用的同类函数体），
        //    检查是否引用了声明更靠后的非 lazy 属性
        val problems = mutableListOf<String>()
        for (d in decls + pseudoDecls) {
            val texts = mutableListOf<Pair<String, Set<String>>>() // (代码, 被参数屏蔽的名字)
            val visited = mutableSetOf<String>()

            fun absorb(range: IntRange, shadow: Set<String>) {
                val text = range.joinToString("\n") { lines[it] }
                texts += text to shadow
                // 区内裸调用的同类函数，递归展开（函数体在调用时才会执行）
                val calls = Regex("(?<![.\\w])(\\w+)\\s*\\(").findAll(text)
                    .map { it.groupValues[1] }.toSet()
                for (c in calls) {
                    val f = funcByName[c] ?: continue
                    if (!visited.add(f.name)) continue
                    absorb(f.start..f.end, f.params)
                }
            }

            absorb(regionOf(d.line), emptySet())

            for (q in decls) {
                if (q.line <= d.line || q.lazy) continue
                val refRe = Regex("(?<![.\\w])" + Regex.escape(q.name) + "(?!\\w)")
                val hit = texts.any { (text, shadow) ->
                    q.name !in shadow && refRe.containsMatchIn(text)
                }
                if (hit) {
                    problems += "第 ${d.line + 1 + offset} 行的「${d.name}」初始化时引用了第 ${q.line + 1 + offset} 行才声明的「${q.name}」"
                }
            }
        }
        return problems.distinct()
    }

    /** 去掉行注释与字符串字面量（本文件无跨行原始字符串，单行处理足够） */
    private fun stripCommentAndStrings(line: String): String {
        val ci = line.indexOf("//")
        var s = if (ci >= 0) line.substring(0, ci) else line
        s = Regex("\"(?:[^\"\\\\]|\\\\.)*\"").replace(s, "\"\"")
        return s
    }
}
