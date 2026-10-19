package com.qingjizhang.app.util

/**
 * 面向用户的错误文案（v2.9.1 打磨项 ①）。
 *
 * 改造前：68 处失败提示直接把 `Throwable.message` 拼给用户，例如
 * `保存失败：UNIQUE constraint failed: account.name (code 2067 SQLITE_CONSTRAINT_UNIQUE)`、
 * `恢复失败：open failed: EACCES (Permission denied)`——对普通用户是天书，
 * 还泄露了内部实现细节。
 *
 * 改造后：所有失败路径统一走 [UserError]，把技术异常翻译成「一句人话 + 一句下一步建议」，
 * 未知异常则给一个**短错误码**（用户可截图反馈，开发者能据此定位），
 * 完整异常仍然写入本地崩溃/诊断日志（见 [BootLog]），不丢排查信息。
 *
 * 设计约束：
 * - **纯 Kotlin、无 Android 依赖**，可直接单测（与 Money / DateFmt 同一定位）；
 * - 文案里不出现 SQL、路径、类名、堆栈等内部细节；
 * - 统一带上业务前缀（如「保存失败」），保持既有提示的语义不变。
 */
object UserError {

    /** 未知异常时展示给用户的错误码前缀，后接 4 位十六进制 */
    const val CODE_PREFIX = "E"

    /**
     * 把异常翻译成一句用户能看懂的话。
     *
     * @param action 业务动作前缀，如「保存失败」「恢复失败」「导出失败」
     * @param error  原始异常（可为 null，表示只知道失败了、不知道原因）
     * @return 形如 `「保存失败：名称重复了，换一个名字试试」` 的完整文案
     */
    fun text(action: String, error: Throwable?): String {
        val hint = when {
            error == null -> UNKNOWN_HINT
            isOutOfSpace(error) -> "手机存储空间不足，清理一些空间后再试"
            isPermission(error) -> "没有访问这个文件的权限，换一个位置或换一种方式再试"
            isNotFound(error) -> "文件不存在或已被移除，请重新选择"
            isConstraint(error) -> "名称重复了，换一个名字试试"
            isCorrupt(error) -> "文件已损坏或格式不对，换一个文件试试"
            isCancelled(error) -> "操作已取消"
            else -> UNKNOWN_HINT
        }
        // 未知异常附带错误码，便于用户反馈时定位；已知异常不加噪音
        val suffix = if (error != null && hint == UNKNOWN_HINT) {
            "（" + codeOf(error) + "）"
        } else ""
        return "$action：$hint$suffix"
    }

    /** 未知提示的统一措辞 */
    private const val UNKNOWN_HINT = "出了点问题，请重试；仍不行可在帮助页反馈"

    // ---------------- 分类判定 ----------------

    /**
     * 存储空间不足。SQLite / 文件写入空间不够时抛 `SQLiteFullException` /
     * `IOException: No space left on device`。注意必须排在 [isConstraint] 之前判断，
     * 因为 `SQLiteFullException` 也是 `SQLiteException` 的子类。
     */
    fun isOutOfSpace(error: Throwable): Boolean = anyInChain(error) { t ->
        val name = t.javaClass.simpleName
        val msg = t.message.orEmpty()
        name == "SQLiteFullException" ||
            name == "OutOfMemoryError" ||
            msg.contains("No space left", ignoreCase = true) ||
            msg.contains("ENOSPC") ||
            msg.contains("disk is full", ignoreCase = true)
    }

    /** 权限被拒（Scoped Storage / URI 授权失效 / 存储被占用） */
    fun isPermission(error: Throwable): Boolean = anyInChain(error) { t ->
        val name = t.javaClass.simpleName
        val msg = t.message.orEmpty()
        name == "SecurityException" ||
            msg.contains("EACCES") ||
            msg.contains("EPERM") ||
            msg.contains("Permission denied", ignoreCase = true) ||
            msg.contains("Permission Denial", ignoreCase = true)
    }

    /** 文件 / URI 不存在 */
    fun isNotFound(error: Throwable): Boolean = anyInChain(error) { t ->
        val name = t.javaClass.simpleName
        val msg = t.message.orEmpty()
        name == "FileNotFoundException" ||
            msg.contains("ENOENT") ||
            msg.contains("No such file", ignoreCase = true) ||
            msg.contains("not found", ignoreCase = true) && msg.contains("file", ignoreCase = true)
    }

    /** 唯一约束冲突（名称重复）；可合理触发：账户 / 分类 / 标签 / 账本同名 */
    fun isConstraint(error: Throwable): Boolean = anyInChain(error) { t ->
        val msg = t.message.orEmpty()
        val name = t.javaClass.simpleName
        // 空间不足优先：SQLITE_FULL 不是约束问题
        !msg.contains("SQLITE_FULL", ignoreCase = true) &&
            (name == "SQLiteConstraintException" ||
                msg.contains("SQLITE_CONSTRAINT", ignoreCase = true) ||
                msg.contains("UNIQUE constraint", ignoreCase = true))
    }

    /** 备份 / 导入文件损坏、JSON 解析失败、数据库文件不完整 */
    fun isCorrupt(error: Throwable): Boolean = anyInChain(error) { t ->
        val name = t.javaClass.simpleName
        val msg = t.message.orEmpty()
        name == "JSONException" ||
            name == "SQLiteDatabaseCorruptException" ||
            name == "ZipException" ||
            msg.contains("database disk image is malformed", ignoreCase = true) ||
            msg.contains("not a valid zip", ignoreCase = true) ||
            msg.contains("Unexpected end of", ignoreCase = true)
    }

    /** 用户主动取消（文件选择器返回 null 之类不该报错） */
    fun isCancelled(error: Throwable): Boolean = anyInChain(error) { t ->
        t.javaClass.simpleName.contains("Cancellation") ||
            t.message.orEmpty().contains("cancel", ignoreCase = true)
    }

    /**
     * 错误码：把异常类名与消息散列成 4 位十六进制。
     * 同一类异常在同一位置稳定复现同一错误码，便于用户反馈后反查；
     * 不含任何原始文本，因此可以安全展示。
     */
    fun codeOf(error: Throwable): String {
        val basis = error.javaClass.name + "|" + error.message.orEmpty()
        var h = 0
        for (c in basis) h = 31 * h + c.code
        val hex = Integer.toHexString(h and 0xFFFF).uppercase()
        return CODE_PREFIX + " " + hex.padStart(4, '0')
    }

    /** 沿 cause 链逐层判定（SQLite 的约束异常常包在外层异常里） */
    private inline fun anyInChain(error: Throwable, predicate: (Throwable) -> Boolean): Boolean {
        var t: Throwable? = error
        var guard = 0
        while (t != null && guard < 8) {
            if (predicate(t)) return true
            t = t.cause
            guard++
        }
        return false
    }
}
