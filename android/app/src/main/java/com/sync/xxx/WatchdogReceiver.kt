package com.sync.xxx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val svc = Intent(context, DeviceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, svc)
            } else {
                context.startService(svc)
            }
            context.sendBroadcast(Intent(DeviceService.ACTION_CONNECT).apply {
                setPackage(context.packageName)
            })
        } catch (_: Exception) {}
    }
}