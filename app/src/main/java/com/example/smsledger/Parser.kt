package com.example.smsledger

import java.time.LocalDate

data class Txn(
    val date: String, val type: String, val amount: Double, val merchant: String,
    val account: String, val category: String, val balance: Double?, val raw: String
)

object Parser {
    private val I = RegexOption.IGNORE_CASE
    private val CUR = Regex("""(?:rs\.?|inr|₹|usd|\$)\s*(\d[\d,]*(?:\.\d+)?)""", I)
    private val OTP = Regex("""\botp\b|one.time password""", I)
    private val PROMO = Regex("""offer|loan|apply|eligible|pre-?approved|congrat|click|reward points|expires|\bwin\b|enhanced|upgrade|cashback up to""", I)
    private val DEBIT = Regex("""debited|spent|withdrawn|paid|purchase|sent""", I)
    private val CREDIT = Regex("""credited|received|deposited|refund""", I)
    private val BAL = Regex("""\b(avl|available|bal|balance|lmt|limit)\b""", I)
    private val ACCT = Regex("""(?:a/c|acct?|account|card)\s*(?:no\.?)?\s*(?:ending|number)?\s*(?:xx+|x+|\*+)?\s*(\d{3,4})""", I)
    private const val NAME = """([A-Za-z0-9@._&\- ]{2,32}?)(?=\s+(?:on|ref|via|avl|bal|using|upi|card|a/c|\()|[.,;(]\s|[.,;]$|$)"""
    // Money going out is paid TO someone; money coming in arrives FROM someone.
    private val M_OUT = Regex("""\b(?:to vpa|to|at|towards|info[:-])\s+$NAME""", I)
    private val M_IN = Regex("""\b(?:from vpa|from|by|info[:-])\s+$NAME""", I)

    // Dates are strict on purpose: random numbers in a message must not be read as a date.
    private val DATE_NUM = Regex("""(?<!\d)(\d{1,2})[-/](\d{1,2})[-/](\d{4}|\d{2})(?!\d)""")
    private val DATE_MON = Regex("""(?<!\d)(\d{1,2})[-/ ]([A-Za-z]{3})[A-Za-z]*[-/ ,]+(\d{4}|\d{2})(?!\d)""")
    private val MONTHS = listOf("jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec")
    private const val MAX_AMOUNT = 10_000_000.0 // anything above 1 crore is treated as not a real alert

    private val CATS = listOf(
        "Food" to Regex("swiggy|zomato|restaurant|cafe|pizza|dominos|mcdonald|starbucks|kfc", I),
        "Groceries" to Regex("bigbasket|blinkit|zepto|dmart|grocery|instamart", I),
        "Transport" to Regex("uber|ola|rapido|irctc|metro|fuel|petrol|hpcl|bpcl|fastag|redbus", I),
        "Shopping" to Regex("amazon|flipkart|myntra|ajio|nykaa|meesho", I),
        "Bills" to Regex("electric|bescom|mahavitaran|airtel|jio|vodafone|recharge|broadband|insurance|bill", I),
        "Entertainment" to Regex("netflix|spotify|hotstar|bookmyshow|youtube|pvr|inox", I),
        "Health" to Regex("pharmacy|apollo|medplus|hospital|clinic|pharmeasy|1mg", I),
        "Cash" to Regex("atm|cash wdl|cash withdrawal", I),
        "Salary" to Regex("salary|payroll", I)
    )

    private fun num(s: String) = s.replace(",", "").toDouble()

    private fun build(d: String, mo: Int, y: String): LocalDate? = try {
        var yy = y.toInt(); if (yy < 100) yy += 2000
        LocalDate.of(yy, mo, d.toInt())
    } catch (e: Exception) { null }

    /** Uses a date from the text only if it is plausible; otherwise the day the SMS arrived. */
    private fun parseDate(t: String, fb: LocalDate): String {
        val c = mutableListOf<LocalDate?>()
        DATE_NUM.findAll(t).forEach { c.add(build(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3])) }
        DATE_MON.findAll(t).forEach {
            val mo = MONTHS.indexOf(it.groupValues[2].lowercase()) + 1
            if (mo > 0) c.add(build(it.groupValues[1], mo, it.groupValues[3]))
        }
        val ok = c.filterNotNull().firstOrNull { !it.isAfter(fb.plusDays(1)) && !it.isBefore(fb.minusDays(120)) }
        return (ok ?: fb).toString()
    }

    /** A message that cannot be parsed is skipped; it must never stop the whole import. */
    fun parse(sms: String, fallback: LocalDate = LocalDate.now()): Txn? =
        try { parseInner(sms, fallback) } catch (e: Exception) { null }

    private fun parseInner(sms: String, fallback: LocalDate): Txn? {
        val t = sms.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || OTP.containsMatchIn(t) || PROMO.containsMatchIn(t)) return null
        val cut = BAL.find(t)?.range?.first ?: -1
        val head = if (cut > 0) t.substring(0, cut) else t
        val tail = if (cut > 0) t.substring(cut) else ""
        val amt = (CUR.find(head) ?: CUR.find(t))?.groupValues?.get(1)?.let { num(it) } ?: return null
        if (amt <= 0 || amt > MAX_AMOUNT) return null
        val isDeb = DEBIT.containsMatchIn(t); val isCr = CREDIT.containsMatchIn(t)
        if (!isDeb && !isCr) return null
        val type = if (isCr && !isDeb) "credit" else "debit"
        var merchant = (if (type == "debit") M_OUT else M_IN).find(t)?.groupValues?.get(1)
            ?.replace(Regex("^(NEFT|IMPS|UPI|VPA)[-\\s]*", I), "")?.trim() ?: ""
        if (merchant.isEmpty() && Regex("atm", I).containsMatchIn(t)) merchant = "ATM withdrawal"
        var cat = CATS.firstOrNull { it.second.containsMatchIn("$merchant $t") }?.first ?: "Other"
        if (cat == "Other" && type == "debit" && Regex("upi|neft|imps|trf", I).containsMatchIn(t)) cat = "Transfer"
        return Txn(parseDate(t, fallback), type, amt, merchant.ifEmpty { "Unknown" },
            ACCT.find(t)?.groupValues?.get(1) ?: "", cat,
            CUR.find(tail)?.groupValues?.get(1)?.let { num(it) }, t)
    }
}
