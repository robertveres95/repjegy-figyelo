package hu.repjegy.figyelo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import javax.swing.SwingUtilities

// ---------------------------------------------------------------- Tárolás (fájl)

/** A beállítások és figyelések egy fájlban: %APPDATA%\REFI\refi.properties */
object DesktopPrefs : Prefs {
    private val file: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".refi")
        File(base, "REFI").apply { mkdirs() }.resolve("refi.properties")
    }
    private val props = Properties()
    private var loaded = false
    /**
     * Ha a meglévő fájl nem olvasható be (pl. a vírusirtó épp zárolja induláskor), nem írjuk felül
     * az üres állapottal – különben minden figyelés és beállítás elveszne. Ilyenkor csak a memóriában
     * dolgozunk, a fájlról másolat készül, és a következő indítás újra megpróbálja.
     */
    private var readOnly = false

    @Synchronized
    private fun p(): Properties {
        if (!loaded) {
            if (file.exists()) {
                var ok = false
                for (attempt in 1..5) {
                    ok = runCatching {
                        val fresh = Properties()
                        file.reader(Charsets.UTF_8).use { fresh.load(it) }
                        props.clear()
                        props.putAll(fresh)
                    }.isSuccess
                    if (ok) break
                    if (attempt < 5) Thread.sleep(400L * attempt)
                }
                if (!ok) {
                    readOnly = true
                    runCatching { file.copyTo(File(file.parentFile, "refi.properties.olvashatatlan-${System.currentTimeMillis()}")) }
                }
            }
            loaded = true
        }
        return props
    }

    override fun getString(key: String, default: String?): String? = p().getProperty(key) ?: default
    override fun getBoolean(key: String, default: Boolean) = p().getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun getInt(key: String, default: Int) = p().getProperty(key)?.toIntOrNull() ?: default
    override fun getLong(key: String, default: Long) = p().getProperty(key)?.toLongOrNull() ?: default

    @Synchronized
    override fun edit(block: PrefsEditor.() -> Unit) {
        val props = p()
        object : PrefsEditor {
            override fun putString(key: String, value: String) { props.setProperty(key, value) }
            override fun putBoolean(key: String, value: Boolean) { props.setProperty(key, value.toString()) }
            override fun putInt(key: String, value: Int) { props.setProperty(key, value.toString()) }
            override fun putLong(key: String, value: Long) { props.setProperty(key, value.toString()) }
        }.block()
        if (readOnly) return
        // Előbb ideiglenes fájlba, aztán csere: áramszünetnél se sérüljön.
        // Ha a mappa nem írható (pl. teli lemez), az app ne omoljon össze: a változás
        // a memóriában megmarad, és a következő sikeres mentéskor kiíródik.
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writer(Charsets.UTF_8).use { props.store(it, "REFI") }
        } catch (_: Exception) {
            return
        }
        // Windowson a renameTo nem ír felül létező fájlt, ezért Files.move kell
        val src = tmp.toPath()
        val dst = file.toPath()
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            runCatching { Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    /** Az app adatmappája (%APPDATA%\REFI). */
    val dir: File get() = file.parentFile
}

// ---------------------------------------------------------------- Egyetlen példány

