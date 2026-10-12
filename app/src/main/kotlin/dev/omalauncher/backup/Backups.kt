package dev.omalauncher.backup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.omalauncher.BuildConfig
import dev.omalauncher.LauncherData
import dev.omalauncher.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Backs the home screen up to a file the user picks and restores it from one. A restore keeps
 * what it replaces in [undoFile] first, puts the backup's files and preferences in place, and
 * restarts the launcher, so every part of it starts from what was restored rather than
 * saving what it had in memory over it.
 */
internal class Backups(
    private val context: Context,
) {
    private val filesDir = context.filesDir
    private val staging = File(filesDir, ".restore")
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /** The home screen from before the last restore, private to the app. */
    private val undoFile = File(filesDir, "backups/before-restore.zip")

    /** When the last backup was written, 0 if never, and the file's name. */
    val lastBackup get() = prefs.getLong(LAST_AT, 0L) to prefs.getString(LAST_NAME, null)

    /** When the last restore happened, if it can be undone. */
    val undoAt get() = undoFile.takeIf { it.isFile }?.lastModified()

    /** What the home screen holds now. */
    fun current(): Summary = summarize(values("shortcuts"), values("themes"), File(filesDir, "widgets"))

    /** Writes a backup to [uri], one the system's file picker made. */
    suspend fun backUp(uri: Uri) =
        withContext(Dispatchers.IO) {
            // Some providers (cloud drives) refuse "wt"; the picker made a new, empty file anyway.
            val out =
                runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                    ?: context.contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("can't write there")
            out.use(::write)
            prefs.edit().putLong(LAST_AT, System.currentTimeMillis()).putString(LAST_NAME, displayName(uri)).apply()
        }

    /** Reads and checks the backup at [uri]; nothing changes until [restore]. */
    suspend fun read(uri: Uri): Staged =
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't read that file")
            input.use { readBackup(it, staging) }
        }

    /** Reads the home screen from before the last restore. */
    suspend fun readUndo(): Staged = withContext(Dispatchers.IO) { undoFile.inputStream().use { readBackup(it, staging) } }

    /** Drops a backup that was read but not restored. */
    fun discard() {
        staging.deleteRecursively()
    }

    /** Replaces the home screen with [staged]'s and restarts the launcher; doesn't return unless it fails. */
    suspend fun restore(staged: Staged) {
        withContext(Dispatchers.IO) {
            // What's replaced, first: if anything below fails, the user can still go back.
            undoFile.parentFile?.mkdirs()
            val tmp = File(undoFile.parentFile, ".${undoFile.name}.tmp")
            tmp.outputStream().use(::write)
            if (!tmp.renameTo(undoFile)) throw IOException("can't keep a copy of the home screen")
            applyFiles(staged, filesDir, LauncherData::deleteTree)
            for (part in BackupFormat.PREFS) {
                val values = staged.prefs[part.name] ?: continue
                val edit = prefsEdit(part, values)
                val editor = context.getSharedPreferences(part.name, Context.MODE_PRIVATE).edit()
                if (edit.clearAll) editor.clear()
                for (key in edit.remove) editor.remove(key)
                for ((key, value) in edit.put) put(editor, key, value)
                // Written now, not later: the process ends below.
                if (!editor.commit()) throw IOException("can't save ${part.name}")
            }
            staging.deleteRecursively()
        }
        Log.i(TAG, "restored a backup from ${staged.info.createdAt}; restarting")
        restart()
    }

    private fun write(out: java.io.OutputStream) {
        val info = BackupInfo(BackupFormat.VERSION, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), System.currentTimeMillis())
        writeBackup(out, info, BackupFormat.PREFS.associate { it.name to values(it.name) }, filesDir)
    }

    private fun values(name: String): Map<String, Any> =
        context
            .getSharedPreferences(name, Context.MODE_PRIVATE)
            .all
            .filterValues { it != null }
            .mapValues { it.value!! }

    private fun put(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any,
    ) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    /**
     * Starts home again in a fresh process. The launcher is the home app, so the system would
     * bring it back anyway; the restart intent makes that immediate.
     */
    private fun restart() {
        context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private companion object {
        const val TAG = "backup"
        const val LAST_AT = "last_at"
        const val LAST_NAME = "last_name"
    }
}
