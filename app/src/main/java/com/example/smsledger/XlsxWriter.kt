package com.example.smsledger

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a real .xlsx workbook (Transactions + Daily summary) without any external library. */
object XlsxWriter {
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    private const val CONTENT_TYPES = HEAD + """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private const val ROOT_RELS = HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK = HEAD + """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Transactions" sheetId="1" r:id="rId1"/><sheet name="Daily summary" sheetId="2" r:id="rId2"/></sheets></workbook>"""

    private const val WORKBOOK_RELS = HEAD + """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    // Styles: 0 normal, 1 bold header, 2 amount (#,##0.00), 3 date (yyyy-mm-dd), 4 time (hh:mm AM/PM)
    private const val STYLES = HEAD + """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy\-mm\-dd"/><numFmt numFmtId="165" formatCode="hh:mm\ AM/PM"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' -> {} // illegal in XML, drop
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun ref(c: Int, r: Int) = "${'A' + c}$r"
    private fun text(c: Int, r: Int, v: String, s: Int = 0) =
        """<c r="${ref(c, r)}" t="inlineStr" s="$s"><is><t xml:space="preserve">${esc(v)}</t></is></c>"""
    private fun number(c: Int, r: Int, v: Double, s: Int) = """<c r="${ref(c, r)}" s="$s"><v>$v</v></c>"""
    private fun dayFraction(t: String): Double? = try {
        java.time.LocalTime.parse(t).toSecondOfDay() / 86400.0
    } catch (e: Exception) { null }
    private fun serial(d: String): Double = try { (LocalDate.parse(d).toEpochDay() + 25569).toDouble() } catch (e: Exception) { 0.0 }

    private fun sheet(widths: List<Int>, rows: String): String {
        val cols = widths.mapIndexed { i, w -> """<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""" }.joinToString("")
        return HEAD + """<worksheet xmlns="$NS"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>$cols</cols><sheetData>$rows</sheetData></worksheet>"""
    }

    fun build(txns: List<Txn>): ByteArray {
        val sorted = txns.sortedWith(compareByDescending<Txn> { it.date }.thenByDescending { it.time })

        // Sheet 1: every transaction
        val heads = listOf("Date", "Time", "Type", "Amount", "Merchant", "Category", "Account", "Balance", "Original SMS")
        val r1 = StringBuilder("""<row r="1">""" + heads.mapIndexed { i, h -> text(i, 1, h, 1) }.joinToString("") + "</row>")
        sorted.forEachIndexed { idx, t ->
            val r = idx + 2
            r1.append("""<row r="$r">""")
                .append(number(0, r, serial(t.date), 3))
                .append(dayFraction(t.time)?.let { number(1, r, it, 4) } ?: "")
                .append(text(2, r, if (t.type == "debit") "Debit" else "Credit"))
                .append(number(3, r, t.amount, 2))
                .append(text(4, r, t.merchant))
                .append(text(5, r, t.category))
                .append(text(6, r, if (t.account.isEmpty()) "" else "XX" + t.account))
                .append(if (t.balance != null) number(7, r, t.balance, 2) else "")
                .append(text(8, r, t.raw))
                .append("</row>")
        }

        // Sheet 2: one line per day
        val h2 = listOf("Date", "Spent", "Received", "Net", "Transactions")
        val r2 = StringBuilder("""<row r="1">""" + h2.mapIndexed { i, h -> text(i, 1, h, 1) }.joinToString("") + "</row>")
        sorted.groupBy { it.date }.entries.sortedByDescending { it.key }.forEachIndexed { idx, e ->
            val r = idx + 2
            val spent = e.value.filter { it.type == "debit" }.sumOf { it.amount }
            val got = e.value.filter { it.type == "credit" }.sumOf { it.amount }
            r2.append("""<row r="$r">""")
                .append(number(0, r, serial(e.key), 3))
                .append(number(1, r, spent, 2))
                .append(number(2, r, got, 2))
                .append(number(3, r, got - spent, 2))
                .append(number(4, r, e.value.size.toDouble(), 0))
                .append("</row>")
        }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(listOf(12, 11, 8, 14, 28, 14, 10, 14, 80), r1.toString()))
            put("xl/worksheets/sheet2.xml", sheet(listOf(12, 14, 14, 14, 14), r2.toString()))
        }
        return out.toByteArray()
    }
}
