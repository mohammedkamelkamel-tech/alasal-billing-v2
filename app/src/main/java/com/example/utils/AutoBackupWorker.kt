package com.example.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** نسخ احتياطي يومي إلى الهاتف، ويمكن أيضاً إلى مجلد يختاره المستخدم داخل Google Drive. */
class AutoBackupWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val date = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val dir = File(context.filesDir, "daily_backups").apply { mkdirs() }
        val localFile = File(dir, "electricity_billing_backup_$date.zip")
        val ok = BackupHelper.createBackupFile(context, localFile)
        if (!ok) return Result.retry()

        // الاحتفاظ بآخر 30 نسخة على الهاتف.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }

        val driveUri = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("backup_drive_tree_uri", null)
        if (!driveUri.isNullOrBlank()) {
            try {
                uploadToDriveFolder(context, Uri.parse(driveUri), localFile)
            } catch (_: Exception) {
                // لا نفشل النسخة المحلية إذا تعذر الوصول إلى Drive مؤقتاً.
            }
        }
        return Result.success()
    }

    private fun uploadToDriveFolder(context: Context, treeUri: Uri, localFile: File) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
        val existing = root.findFile(localFile.name)
        existing?.delete()
        val target = root.createFile("application/zip", localFile.name) ?: return
        context.contentResolver.openOutputStream(target.uri)?.use { output ->
            FileInputStream(localFile).use { input -> input.copyTo(output) }
        }
    }

    companion object {
        private const val UNIQUE_NAME = "daily_electricity_billing_backup"

        fun schedule(context: Context) {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("backup_auto_enabled", true)) return

            val now = Calendar.getInstance()
            val next = now.clone() as Calendar
            next.set(Calendar.HOUR_OF_DAY, 2)
            next.set(Calendar.MINUTE, 0)
            next.set(Calendar.SECOND, 0)
            next.set(Calendar.MILLISECOND, 0)
            if (next.timeInMillis <= now.timeInMillis) next.add(Calendar.DAY_OF_YEAR, 1)
            val initialDelay = next.timeInMillis - now.timeInMillis

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
