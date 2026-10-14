package com.example.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.example.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupHelper {
    private const val DB_NAME = "electricity_billing_db"
    private const val SQLITE_HEADER = "SQLite format 3"

    suspend fun backupDatabase(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.isFile || dbFile.length() == 0L) return@withContext false
            checkpointDatabase(context)
            val output = context.contentResolver.openOutputStream(uri) ?: return@withContext false
            output.use { stream ->
                ZipOutputStream(stream).use { zip ->
                    databaseFiles(context).filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createBackupFile(context: Context, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.isFile || dbFile.length() == 0L) return@withContext false
            checkpointDatabase(context)
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { output ->
                ZipOutputStream(output).use { zip ->
                    databaseFiles(context).filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            targetFile.isFile && targetFile.length() > 0L
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createBackupFileForSharing(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val file = File(context.cacheDir, "electricity_billing_backup_${System.currentTimeMillis()}.zip")
            if (createBackupFile(context, file)) file else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Supports current ZIP backups, older ZIPs containing a SQLite database under a different
     * common filename, and a raw SQLite database file. Never replaces the live DB until the
     * incoming database passes SQLite integrity_check.
     */
    suspend fun restoreDatabase(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "db_restore_temp")
        try {
            if (tempDir.exists()) tempDir.deleteRecursively()
            if (!tempDir.mkdirs()) return@withContext false

            val source = context.contentResolver.openInputStream(uri) ?: return@withContext false
            val buffered = BufferedInputStream(source)
            buffered.mark(32)
            val signature = ByteArray(4)
            val count = buffered.read(signature)
            buffered.reset()

            val incomingDb: File
            if (count == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()) {
                ZipInputStream(buffered).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            // Ignore any directories in the archive path to avoid ZIP path traversal.
                            val safeName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                            if (safeName.isNotBlank()) {
                                FileOutputStream(File(tempDir, safeName)).use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val files = tempDir.listFiles()?.filter { it.isFile }.orEmpty()
                incomingDb = files.firstOrNull {
                    it.name.equals(DB_NAME, true) ||
                        it.name.equals("$DB_NAME.db", true) ||
                        it.name.equals("database.db", true) ||
                        it.name.equals("billing.db", true)
                } ?: files.firstOrNull { hasSqliteHeader(it) }
                    ?: return@withContext false
            } else {
                val rawDb = File(tempDir, DB_NAME)
                buffered.use { it.copyTo(FileOutputStream(rawDb)) }
                incomingDb = rawDb
            }

            if (!isValidDatabase(incomingDb)) return@withContext false

            val dbFile = context.getDatabasePath(DB_NAME)
            val parent = dbFile.parentFile ?: return@withContext false
            if (!parent.exists() && !parent.mkdirs()) return@withContext false
            val safetyCopy = File(tempDir, "current_database_before_restore")
            if (dbFile.isFile) dbFile.copyTo(safetyCopy, overwrite = true)

            AppDatabase.closeForRestore()
            File(parent, "$DB_NAME-wal").delete()
            File(parent, "$DB_NAME-shm").delete()

            try {
                incomingDb.copyTo(dbFile, overwrite = true)
                if (!isValidDatabase(dbFile)) throw IllegalStateException("Restored database failed integrity check")
                true
            } catch (e: Exception) {
                e.printStackTrace()
                dbFile.delete()
                if (safetyCopy.isFile) safetyCopy.copyTo(dbFile, overwrite = true)
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun databaseFiles(context: Context): List<File> {
        val dbFile = context.getDatabasePath(DB_NAME)
        return listOf(dbFile, File(dbFile.parentFile, "$DB_NAME-wal"), File(dbFile.parentFile, "$DB_NAME-shm"))
    }

    private fun checkpointDatabase(context: Context) {
        try {
            AppDatabase.getDatabase(context).openHelper.writableDatabase
                .execSQL("PRAGMA wal_checkpoint(FULL)")
        } catch (e: Exception) {
            // Keep backup available even if checkpointing is unavailable.
            e.printStackTrace()
        }
    }

    private fun hasSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.length) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(SQLITE_HEADER.length)
                input.read(header) == header.size && String(header, Charsets.US_ASCII) == SQLITE_HEADER
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidDatabase(file: File): Boolean {
        if (!hasSqliteHeader(file)) return false
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            database?.close()
        }
    }
}
