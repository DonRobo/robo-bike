package dev.donrobo.ebike

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The app log behind [Repo.log]: the newest [KEPT] lines in memory (Diagnose screen), all of them in
 * `filesDir/log.txt` across process restarts for field debugging. [log] may be called from any thread, also the
 * GATT callback thread: the line is added in memory at once and appended to the file on the log's own thread.
 */
internal class AppLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Once per process (App.onCreate), before the first line that should reach the file. */
    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "log.txt")
        runCatching {
            if (f.length() > MAX_FILE_BYTES) f.writeText(f.readLines().takeLast(TRIMMED_LINES).joinToString("\n", postfix = "\n"))
            if (f.exists()) _lines.value = f.readLines().takeLast(KEPT)
        }
        file = f
    }

    fun log(msg: String) {
        android.util.Log.i("ebike", msg)
        val line = "${LocalTime.now().format(fmt)} $msg"
        _lines.update { (it + line).takeLast(KEPT) }
        val f = file ?: return
        // one thread, so lines reach the file in the order they were logged
        writer.execute { runCatching { f.appendText(line + "\n") } }
    }

    private companion object {
        const val KEPT = 300
        const val MAX_FILE_BYTES = 300_000L
        const val TRIMMED_LINES = 1500
    }
}
