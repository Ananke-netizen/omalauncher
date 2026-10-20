package dev.omalauncher.drawer

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import dev.omalauncher.widgets.runtime.watchFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The drawer's folders, read from [DrawerFoldersFile] beside style.toml and followed as it changes. Main thread. */
class DrawerFolderStore(
    context: Context,
) {
    private val file = File(LauncherData.styleDir(context), DrawerFoldersFile.NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    var folders by mutableStateOf(emptyList<DrawerFolder>())
        private set

    fun start() {
        if (watching != null) return
        watching =
            scope.launch {
                watchFile(file).collect { text ->
                    folders = runCatching { DrawerFoldersFile.parse(text) }.getOrElse {
                        Log.w(TAG, "drawer.txt unreadable", it)
                        emptyList()
                    }
                }
            }
    }

    /** Moves [pkg] into the folder named [to], or out of every folder when null, and saves. */
    fun move(
        pkg: String,
        to: String?,
    ) {
        val next = DrawerFoldersFile.move(folders, pkg, to)
        folders = next
        val text = DrawerFoldersFile.format(next)
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, ".${file.name}.tmp")
                    tmp.writeText(text)
                    tmp.renameTo(file)
                }.onFailure { Log.w(TAG, "drawer.txt not saved", it) }
            }
        }
    }

    private companion object {
        const val TAG = "DrawerFolders"
    }
}
