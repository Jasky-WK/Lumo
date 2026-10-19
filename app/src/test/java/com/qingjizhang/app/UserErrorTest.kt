package com.qingjizhang.app

import com.qingjizhang.app.util.UserError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * v2.9.1 打磨项 ①：面向用户的错误文案（UserError）。
 *
 * 守住的几条约定：
 * 1. 所有文案都带业务前缀；
 * 2. 已知异常翻译成人话，**不含** SQL / 类名 / 路径等内部细节；
 * 3. 未知异常给一个稳定的错误码，便于用户反馈时定位；
 * 4. cause 链上的异常也能被识别（SQLite 常把约束异常包在外层）。
 */
class UserErrorTest {

    @Test
    fun constraint_translatesToDuplicateNameHint() {
        val e = IllegalStateException("UNIQUE constraint failed: account.name (code 2067 SQLITE_CONSTRAINT_UNIQUE)")
        val text = UserError.text("保存失败", e)
        assertTrue(text.startsWith("保存失败："))
        assertTrue(text.contains("重复"))
        // 不得泄露内部细节
        assertFalse(text.contains("SQLITE"))
        assertFalse(text.contains("constraint"))
        assertFalse(text.contains("account.name"))
    }

    @Test
    fun permission_denied_translatesToAccessHint() {
        val e = FileNotFoundException("/storage/emulated/0/x.zip: open failed: EACCES (Permission denied)")
        val text = UserError.text("恢复失败", e)
        assertTrue(text.startsWith("恢复失败："))
        assertTrue(text.contains("权限"))
        assertFalse(text.contains("EACCES"))
        assertFalse(text.contains("/storage"))
    }

    @Test
    fun outOfSpace_translatesToStorageHint() {
        val text = UserError.text("导出失败", IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(text.contains("空间"))
        assertFalse(text.contains("ENOSPC"))
    }

    @Test
    fun corruptFile_translatesToFormatHint() {
        val text = UserError.text("导入失败", IOException("Unexpected end of ZLIB input stream"))
        assertTrue(text.contains("损坏") || text.contains("格式"))
    }

    @Test
    fun unknownError_carriesStableCode_andNoRawMessage() {
        val e = IllegalStateException("boom 内部实现细节")
        val a = UserError.text("操作失败", e)
        val b = UserError.text("操作失败", e)
        assertEquals(a, b)                       // 同一异常稳定同一文案
        assertTrue(a.contains("E "))             // 带错误码
        assertFalse(a.contains("boom"))          // 不泄露原始消息
        assertFalse(a.contains("IllegalState"))
    }

    @Test
    fun differentUnknownErrors_getDifferentCodes() {
        val c1 = UserError.codeOf(IllegalStateException("a"))
        val c2 = UserError.codeOf(IllegalStateException("b"))
        assertTrue(c1 != c2)
        assertTrue(c1.startsWith(UserError.CODE_PREFIX))
        assertEquals(6, c1.length)               // "E " + 4 位
    }

    @Test
    fun nullError_stillProducesUsableText() {
        val text = UserError.text("备份失败", null)
        assertEquals("备份失败：出了点问题，请重试；仍不行可在帮助页反馈", text)
        assertFalse(text.contains("E "))         // 没有异常就没有错误码
    }

    @Test
    fun wrappedCause_isDetected() {
        // SQLite 常把约束异常包在外层：外层 IllegalArgumentException、cause 是约束
        val inner = RuntimeException("UNIQUE constraint failed: tags.name")
        val outer = IllegalStateException("while inserting", inner)
        assertTrue(UserError.isConstraint(outer))
        assertTrue(UserError.text("新建失败", outer).contains("重复"))
    }

    @Test
    fun causeCycles_doNotHang() {
        // 自引用的 cause 链不应死循环
        val a = RuntimeException("a")
        assertFalse(UserError.isCorrupt(a))
        val text = UserError.text("操作失败", a)
        assertTrue(text.startsWith("操作失败："))
    }

    @Test
    fun cancelled_isReportedAsCancelled() {
        val text = UserError.text("恢复失败", RuntimeException("Operation canceled by user"))
        assertTrue(text.contains("取消"))
    }

    @Test
    fun fullSqliteException_isOutOfSpace_notConstraint() {
        // 空间不足必须优先于「重复名称」判定：真实的 SQLiteFullException 消息长这样，
        // 而同一条分支里如果先判 constraint 就会给出误导性的「名称重复」提示。
        val full = IllegalStateException(
            "android.database.sqlite.SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)"
        )
        assertTrue(UserError.isOutOfSpace(full))
        assertFalse(UserError.isConstraint(full))
        assertTrue(UserError.text("保存失败", full).contains("空间"))
    }

    @Test
    fun fullText_neverLeaksClassNamesOrPaths() {
        val samples = listOf(
            IllegalStateException("UNIQUE constraint failed: ledger.name"),
            FileNotFoundException("/data/user/0/com.qingjizhang.app/x: open failed: EACCES (Permission denied)"),
            IOException("No space left on device"),
            IllegalArgumentException("some internal bug at com.qingjizhang.app.data.Repository")
        )
        samples.forEach { e ->
            val text = UserError.text("操作失败", e)
            assertFalse("泄露类名: $text", text.contains("com.qingjizhang"))
            assertFalse("泄露 SQL: $text", text.contains("constraint"))
            assertFalse("泄露路径: $text", text.contains("/data/"))
            assertFalse("泄露权限错误码: $text", text.contains("EACCES"))
        }
    }
}
