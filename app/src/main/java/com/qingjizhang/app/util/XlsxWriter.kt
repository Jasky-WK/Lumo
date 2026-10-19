package com.qingjizhang.app.util

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 最小 XLSX 写出器（N58，零第三方依赖）。
 *
 * xlsx 本质是一个 zip：按 OOXML 的最小必要结构生成 6 个部件，
 * Excel / WPS / Numbers 都能直接打开。只做表格（文本 + 数字单元格、
 * 表头样式、首行冻结、列宽），不带公式与图表——记账导出完全够用。
 *
 * 文本用 inlineStr（内联字符串），省掉 sharedStrings 部件；
 * 金额用数字单元格 + "0.00" 数字格式，Excel 里可以直接求和。
 */
object XlsxWriter {

    /** 一列：表头文字 + 列宽（字符宽） */
    data class Column(val header: String, val width: Float)

    /** 单元格：文本或数字 */
    sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Num(val value: Double) : Cell
    }

    /** 一张工作表（当前只用一张，见 [write]） */
    data class Sheet(val name: String, val columns: List<Column>, val rows: List<List<Cell>>)

    /** 生成 xlsx 字节 */
    fun write(sheet: Sheet): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putEntry("[Content_Types].xml", contentTypes())
            zip.putEntry("_rels/.rels", rootRels())
            zip.putEntry("xl/workbook.xml", workbook(sheet.name))
            zip.putEntry("xl/_rels/workbook.xml.rels", workbookRels())
            zip.putEntry("xl/styles.xml", STYLES)
            zip.putEntry("xl/worksheets/sheet1.xml", sheetXml(sheet))
        }
        return bos.toByteArray()
    }

    private fun ZipOutputStream.putEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // ---------------- 各个 XML 部件 ----------------

    private fun contentTypes(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
            """<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" +
            """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
            "</Types>"

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            "</Relationships>"

    private fun workbook(sheetName: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<sheets><sheet name="${esc(sheetName)}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private fun workbookRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>""" +
            """<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            "</Relationships>"

    /**
     * 样式表：两套字体（正文 / 表头白字加粗）、三套填充（无 / 灰 / 主蓝）、
     * 三个单元格样式（0 正文、1 表头、2 数字 0.00）。
     */
    private val STYLES: String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<numFmts count="1"><numFmt numFmtId="164" formatCode="0.00"/></numFmts>""" +
            """<fonts count="2">""" +
            """<font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""" +
            "</fonts>" +
            """<fills count="3">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF4C7DF0"/><bgColor indexed="64"/></patternFill></fill>""" +
            "</fills>" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="3">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>""" +
            """<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            "</cellXfs>" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            "</styleSheet>"

    private fun sheetXml(sheet: Sheet): String {
        val colCount = sheet.columns.size
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append(
            """<dimension ref="A1:${colName(colCount - 1)}${sheet.rows.size + 1}"/>"""
        )
        // 首行冻结
        sb.append(
            """<sheetViews><sheetView workbookViewId="0">""" +
                """<pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>""" +
                "</sheetView></sheetViews>"
        )
        sb.append("""<sheetFormatPr defaultRowHeight="15"/>""")
        // 列宽
        sb.append("<cols>")
        sheet.columns.forEachIndexed { i, col ->
            val min = i + 1
            sb.append(
                """<col min="$min" max="$min" width="${col.width}" customWidth="1"/>"""
            )
        }
        sb.append("</cols>")
        sb.append("<sheetData>")
        // 表头（样式 1）
        sb.append("""<row r="1">""")
        sheet.columns.forEachIndexed { i, col ->
            sb.append(cellRef(i, 0, style = 1, text = col.header))
        }
        sb.append("</row>")
        // 数据行
        sheet.rows.forEachIndexed { r, row ->
            sb.append("""<row r="${r + 2}">""")
            row.forEachIndexed { c, cell ->
                when (cell) {
                    is Cell.Text -> if (cell.value.isNotEmpty()) {
                        sb.append(cellRef(c, r + 1, style = 0, text = cell.value))
                    }
                    is Cell.Num -> sb.append(
                        """<c r="${colName(c)}${r + 2}" s="2"><v>${
                            String.format(Locale.US, "%.2f", cell.value)
                        }</v></c>"""
                    )
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** 文本单元格（inlineStr，空文本不写单元格） */
    private fun cellRef(col: Int, row: Int, style: Int, text: String): String =
        """<c r="${colName(col)}${row + 1}" s="$style" t="inlineStr"><is><t xml:space="preserve">${
            esc(text)
        }</t></is></c>"""

    /** 0 -> A，25 -> Z，26 -> AA */
    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    /** XML 转义 + 去掉 XML 1.0 不允许的控制字符 */
    private fun esc(s: String): String = buildString(s.length + 16) {
        s.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit // 丢弃非法控制字符
                else -> append(c)
            }
        }
    }
}
