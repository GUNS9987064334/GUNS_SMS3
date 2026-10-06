package com.example.smsledger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Stays running (with a small notification) and stores new transactions as soon as the SMS database changes. */
class SyncService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val syncNow = Runnable { Thread { Sync.inbox(applicationContext, 100) }.start() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(syncNow)
            handler.postDelayed(syncNow, 2000)
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("capture", "Transaction capture", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "capture")
            .setContentTitle("SMS Ledger is capturing transactions")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        try { contentResolver.registerContentObserver(Uri.parse("content://sms"), true, observer) } catch (e: Exception) {}
        handler.post(syncNow)
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onDestroy() {
        try { contentResolver.unregisterContentObserver(observer) } catch (e: Exception) {}
        super.onDestroy()
    }
}