/**
 * Egyszerre csak egy REFI fusson (különben a bejelentkezéskori indítás és egy kézi
 * megnyitás két tálcaikont, két háttér-ellenőrzést és ütköző mentéseket adna).
 * A második példány jelez az elsőnek, hogy mutassa az ablakát, majd kilép.
 *
 * Frissítés után a régi verzió még futhat a tálcán: ha az újonnan indított példány
 * újabb buildből van, a régi kilép, és átadja a helyét (különben a régi verzió maradna
 * előtérben, a bezárhatatlan „új verzió” ablakkal).
 */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private val requestFile: File get() = File(DesktopPrefs.dir, "refi.show")

    class Request(val build: Int, val show: Boolean)

    fun acquire(): Boolean {
        return runCatching {
            val ch = FileChannel.open(
                File(DesktopPrefs.dir, "refi.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            )
            val l = try { ch.tryLock() } catch (e: Exception) { ch.close(); throw e }
            if (l == null) { ch.close(); false } else { channel = ch; lock = l; true }
        }.getOrDefault(true) // ha a zárolás nem működik, inkább induljon el
    }

    fun request(build: Int, show: Boolean) {
        runCatching { requestFile.writeText("$build;$show") }
    }

    /** Egy másik indítás kérése (és törli), vagy null. */
    fun consumeRequest(): Request? {
        val f = requestFile
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrDefault("")
        if (!f.delete()) return null
        val parts = text.split(';')
        return Request(parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0, parts.getOrNull(1)?.trim() != "false")
    }
}

// ---------------------------------------------------------------- Értesítés a tálcáról

object TrayNotifier {
    @Volatile
    var state: TrayState? = null

    fun send(title: String, message: String) {
        val s = state ?: return
        SwingUtilities.invokeLater {
            runCatching { s.sendNotification(Notification(title, message, Notification.Type.Info)) }
        }
    }
}

// ---------------------------------------------------------------- Csendes órák

/** Csendes órákban gyűjtött riasztások; a csendes idő végén egyben jelezzük őket. */
object QuietQueue {
    // Fájlba is mentjük: ha éjjel újraindul a gép (pl. Windows-frissítés), reggel se maradjon el az értesítés
    private val items: MutableList<String> by lazy {
        runCatching {
            val arr = org.json.JSONArray(Store.prefs.getString("quietQueue", "[]") ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toMutableList()
        }.getOrNull() ?: mutableListOf()
    }

    private fun save() {
        val arr = org.json.JSONArray()
        items.takeLast(50).forEach { arr.put(it) }
        runCatching { Store.prefs.edit { putString("quietQueue", arr.toString()) } }
    }

    @Synchronized
    fun add(title: String) {
        items += title
        save()
    }

    @Synchronized
    fun flushIfAwake() {
        if (items.isEmpty() || Store.settings.value.isQuiet()) return
        val text = items.takeLast(5).joinToString("\n") + if (items.size > 5) "\n… és még ${items.size - 5}" else ""
        TrayNotifier.send("Éjszaka ${items.size} ár esett a célár alá", text)
        items.clear()
        save()
    }
}

// ---------------------------------------------------------------- Háttér-ellenőrzés

/** Amíg az app fut (ablakban vagy a tálcán), a beállított gyakorisággal ellenőriz. */
object BackgroundLoop {
    private var job: Job? = null

    @Synchronized
    fun restart() {
        job?.cancel()
        job = AppScope.scope.launch {
            delay(30_000)
            while (isActive) {
                val hours = Store.settings.value.intervalHours
                val now = System.currentTimeMillis()
                // A jövőbeli időpont (pl. előreállt, majd visszaállított óra) se akassza meg az ellenőrzést
                val last = Store.prefs.getLong("lastAutoCheck", 0L).takeIf { it <= now } ?: 0L
                if (hours > 0 && now - last >= hours * 3_600_000L) {
                    runCatching { PriceChecker.checkAll() }
                    // Csak akkor számít lefutottnak, ha legalább egy forrás válaszolt: alvásból ébredés
                    // után gyakran még nincs net – ilyenkor 10 perc múlva újrapróbálja, nem csak órák múlva
                    val active = Store.watches.value.filter { !it.isExpired() }
                    val anyAnswer = active.isEmpty() || active.any { w ->
                        // A részleges válasz (talált ajánlatot, de nem minden kérése sikerült) is válasz
                        (w.lastChecked ?: 0L) >= now && w.sourceStatus.any { it.ok || it.text.contains("részleges") }
                    }
                    if (anyAnswer) Store.prefs.edit { putLong("lastAutoCheck", now) }
                }
                runCatching { Updater.dailyCheck() }
                runCatching { QuietQueue.flushIfAwake() }
                // A telefonon végzett módosítások átvétele, a friss árak feltöltése
                runCatching { Sync.syncNow() }
                delay(10 * 60_000L)
            }
        }
    }
}

// ---------------------------------------------------------------- Indítás a Windowszal

object Autostart {
    /** Bejelentkezéskor a tálcára indul (csak a telepített appnál). */
    fun enable() {
        val exe = System.getProperty("jpackage.app-path") ?: return
        if (!System.getProperty("os.name", "").startsWith("Windows")) return
        runCatching {
            ProcessBuilder(
                "reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                // A reg.exe-nek az idézőjeleket \"-ként kell átadni, különben szóközös útvonalnál
                // (pl. C:\Users\Kiss Anna\...) elvesznek, és az automatikus indítás nem működik
                "/v", "REFI", "/t", "REG_SZ", "/d", "\\\"$exe\\\" --tray", "/f",
            ).redirectErrorStream(true).start().waitFor()
        }
    }

    /** Automatikus indítás kikapcsolása: a bejegyzés törlése (ha nincs, nem hiba). */
    fun disable() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return
        runCatching {
            ProcessBuilder("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "REFI", "/f")
                .redirectErrorStream(true).start().waitFor()
        }
    }
}

// ---------------------------------------------------------------- Platform

object DesktopPlatform : PlatformApi {
    private val versionProps: Properties by lazy {
        Properties().apply {
            DesktopPlatform::class.java.getResourceAsStream("/refi-version.properties")?.use { load(it) }
        }
    }

    override val versionName: String get() = versionProps.getProperty("version", "1.0.0")
    override val buildNumber: Int get() = versionProps.getProperty("build", "1").toIntOrNull() ?: 1
    override val installerSuffix = ".msi"
    override val deviceWord = "számítógépen"
    override val updateSteps: String get() = if (canSelfUpdate) {
        "1. Kattints a gombra: a REFI letölti az új verziót, és elindítja a telepítést.\n" +
            "2. A telepítés magától lefut (egy kis folyamatjelző látszik). Utána nyisd meg újra a REFI-t."
    } else {
        "1. Kattints a gombra, a böngésző letölti az új verziót.\n" +
            "2. Nyisd meg a letöltött fájlt, és telepítsd (ha a Windows figyelmeztet, kattints a " +
            "„További információ”, majd a „Futtatás mindenképp” gombra)."
    }

    private val isWindows get() = System.getProperty("os.name", "").startsWith("Windows")

    override val canSelfUpdate: Boolean get() = isWindows && System.getProperty("jpackage.app-path") != null

    /**
     * Letölti az MSI-t a saját kiadási oldalunkról, és a Windows telepítőjével (msiexec, csak
     * folyamatjelzővel) elindítja; ez a példány kilép, hogy a telepítő cserélhesse a fájlokat.
     */
    override fun installUpdate(release: Updater.Release, progress: (Float) -> Unit): Boolean {
        if (!canSelfUpdate) return false
        if (!release.apkUrl.startsWith("https://github.com/robertveres95/repjegy-figyelo/releases/download/")) return false
        if (!release.apkUrl.endsWith(".msi")) return false
        val dir = File(System.getProperty("java.io.tmpdir"), "REFI-frissites").apply { mkdirs() }
        val msi = File(dir, "REFI-Setup-${release.version}-${release.build}.msi")
        val conn = java.net.URL(release.apkUrl).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (conn.responseCode !in 200..299) return false
        val total = conn.contentLengthLong
        var done = 0L
        conn.inputStream.use { input ->
            msi.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceAtMost(0.99f))
                }
            }
        }
        if (msi.length() < 1_000_000 || (total > 0 && msi.length() != total)) return false
        progress(1f)
        ProcessBuilder("msiexec", "/i", msi.absolutePath, "/passive", "/norestart").start()
        // A telepítő csak a kilépésünk után cserélheti a fájlokat
        Thread {
            Thread.sleep(1500)
            kotlin.system.exitProcess(0)
        }.start()
        return true
    }

    override val autostartSupported: Boolean get() = canSelfUpdate

    override var autostart: Boolean
        get() = DesktopPrefs.getBoolean("autostart", true)
        set(value) {
            DesktopPrefs.edit { putBoolean("autostart", value) }
            if (value) Autostart.enable() else Autostart.disable()
        }
    override val backgroundHint =
        "Ablak bezárásakor a REFI a tálcán fut tovább és onnan ellenőriz; kikapcsolt gépen nem figyel."

    override val appFont: FontFamily by lazy {
        FontFamily(
            Font("jakarta_regular.ttf", FontWeight.Normal),
            Font("jakarta_medium.ttf", FontWeight.Medium),
            Font("jakarta_semibold.ttf", FontWeight.SemiBold),
            Font("jakarta_bold.ttf", FontWeight.Bold),
        )
    }

    override fun openUrl(url: String) {
        // Csak https-weboldal: egy file: cím a Windowson programot indíthatna
        if (!isSafeWebUrl(url)) return
        val uri = URI(url.trim())
        val ok = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri); true
            } else false
        }.getOrDefault(false)
        if (!ok) runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString()).start() }
    }

    override fun openAsset(name: String): InputStream =
        DesktopPlatform::class.java.getResourceAsStream("/$name")
            ?: error("Hiányzó erőforrás: $name")

    override fun notifyPriceDrop(w: Watch, currency: String) {
        val best = w.bestOffer ?: return
        val text = buildString {
            append("Célár alatt (${formatPrice(w.targetPrice, currency)}).")
            best.outboundText()?.let { append(" Indulás: $it") }
            best.airline?.let { append(" · $it") }
        }
        val title = "${w.routeTitle}: ${formatPrice(best.price, currency)}"
        // Csendes órákban nem ugrik fel; a csendes idő végén összefoglalót küldünk
        if (Store.settings.value.isQuiet()) QuietQueue.add(title) else TrayNotifier.send(title, text)
    }

    override suspend fun googleAccessToken(interactive: Boolean): String? = GoogleAuthDesktop.token(interactive)
    override fun googleInvalidateToken() = GoogleAuthDesktop.invalidate()
    override fun googleCancelSignIn() = GoogleAuthDesktop.cancel()
    override fun googleSignOut() {
        Thread { GoogleAuthDesktop.signOut() }.start()
    }

    override val quietHint =
        "Ilyenkor nem ugrik fel értesítés; a csendes idő végén egy összefoglalót kapsz a közben esett árakról."

    override fun shareText(text: String): Boolean = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }.isSuccess

    override fun readClipboard(): String? = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun exportFile(suggestedName: String, content: String, onDone: (Boolean) -> Unit) {
        SwingUtilities.invokeLater {
            val ok = runCatching {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "REFI-mentés helye", java.awt.FileDialog.SAVE)
                dialog.file = suggestedName
                dialog.isVisible = true
                val name = dialog.file ?: return@runCatching false
                var f = File(dialog.directory, name)
                if (!f.name.endsWith(".json", ignoreCase = true)) f = File(f.parentFile, f.name + ".json")
                f.writeText(content, Charsets.UTF_8)
                true
            }.getOrDefault(false)
            onDone(ok)
        }
    }

    override fun importFile(onResult: (String?) -> Unit) {
        SwingUtilities.invokeLater {
            val text = runCatching {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "REFI-mentés megnyitása", java.awt.FileDialog.LOAD)
                dialog.setFilenameFilter { _, n -> n.endsWith(".json", ignoreCase = true) }
                dialog.isVisible = true
                val name = dialog.file ?: return@runCatching null
                val f = File(dialog.directory, name)
                require(f.length() <= 5L * 1024 * 1024) { "túl nagy fájl" }
                f.readText(Charsets.UTF_8)
            }.getOrNull()
            onResult(text)
        }
    }

    override fun notifyUpdate(release: Updater.Release) {
        TrayNotifier.send("Új verzió érhető el", "Megjelent a REFI ${release.version}. Nyisd meg az appot a frissítéshez.")
    }

    override fun reschedule() = BackgroundLoop.restart()

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        // Asztali gépen nincs „vissza” gomb; a felső sáv nyila szolgál erre.
    }

    @Composable
    override fun SystemBars(palette: AppPalette) {
    }

    @Composable
    override fun Splash(onFinished: () -> Unit) = DesktopSplash(onFinished)
}

