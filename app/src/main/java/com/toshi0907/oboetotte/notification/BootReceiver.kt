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
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onBootCompleted(context)
            // アプリ更新直後は、アプリを開くまで連続追跡サービスが動かず位置情報リマインダーが
            // 止まったままになるため、ここで再開する(旧バージョンで登録したジオフェンスの一括解除も
            // reconcileAll内で行われる)。期限日時のアラームは更新を越えて保持されるため触らない。
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        LocationReminderManager.reconcileAll(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    private fun onBootCompleted(context: Context) {
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
