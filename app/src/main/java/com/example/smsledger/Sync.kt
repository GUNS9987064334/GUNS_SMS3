package com.example.smsledger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.time.Instant
import java.time.ZoneId

object Sync {
    /** Reads the newest [limit] inbox messages and stores any new transactions. Returns how many were added. */
    fun inbox(ctx: Context, limit: Int): Int {
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return 0
        val found = mutableListOf<Txn>()
        try {
            ctx.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("body", "date"), null, null, "date DESC")?.use { c ->
                var n = 0
                while (c.moveToNext() && n++ < limit) {
                    val ms = c.getLong(1)
                    val day = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
                    Parser.parse(c.getString(0) ?: "", day, ms)?.let { found.add(it) }
                }
            }
        } catch (e: Exception) { return 0 }
        return Store.add(ctx, found, dedupe = true)
    }
}