// ---------------------------------------------------------------- Indítás

/**
 * Önteszt a kész, telepíthető appon (a GitHub Windows-gépén fut a build végén):
 * a beépített futtatókörnyezetben megvan-e minden, ami kell. Az eredményt fájlba írja.
 */
private fun selfTest(outFile: String): Int {
    val lines = mutableListOf<String>()
    var failed = 0
    fun check(name: String, block: () -> String) {
        val r = runCatching(block)
        if (r.isSuccess) lines += "OK   $name: ${r.getOrNull()}"
        else { failed++; lines += "FAIL $name: ${r.exceptionOrNull()}" }
    }
    Platform.current = DesktopPlatform
    check("verzió") {
        val v = DesktopPlatform.versionName
        require(DesktopPlatform::class.java.getResource("/refi-version.properties") != null) { "nincs refi-version.properties" }
        "$v (build ${DesktopPlatform.buildNumber})"
    }
    check("repülőterek") {
        val r = Airports.search("buda")
        require(r.isNotEmpty()) { "nincs találat" }
        r.first().title
    }
    check("betűtípus") {
        listOf("regular", "medium", "semibold", "bold").forEach {
            require(DesktopPlatform::class.java.getResource("/jakarta_$it.ttf") != null) { "hiányzik: $it" }
        }
        "4 vastagság megvan"
    }
    check("magyar dátum") {
        val s = java.time.LocalDate.of(2026, 10, 16).format(java.time.format.DateTimeFormatter.ofPattern("MMM d., EEEE", HU))
        require(s.contains("okt") && s.contains("péntek")) { "nem magyar: $s" }
        s
    }
    check("HTTPS") {
        val res = Http.request("https://api.github.com/repos/robertveres95/repjegy-figyelo", timeoutMs = 20_000)
        // Bármilyen HTTP-válasz azt jelenti, hogy a titkosított kapcsolat felépült
        // (a közös build-gépeken a GitHub néha 403-mal korlátoz, otthon ez nem fordul elő)
        require(res.code in 200..499) { "HTTP ${res.code}" }
        "kapcsolat rendben (HTTP ${res.code})"
    }
    check("3D nyitóanimáció képkockái") {
        var n = 0
        while (true) {
            val bytes = DesktopPlatform::class.java.getResourceAsStream("/splash3d/f%03d.webp".format(n))?.use { it.readBytes() } ?: break
            org.jetbrains.skia.Image.makeFromEncoded(bytes).use { require(it.width > 0) { "hibás kép: $n" } }
            n++
        }
        require(n >= 50) { "csak $n képkocka" }
        "$n képkocka betölthető"
    }
    check("3D-fájlok kihagyva") {
        require(DesktopPlatform::class.java.getResource("/splash/three.min.js") == null) { "a three.js bekerült" }
        "igen"
    }
    lines += if (failed == 0) "ÖSSZESEN: minden rendben" else "ÖSSZESEN: $failed hiba"
    File(outFile).writeText(lines.joinToString("\n"), Charsets.UTF_8)
    return failed
}

