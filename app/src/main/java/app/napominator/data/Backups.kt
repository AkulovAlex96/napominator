package app.napominator.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.napominator.App
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Автоматическая резервная копия JSON в выбранную папку (по одному файлу на день недели). */
object Backups {
    suspend fun run(ctx: Context): Boolean {
        val app = App.get(ctx)
        val tree = Uri.parse(app.prefs.backupTree ?: return false)
        val res = ctx.contentResolver
        val treeDoc = DocumentsContract.getTreeDocumentId(tree)
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeDoc)
        val name = "napominator-backup-${LocalDate.now().dayOfWeek.value}.json"
        val json = app.repo.exportJson()
        return try {
            res.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDoc), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name) DocumentsContract.deleteDocument(res, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
            }
            val doc = DocumentsContract.createDocument(res, dir, "application/json", name) ?: return false
            writeText(ctx, doc, json)
            app.prefs.lastBackup = System.currentTimeMillis()
            true
        } catch (e: Exception) { false }
    }

    fun schedule(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (enabled) wm.enqueueUniquePeriodicWork("backup", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build())
        else wm.cancelUniqueWork("backup")
    }
}

class BackupWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result = if (Backups.run(applicationContext)) Result.success() else Result.retry()
}
