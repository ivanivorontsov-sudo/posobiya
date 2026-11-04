package org.ivanivorontsov.posobiya

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object Notify {
    const val CH_LAWS = "laws"; const val CH_END = "ending"
    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LAWS, "Новые законы и меры", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel(CH_END, "Окончание мер поддержки", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    fun show(ctx: Context, id: Int, ch: String, title: String, text: String, tab: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val i = Intent(ctx, MainActivity::class.java).putExtra("tab", tab).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<CheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Фоновая проверка: новые законы о соцподдержке + меры, срок которых подходит к концу. */
class CheckWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Settings(ctx).notify) return Result.success()
        Notify.channels(ctx)
        val prefs = ctx.getSharedPreferences("notified", Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val measures = Db.parse(ctx.assets.open("measures.json").bufferedReader().use { it.readText() })
        val milestones = listOf(90L, 30L, 7L, 1L)
        Expiry.ending(measures, today).forEach { m ->
            val left = Expiry.daysLeft(m, today)!!
            val ms = milestones.filter { left <= it }.minOrNull() ?: return@forEach
            val key = "${m.id}:$ms"
            if (!prefs.getBoolean(key, false)) {
                Notify.show(ctx, m.id.hashCode(), Notify.CH_END, "Заканчивается: ${m.title}", "${Expiry.label(m, today)}. ${m.endNote ?: ""}", 1)
                prefs.edit().putBoolean(key, true).apply()
            }
        }
        return try {
            val first = Laws.updated(ctx) == 0L
            val (fresh, _) = Laws.refresh(ctx)
            if (!first && fresh.isNotEmpty()) {
                val reg = fresh.count { it.regional }
                Notify.show(ctx, 7001, Notify.CH_LAWS, "Новые акты о соцподдержке: ${fresh.size}" + (if (reg > 0) " (Приморье: $reg)" else ""),
                    fresh.take(3).joinToString("\n") { "• " + it.name.take(140) }, 2)
            }
            Result.success()
        } catch (e: Exception) { Result.retry() }
    }
}
