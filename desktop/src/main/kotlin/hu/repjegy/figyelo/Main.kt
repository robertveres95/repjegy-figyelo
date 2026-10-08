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

    @Synchronized
    private fun p(): Properties {
        if (!loaded) {
            if (file.exists()) runCatching { file.reader(Charsets.UTF_8).use { props.load(it) } }
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
        // Előbb ideiglenes fájlba, aztán csere: áramszünetnél se sérüljön
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writer(Charsets.UTF_8).use { props.store(it, "REFI") }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
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
                val last = Store.prefs.getLong("lastAutoCheck", 0L)
                if (hours > 0 && now - last >= hours * 3_600_000L) {
                    Store.prefs.edit { putLong("lastAutoCheck", now) }
                    runCatching { PriceChecker.checkAll() }
                }
                runCatching { Updater.dailyCheck() }
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
                "/v", "REFI", "/t", "REG_SZ", "/d", "\"$exe\" --tray", "/f",
            ).redirectErrorStream(true).start().waitFor()
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
    override val updateSteps =
        "1. Kattints a gombra, a böngésző letölti az új verziót.\n" +
            "2. Nyisd meg a letöltött fájlt, és telepítsd (ha a Windows figyelmeztet, kattints a " +
            "„További információ”, majd a „Futtatás mindenképp” gombra)."
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
        if (url.isBlank()) return
        val ok = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url)); true
            } else false
        }.getOrDefault(false)
        if (!ok) runCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }
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
        TrayNotifier.send("${w.routeTitle}: ${formatPrice(best.price, currency)}", text)
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
    Platform.current = DesktopPlatform
    Store.init(DesktopPrefs)
    AppScope.splashPending = !startHidden
    AppScope.scope.launch { runCatching { Airports.preload() } }
    Autostart.enable()
    BackgroundLoop.restart()

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        var hintShown by remember { mutableStateOf(DesktopPrefs.getBoolean("trayHintShown", false)) }
        val trayState = rememberTrayState()
        LaunchedEffect(trayState) { TrayNotifier.state = trayState }
        @Suppress("DEPRECATION")
        val icon = painterResource("refi_icon.png")

        Tray(
            state = trayState,
            icon = icon,
            tooltip = "REFI – repjegy figyelő",
            onAction = { visible = true },
            menu = {
                Item("Megnyitás", onClick = { visible = true })
                Item("Összes ellenőrzése most", onClick = { AppScope.scope.launch { PriceChecker.checkAll() } })
                Separator()
                Item("Kilépés", onClick = ::exitApplication)
            },
        )

        val windowState = rememberWindowState(
            width = 460.dp,
            height = 820.dp,
            position = WindowPosition(Alignment.Center),
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
            val settings by Store.settings.collectAsState()
            NeonTheme(mode = settings.themeMode, textScale = settings.textScale) {
                Surface(Modifier.fillMaxSize(), color = Neon.Black) {
                    AppRoot()
                }
            }
        }
    }
}
