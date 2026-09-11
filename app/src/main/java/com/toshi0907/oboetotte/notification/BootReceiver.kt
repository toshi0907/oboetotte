package com.toshi0907.oboetotte.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.toshi0907.oboetotte.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val taskDao = AppDatabase.getInstance(context).taskDao()
                taskDao.getPendingWithDueDate().forEach { task -> ReminderScheduler.schedule(context, task) }
                taskDao.getPendingWithLocation().forEach { task -> LocationReminderManager.register(context, task) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
