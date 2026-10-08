package hu.repjegy.figyelo

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.ref.WeakReference

/**
 * Fájl mentése / megnyitása a rendszer fájlválasztójával (Storage Access Framework).
 * Az Activity regisztrálja a választókat; a közös kód a [export]/[import] hívásokkal használja.
 */
object FileBridge {
    private var activity: WeakReference<ComponentActivity>? = null
    private var createLauncher: ActivityResultLauncher<String>? = null
    private var openLauncher: ActivityResultLauncher<Array<String>>? = null

    private var pendingContent: String? = null
    private var pendingExport: ((Boolean) -> Unit)? = null
    private var pendingImport: ((String?) -> Unit)? = null

    /** Az Activity onDestroy-ából: ha még ez az Activity a gazdája, elengedjük. */
    fun unregister(a: ComponentActivity) {
        if (activity?.get() !== a) return
        activity = null
        createLauncher = null
        openLauncher = null
        pendingExport?.invoke(false)
        pendingImport?.invoke(null)
        pendingExport = null
        pendingImport = null
        pendingContent = null
    }

    /** Az Activity onCreate-jéből hívandó (a regisztrációnak a STARTED előtt kell megtörténnie). */
    fun register(a: ComponentActivity) {
        activity = WeakReference(a)
        createLauncher = a.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val content = pendingContent
            val done = pendingExport
            pendingContent = null
            pendingExport = null
            val ok = uri != null && content != null && runCatching {
                a.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            done?.invoke(ok)
        }
        openLauncher = a.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val done = pendingImport
            pendingImport = null
            val text = uri?.let {
                runCatching {
                    a.contentResolver.openInputStream(it)!!.use { s ->
                        // Legfeljebb 5 MB (egy mentésfájl ennél jóval kisebb)
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            require(out.size() <= 5 * 1024 * 1024) { "túl nagy fájl" }
                        }
                        String(out.toByteArray(), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            done?.invoke(text)
        }
    }

    fun export(name: String, content: String, onDone: (Boolean) -> Unit) {
        val launcher = createLauncher ?: return onDone(false)
        pendingContent = content
        pendingExport = onDone
        runCatching { launcher.launch(name) }.onFailure { pendingExport = null; onDone(false) }
    }

    fun import(onResult: (String?) -> Unit) {
        val launcher = openLauncher ?: return onResult(null)
        pendingImport = onResult
        runCatching { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            .onFailure { pendingImport = null; onResult(null) }
    }
}
