package com.example.smsledger

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Keeps SMS_Transactions.xlsx in the phone's Downloads folder up to date. */
object Exporter {
    const val NAME = "SMS_Transactions.xlsx"
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    @Synchronized fun export(ctx: Context, list: List<Txn>): Uri? {
        try {
            val bytes = XlsxWriter.build(list)
            if (Build.VERSION.SDK_INT < 29) {
                val f = File(ctx.getExternalFilesDir(null), NAME)
                f.writeBytes(bytes)
                return Uri.fromFile(f)
            }
            val r = ctx.contentResolver
            val prefs = ctx.getSharedPreferences("export", 0)
            val saved = prefs.getString("uri", null)?.let { Uri.parse(it) }
            if (saved != null && tryWrite(r, saved, bytes)) return saved
            val found = findExisting(r)
            if (found != null && tryWrite(r, found, bytes)) {
                prefs.edit().putString("uri", found.toString()).apply(); return found
            }
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
            if (!tryWrite(r, uri, bytes)) return null
            prefs.edit().putString("uri", uri.toString()).apply()
            return uri
        } catch (e: Exception) {
            return null
        }
    }

    private fun tryWrite(r: ContentResolver, uri: Uri, b: ByteArray): Boolean = try {
        r.openOutputStream(uri, "wt")?.use { it.write(b); true } ?: false
    } catch (e: Exception) { false }

    private fun findExisting(r: ContentResolver): Uri? = try {
        r.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(NAME), null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
        }
    } catch (e: Exception) { null }
}