fun main(args: Array<String>) {
    args.firstOrNull { it.startsWith("--selftest=") }?.let {
        kotlin.system.exitProcess(selfTest(it.substringAfter("=")))
    }
    val startHidden = "--tray" in args
    if (!SingleInstance.acquire()) {
        // Már fut egy REFI: kézi indításnál az mutassa az ablakát. Ha mi vagyunk az újabb
        // verzió (frissítés után), a régi kilép, és mi indulunk el helyette.
        SingleInstance.request(DesktopPlatform.buildNumber, show = !startHidden)
        var got = false
        repeat(40) {
            if (!got) {
                Thread.sleep(250)
                got = SingleInstance.acquire()
            }
        }
        if (!got) kotlin.system.exitProcess(0)
    }
    SingleInstance.consumeRequest() // régi, beragadt kérés törlése
    Platform.current = DesktopPlatform
    Store.init(DesktopPrefs)
    AppScope.splashPending = !startHidden
    AppScope.scope.launch { runCatching { Airports.preload() } }
    if (DesktopPrefs.getBoolean("autostart", true)) Autostart.enable() else Autostart.disable()
    BackgroundLoop.restart()

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        var hintShown by remember { mutableStateOf(DesktopPrefs.getBoolean("trayHintShown", false)) }
        val trayState = rememberTrayState()
        LaunchedEffect(trayState) { TrayNotifier.state = trayState }
        @Suppress("DEPRECATION")
        val icon = painterResource("refi_icon.png")

        // Laptopon (pl. 1080p, 150%-os nagyítás) a 820 dp magasabb lehet a képernyőnél:
        // ilyenkor a címsor a képernyő fölé kerülne, és az ablak nem lenne mozgatható
        val usableHeight = remember {
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height
            }.getOrDefault(900)
        }
        val windowState = rememberWindowState(
            width = 460.dp,
            height = minOf(820, usableHeight - 24).coerceAtLeast(480).dp,
            position = WindowPosition(Alignment.Center),
        )
        // Előtérbe hozás: az ablak csak a következő újrarajzoláskor válik láthatóvá, ezért
        // a tényleges előrehozás az ablakon belül, egy számláló változására történik
        var showTick by remember { mutableStateOf(0) }
        fun show() {
            visible = true
            windowState.isMinimized = false
            showTick++
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(700)
                val req = SingleInstance.consumeRequest() ?: continue
                if (req.build > DesktopPlatform.buildNumber) {
                    // Újabb verzió indult el: átadjuk neki a helyet
                    kotlin.system.exitProcess(0)
                }
                if (req.show) show()
            }
        }

        Tray(
            state = trayState,
            icon = icon,
            tooltip = "REFI – repjegy figyelő",
            onAction = { show() },
            menu = {
                Item("Megnyitás", onClick = { show() })
                Item("Összes ellenőrzése most", onClick = { AppScope.scope.launch { PriceChecker.checkAll() } })
                Separator()
                Item("Kilépés", onClick = ::exitApplication)
            },
        )

        Window(
            onCloseRequest = {
                visible = false
                if (!hintShown) {
                    hintShown = true
                    DesktopPrefs.edit { putBoolean("trayHintShown", true) }
                    TrayNotifier.send(
                        "A REFI a tálcán fut tovább",
                        "Innen figyeli az árakat. Kilépni a tálcaikonra jobb gombbal kattintva lehet.",
                    )
                }
            },
            visible = visible,
            state = windowState,
            title = "REFI",
            icon = icon,
        ) {
            LaunchedEffect(showTick) {
                if (showTick > 0) {
                    delay(50)
                    // A Windows a háttérből nem engedi előtérbe hozni az ablakot; ez a szokásos kerülőút
                    window.toFront()
                    window.isAlwaysOnTop = true
                    window.isAlwaysOnTop = false
                    window.requestFocus()
                }
            }
            val settings by Store.settings.collectAsState()
            NeonTheme(mode = settings.themeMode, textScale = settings.textScale) {
                Surface(Modifier.fillMaxSize(), color = Neon.Black) {
                    AppRoot()
                }
            }
        }
    }
}
