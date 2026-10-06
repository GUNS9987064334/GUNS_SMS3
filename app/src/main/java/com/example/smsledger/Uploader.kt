package com.example.smsledger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Sends each transaction to a Google Sheet (through an Apps Script web app). Unsent items wait and retry. */
object Uploader {
    private val lock = Any()
    private val flushing = AtomicBoolean(false)

    private fun cfg(c: Context) = c.getSharedPreferences("cfg", 0)
    private fun url(c: Context) = cfg(c).getString("url", "")?.trim() ?: ""
    private fun token(c: Context) = cfg(c).getString("token", "")?.trim() ?: ""
    fun configured(c: Context) = url(c).startsWith("https://")

    private fun pending(c: Context) = try { JSONArray(cfg(c).getString("pending", "[]")) } catch (e: Exception) { JSONArray() }
    private fun savePending(c: Context, a: JSONArray) { cfg(c).edit().putString("pending", a.toString()).apply() }

    // Same message + date + time always gives the same id, so the sheet can ignore repeats.
    private fun id(t: Txn): String = MessageDigest.getInstance("SHA-256")
        .digest((t.raw + "|" + t.date + "|" + t.time).toByteArray())
        .joinToString("") { "%02x".format(it) }.take(20)

    // The original SMS text is NOT sent, only the transaction details.
    private fun payload(t: Txn) = JSONObject()
        .put("id", id(t)).put("date", t.date).put("time", Parser.pretty(t.time))
        .put("type", if (t.type == "debit") "Debit" else "Credit").put("amount", t.amount)
        .put("merchant", t.merchant).put("category", t.category)
        .put("account", if (t.account.isEmpty()) "" else "XX" + t.account)
        .put("balance", t.balance ?: "")

    fun enqueue(c: Context, items: List<Txn>) {
        if (!configured(c) || items.isEmpty()) return
        synchronized(lock) {
            val q = pending(c)
            items.forEach { q.put(payload(it)) }
            savePending(c, q)
        }
        flushAsync(c)
    }

    fun flushAsync(c: Context) {
        val app = c.applicationContext
        Thread { flush(app) }.start()
    }

    private fun flush(c: Context) {
        if (!configured(c) || !flushing.compareAndSet(false, true)) return
        try {
            while (true) {
                val item = synchronized(lock) { pending(c).let { if (it.length() == 0) null else it.getJSONObject(0) } } ?: break
                val res = post(c, item)
                cfg(c).edit().putString("last", describe(res)).apply()
                if (res != "ok" && res != "duplicate") break
                synchronized(lock) {
                    val q = pending(c); val rest = JSONArray()
                    for (i in 1 until q.length()) rest.put(q.get(i))
                    savePending(c, rest)
                }
            }
        } finally { flushing.set(false) }
    }

    private fun post(c: Context, body: JSONObject): String = try {
        val con = URL(url(c)).openConnection() as HttpURLConnection
        con.requestMethod = "POST"; con.doOutput = true
        con.connectTimeout = 15000; con.readTimeout = 25000
        con.setRequestProperty("Content-Type", "application/json")
        val data = JSONObject(body.toString()).put("token", token(c)).toString()
        con.outputStream.use { it.write(data.toByteArray()) }
        val code = con.responseCode
        val stream = if (code in 200..299) con.inputStream else con.errorStream
        val text = stream?.bufferedReader()?.readText()?.trim() ?: ""
        con.disconnect()
        if (text == "ok" || text == "duplicate" || text == "forbidden") text else "HTTP $code"
    } catch (e: Exception) { "No connection" }

    private fun describe(r: String) = when (r) {
        "ok", "duplicate" -> "sent"
        "forbidden" -> "wrong secret word"
        else -> r
    }

    /** Checks the URL and secret word. [done] is called with "ok", "forbidden" or an error text (on a background thread). */
    fun test(c: Context, done: (String) -> Unit) {
        val app = c.applicationContext
        Thread { done(post(app, JSONObject().put("action", "ping"))) }.start()
    }

    fun status(c: Context): String {
        val n = synchronized(lock) { pending(c).length() }
        val last = cfg(c).getString("last", "") ?: ""
        return "Waiting to send: $n" + (if (last.isNotEmpty()) "\nLast result: $last" else "")
    }
}
