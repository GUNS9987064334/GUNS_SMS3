package com.example.smsledger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var totals: TextView
    private var items = listOf<Txn>()

    private fun money(n: Double) = "₹" + String.format(Locale("en", "IN"), "%,.2f", n)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        totals = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad / 2) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(btn("Import inbox") { importInbox() })
        bar.addView(btn("Share CSV") { shareCsv() })
        bar.addView(btn("Clear") { confirmClear() })
        list = ListView(this).apply { emptyView = TextView(context).apply { text = "No transactions yet. New bank SMS appear here automatically, or tap Import inbox." } }
        root.addView(totals); root.addView(bar); root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (!has(Manifest.permission.RECEIVE_SMS) || !has(Manifest.permission.READ_SMS))
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    // Starts the background capture service (it shows a small ongoing notification).
    private fun startCapture() {
        if (has(Manifest.permission.READ_SMS))
            try { startForegroundService(Intent(this, SyncService::class.java)) } catch (e: Exception) {}
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        startCapture(); importInbox(true); refresh()
    }

    // Opening the app also syncs the newest messages, so nothing is missed if the phone blocked the background capture.
    override fun onResume() { super.onResume(); startCapture(); importInbox(true); refresh() }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        items = Store.all(this).sortedByDescending { it.date }
        val spent = items.filter { it.type == "debit" }.sumOf { it.amount }
        val got = items.filter { it.type == "credit" }.sumOf { it.amount }
        totals.text = "Spent ${money(spent)}\nReceived ${money(got)}"
        list.adapter = object : ArrayAdapter<Txn>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            override fun getView(i: Int, v: View?, p: ViewGroup): View {
                val row = super.getView(i, v, p)
                val t = items[i]
                val sign = if (t.type == "debit") "−" else "+"
                row.findViewById<TextView>(android.R.id.text1).apply {
                    text = "$sign${money(t.amount)}  ${t.merchant}"
                    setTextColor(if (t.type == "debit") Color.parseColor("#B3402F") else Color.parseColor("#1C7A58"))
                }
                row.findViewById<TextView>(android.R.id.text2).text =
                    listOf(t.date, t.category, if (t.account.isEmpty()) "" else "XX" + t.account).filter { it.isNotEmpty() }.joinToString("  •  ")
                return row
            }
        }
        list.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this).setMessage("Delete this transaction?")
                .setPositiveButton("Delete") { _, _ -> Store.remove(this, items[i]); refresh() }
                .setNegativeButton("Cancel", null).show()
            true
        }
    }

    private fun importInbox(silent: Boolean = false) {
        if (!has(Manifest.permission.READ_SMS)) {
            if (!silent) {
                Toast.makeText(this, "Allow SMS access first", Toast.LENGTH_SHORT).show()
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 1)
            }
            return
        }
        val found = mutableListOf<Txn>()
        try {
        contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("body", "date"), null, null, "date DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < (if (silent) 300 else 3000)) {
                val day = Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.systemDefault()).toLocalDate()
                Parser.parse(c.getString(0) ?: "", day)?.let { found.add(it) }
            }
        }
        } catch (e: Exception) {
            if (!silent) Toast.makeText(this, "Could not read SMS: " + e.javaClass.simpleName + " " + e.message, Toast.LENGTH_LONG).show()
            return
        }
        val added = Store.add(this, found, dedupe = true)
        if (!silent) Toast.makeText(this, "Imported $added new transactions", Toast.LENGTH_LONG).show()
        if (added > 0) refresh()
    }

    private fun shareCsv() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Transactions"); putExtra(Intent.EXTRA_TEXT, Store.csv(this@MainActivity))
        }, "Share transactions"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setMessage("Delete all transactions?")
            .setPositiveButton("Delete all") { _, _ -> Store.clear(this); refresh() }
            .setNegativeButton("Cancel", null).show()
    }
}
