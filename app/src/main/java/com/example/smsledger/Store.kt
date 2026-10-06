package com.example.smsledger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("data", 0)

    @Synchronized fun all(c: Context): MutableList<Txn> {
        val a = JSONArray(prefs(c).getString("txns", "[]"))
        val l = mutableListOf<Txn>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            l.add(Txn(o.getString("date"), o.getString("type"), o.getDouble("amount"), o.getString("merchant"),
                o.getString("account"), o.getString("category"),
                if (o.isNull("balance")) null else o.getDouble("balance"), o.getString("raw")))
        }
        return l
    }

    @Synchronized private fun write(c: Context, l: List<Txn>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("date", it.date).put("type", it.type).put("amount", it.amount)
                .put("merchant", it.merchant).put("account", it.account).put("category", it.category)
                .put("balance", it.balance ?: JSONObject.NULL).put("raw", it.raw))
        }
        prefs(c).edit().putString("txns", a.toString()).apply()
        Exporter.export(c, l) // keep the Excel file in step with every change
    }

    /** dedupe = true skips messages already stored (used when importing the inbox). */
    @Synchronized fun add(c: Context, items: List<Txn>, dedupe: Boolean): Int {
        val l = all(c)
        val seen = l.map { it.raw }.toHashSet()
        var n = 0
        for (t in items) if (!dedupe || seen.add(t.raw)) { l.add(t); n++ }
        if (n > 0) write(c, l)
        return n
    }

    @Synchronized fun remove(c: Context, t: Txn) {
        val l = all(c)
        val i = l.indexOfFirst { it.raw == t.raw && it.date == t.date && it.amount == t.amount }
        if (i >= 0) { l.removeAt(i); write(c, l) }
    }

    @Synchronized fun clear(c: Context) = write(c, emptyList())

    fun csv(c: Context): String {
        fun q(s: Any?) = "\"" + (s?.toString() ?: "").replace("\"", "\"\"") + "\""
        val rows = all(c).sortedByDescending { it.date }.map {
            listOf(it.date, it.type, it.amount, it.merchant, it.category, it.account, it.balance, it.raw).joinToString(",") { v -> q(v) }
        }
        return (listOf("Date,Type,Amount,Merchant,Category,Account,Balance,Original SMS") + rows).joinToString("\n")
    }
}
