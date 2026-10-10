@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package hu.repjegy.figyelo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import kotlin.math.roundToInt
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: String?, val template: Watch? = null, val back: Screen = Home) : Screen
    data object Options : Screen
    data object Explore : Screen
}

private val dateFormat get() = DateTimeFormatter.ofPattern(tr("yyyy. MMM d., EEE", "d MMM yyyy, EEE", "EEE, d. MMM yyyy"), Lang.locale)
/** Begépelhető dátum: magyarul 2026.10.16, angolul és németül 2026-10-16 (mindkettőt elfogadjuk). */
private val typedDateFormat: DateTimeFormatter
    get() = DateTimeFormatter.ofPattern(if (Lang.hu) "yyyy.MM.dd" else "yyyy-MM-dd")

/** A legkésőbbi megadható utazási nap (a légitársaságok kb. egy évre előre árulnak). */
/** A kártyák kezdetben kinyitva (csak a képernyőkép-teszthez). */
internal var expandCardsInitially = false

internal fun maxTravelDate(today: LocalDate): LocalDate = today.plusMonths(12)

/** A „Rugalmasság” lista „minden héten” eleme. */
private const val WEEKLY_CHOICE = 7

/** Hány hét fér bele a „minden héten” tartományba (az elsőt is beleértve). */
internal fun weeklyCount(first: LocalDate, until: LocalDate): Int =
    if (until.isBefore(first)) 0 else (java.time.temporal.ChronoUnit.DAYS.between(first, until) / 7 + 1).toInt().coerceAtMost(MAX_WEEKS)

/** Begépelt dátum: 2026.10.16, 2026-10-16, 2026/10/16, 2026.10.16. vagy 2026. 10. 16. */
internal fun parseTypedDate(raw: String): LocalDate? {
    val t = raw.trim()
    // 20261016 (elválasztó nélkül) is jó
    if (t.length == 8 && t.all { it.isDigit() }) {
        return runCatching { LocalDate.of(t.take(4).toInt(), t.substring(4, 6).toInt(), t.takeLast(2).toInt()) }.getOrNull()
    }
    val nums = t.split('.', '-', '/', ' ').filter { it.isNotBlank() }
    if (nums.size != 3 || nums[0].length != 4) return null
    return runCatching { LocalDate.of(nums[0].toInt(), nums[1].toInt(), nums[2].toInt()) }.getOrNull()
}
/** A Windows-indítás kapcsolásai sorban fussanak (gyors ki-be kapcsolásnál se cserélődjenek fel). */
private val autostartLock = kotlinx.coroutines.sync.Mutex()
private val shortDate get() = DateTimeFormatter.ofPattern(tr("MMM d.", "d MMM", "d. MMM"), Lang.locale)
private val timeFormat get() = DateTimeFormatter.ofPattern(tr("MMM d. HH:mm", "d MMM HH:mm", "d. MMM HH:mm"), Lang.locale)

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val update by Updater.available.collectAsState()
    var showSplash by remember { mutableStateOf(AppScope.splashPending) }
    LaunchedEffect(Unit) { AppScope.scope.launch { Updater.check() } }
    // Szinkronizálás induláskor és minden visszatéréskor (a másik eszköz módosításai)
    val resumes by AppScope.resumeCount.collectAsState()
    LaunchedEffect(resumes) { AppScope.scope.launch { Sync.syncNow() } }
    val sync by Sync.state.collectAsState()
    // Ha a bejelentkezés nem sikerül (pl. nincs net, nincs Google Play), egy hiba után erre az
    // indításra tovább lehet lépni – a figyelések addig is használhatók; a következő indításkor újra kéri
    var loginSkipped by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val loginGate = !showSplash && !sync.enabled && !loginSkipped
    // A kötelező frissítés csak a főoldalon jön elő: egy félig kitöltött figyelést nem takar le
    // (a háttérben érkező frissítéskeresés közben épp szerkeszthet)
    val showUpdate = update != null && !showSplash && screen == Screen.Home
    // Új verzió első megnyitásakor: mi változott (a bejelentkezés után, frissítési kérés nélkül)
    // Nyelvváltáskor az új nyelven (a szövegek a lekéréskor fordítódnak)
    val whatsNew = remember(Lang.code) { runCatching { WhatsNew.pending() }.getOrDefault(emptyList()) }
    var whatsNewOpen by remember { mutableStateOf(whatsNew.isNotEmpty()) }
    val showWhatsNew = whatsNewOpen && !showSplash && !loginGate && !showUpdate
    // Ami alatta van, csak akkor reagálhat (pl. megosztott kód), ha semmi sem takarja
    val uncovered = !showSplash && !loginGate && !showUpdate && !showWhatsNew

    // A Vissza gomb a látható képernyőn dolgozzon, ne a letakart alatta lévőn
    Platform.current.BackHandler(enabled = screen != Screen.Home && uncovered) {
        screen = (screen as? Screen.Edit)?.back ?: Screen.Home
    }

    Box(Modifier.fillMaxSize().background(Neon.Black)) {
        AnimatedContent(
            targetState = screen,
            // Letakarva a képernyőolvasó (TalkBack) se érje el az alatta lévő gombokat
            modifier = if (uncovered) Modifier else Modifier.clearAndSetSemantics { },
            transitionSpec = {
                val forward = targetState != Screen.Home
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it * dir / 3 } + fadeIn(tween(320)))
                    .togetherWith(
                        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it * dir / 3 } + fadeOut(tween(200))
                    )
            },
            label = "screens",
        ) { s ->
            when (s) {
                Screen.Home -> HomeScreen(
                    onAdd = { screen = Screen.Edit(null) },
                    onEdit = { screen = Screen.Edit(it) },
                    onSettings = { screen = Screen.Options },
                    onExplore = { screen = Screen.Explore },
                    acceptIncoming = uncovered,
                )
                is Screen.Edit -> EditScreen(s.id, s.template, onDone = { screen = s.back })
                Screen.Options -> SettingsScreen(onDone = { screen = Screen.Home })
                Screen.Explore -> DiscoverScreen(
                    onBack = { screen = Screen.Home },
                    onPick = { template -> screen = Screen.Edit(null, template, back = Screen.Explore) },
                )
            }
        }

        // A repülő után kötelező a Google-bejelentkezés (adattárolás és szinkronizálás)
        AnimatedVisibility(visible = loginGate, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
            LoginGate(onSkip = { loginSkipped = true })
        }

        AnimatedVisibility(visible = showWhatsNew, enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
            WhatsNewOverlay(whatsNew) {
                if (whatsNewOpen) WhatsNew.markSeen()
                whatsNewOpen = false
            }
        }

        // A kötelező frissítés mindennél előrébb való (régi verzióval a bejelentkezés sem biztos, hogy működik)
        AnimatedVisibility(visible = showUpdate, enter = fadeIn(tween(400)), exit = fadeOut()) {
            update?.let { UpdateOverlay(it) }
        }

        if (showSplash) {
            Platform.current.Splash(onFinished = {
                AppScope.splashPending = false
                showSplash = false
            })
        }
    }
}

/** Kötelező frissítés: amíg nincs telepítve az új verzió, ez takarja az appot. */
@Composable
private fun UpdateOverlay(release: Updater.Release) {
    Platform.current.BackHandler(enabled = true) { }
    val transition = rememberInfiniteTransition(label = "update")
    // Ugyanaz az ütem, mint a keret lüktetése (NeonCard: 1100 ms), így együtt pulzálnak
    val pulse by transition.animateFloat(
        0.95f, 1.05f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p",
    )
    CenteredScroll(
        Modifier
            .fillMaxSize()
            .blockInput()
            .background(Neon.Black.copy(alpha = 0.96f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
    ) {
        NeonCard(pulse = true, modifier = Modifier.fillMaxWidth().enterAnimation()) {
            Text(
                tr("ÚJ VERZIÓ", "NEW VERSION", "NEUE VERSION"),
                style = MaterialTheme.typography.headlineMedium.glow(),
                color = Neon.Green,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().scale(pulse),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                tr(
                    "Megjelent a REFI ${release.version}.\n" +
                        "A használathoz frissítened kell.",
                    "REFI ${release.version} is out.\n" +
                        "Please update to keep using the app.",
                    "REFI ${release.version} ist da.\n" +
                        "Bitte aktualisiere, um die App weiter zu nutzen.",
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                Platform.current.updateSteps,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                tr("😎Nyugi a figyeléseid megmaradnak😎", "😎Relax, your watches stay safe😎", "😎Keine Sorge, deine Beobachtungen bleiben erhalten😎"),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            var progress by remember { mutableStateOf<Float?>(null) }
            Button(
                onClick = {
                    if (!Platform.current.canSelfUpdate) {
                        Platform.current.openUrl(release.apkUrl)
                        return@Button
                    }
                    // Windowson az app maga tölti le és indítja a telepítőt (a tálcán futó példány kilép)
                    progress = 0f
                    AppScope.scope.launch {
                        val ok = runCatching {
                            Platform.current.installUpdate(release) { p -> progress = p }
                        }.getOrDefault(false)
                        if (!ok) {
                            progress = null
                            Platform.current.openUrl(release.apkUrl)
                        }
                    }
                },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    progress?.let { p -> if (p >= 1f) tr("Telepítés indul…", "Starting install…", "Installation startet…") else tr("Letöltés… ${(p * 100).toInt()}%", "Downloading… ${(p * 100).toInt()}%", "Herunterladen… ${(p * 100).toInt()}%") }
                        ?: tr("Letöltés és frissítés", "Download and update", "Herunterladen und aktualisieren"),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Fekete, átlátszó felső sáv izzó, monospace címmel. */
@Composable
internal fun NeonTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.glow(radius = 22f),
                color = Neon.Green,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Vissza", "Back", "Zurück"), tint = Neon.Green)
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Neon.Black,
            scrolledContainerColor = Neon.Black,
            actionIconContentColor = Neon.Green,
            navigationIconContentColor = Neon.Green,
        ),
    )
}

// ---------------------------------------------------------------- Főképernyő

@Composable
internal fun HomeScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onExplore: () -> Unit = {},
    acceptIncoming: Boolean = true,
) {
    val watches by Store.watches.collectAsState()
    val settings by Store.settings.collectAsState()
    val checking by Store.checking.collectAsState()
    // Rövid visszajelzés (pl. „Vágólapra másolva”, „3 figyelés visszaállítva”)
    var toast by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(toast) {
        if (toast != null) {
            // Az üzenet a lista tetején jelenik meg: odagörgetünk, hogy lejjebb görgetve is látszódjon
            listState.animateScrollToItem(0)
            kotlinx.coroutines.delay(4500)
            toast = null
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var codeDialog by remember { mutableStateOf<String?>(null) }
    // Más appból megosztott szöveg (pl. a néni által küldött REFI-kód)
    val incoming by AppScope.incomingText.collectAsState()
    LaunchedEffect(incoming, acceptIncoming) {
        if (acceptIncoming) incoming?.let {
            codeDialog = it
            AppScope.incomingText.value = null
        }
    }
    codeDialog?.let { initial ->
        ImportCodeDialog(
            initial = initial,
            onDismiss = { codeDialog = null },
            onImported = { msg ->
                codeDialog = null
                toast = msg
            },
        )
    }
    // A rendszerben letiltott értesítés esetén a csengők hiába „bekapcsoltak”: figyelmeztetünk.
    // Minden visszatéréskor újranézzük (a rendszerbeállításokban visszakapcsolhatja).
    var notifyBlocked by remember { mutableStateOf(false) }
    val resumes by AppScope.resumeCount.collectAsState()
    LaunchedEffect(resumes) {
        notifyBlocked = runCatching { Platform.current.notificationsBlocked() }.getOrDefault(false)
    }

    Scaffold(
        containerColor = Neon.Black,
        topBar = {
            val spin = rememberInfiniteTransition(label = "spin")
            val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
            NeonTopBar(
                title = "REFI",
                actions = {
                    IconButton(
                        onClick = { AppScope.scope.launch { PriceChecker.checkAll() } },
                        enabled = checking.isEmpty() && watches.isNotEmpty() && settings.isReady,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = tr("Összes ellenőrzése", "Check all", "Alle prüfen"),
                            modifier = if (checking.isNotEmpty()) Modifier.rotate(angle) else Modifier,
                        )
                    }
                    IconButton(onClick = onExplore) {
                        Icon(Icons.Filled.Search, contentDescription = tr("Felfedezés: hova repülhetek olcsón?", "Discover: where can I fly cheaply?", "Entdecken: Wohin kann ich günstig fliegen?"))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = tr("Beállítások", "Settings", "Einstellungen"))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = tr("Továbbiak", "More", "Mehr"))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Felfedezés – hova repülhetek olcsón?", "Discover – where can I fly cheaply?", "Entdecken – wohin kann ich günstig fliegen?")) },
                                onClick = { menuOpen = false; onExplore() },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Kód beillesztése", "Paste code", "Code einfügen")) },
                                onClick = {
                                    menuOpen = false
                                    codeDialog = Platform.current.readClipboard()?.takeIf { it.contains("REFI1:") } ?: ""
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Mentés fájlba", "Save to file", "In Datei speichern")) },
                                onClick = {
                                    menuOpen = false
                                    val content = Backup.export(Store.watches.value, Store.settings.value)
                                    Platform.current.exportFile(Backup.fileName(), content) { ok ->
                                        toast = if (ok) tr("Mentve: ${Store.watches.value.size} figyelés.", "Saved: ${Store.watches.value.size} ${if (Store.watches.value.size == 1) "watch" else "watches"}.", "Gespeichert: ${Store.watches.value.size} ${if (Store.watches.value.size == 1) "Beobachtung" else "Beobachtungen"}.") else tr("A mentés nem sikerült.", "Saving failed.", "Speichern fehlgeschlagen.")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Visszaállítás fájlból", "Restore from file", "Aus Datei wiederherstellen")) },
                                onClick = {
                                    menuOpen = false
                                    Platform.current.importFile { text ->
                                        if (text == null) return@importFile
                                        val parsed = Backup.parse(text)
                                        if (parsed == null) {
                                            toast = tr("Ez nem REFI-mentésfájl.", "This is not a REFI backup file.", "Das ist keine REFI-Sicherungsdatei.")
                                            return@importFile
                                        }
                                        AppScope.scope.launch {
                                            val (added, updated) = Store.importWatches(parsed.watches, parsed.currency)
                                            val before = Store.settings.value
                                            val restored = Backup.applySettings(parsed.settings, before)
                                            if (restored != before) {
                                                Store.saveSettings(restored)
                                                if (restored.intervalHours != before.intervalHours) Platform.current.reschedule()
                                            }
                                            toast = tr("Visszaállítva: $added új, $updated frissített figyelés", "Restored: $added new, $updated updated watches", "Wiederhergestellt: $added neue, $updated aktualisierte Beobachtungen") +
                                                (if (parsed.skipped > 0) tr(" (${parsed.skipped} hibás kihagyva).", " (${parsed.skipped} invalid skipped).", " (${parsed.skipped} ungültige übersprungen).") else ".")
                                            if (Store.settings.value.isReady) PriceChecker.checkAll()
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // Sötét gomb vékony, lassan lélegző kerettel (nem teli zöld)
            val glow = rememberInfiniteTransition(label = "fab")
            val borderAlpha by glow.animateFloat(
                0.35f, 0.85f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b",
            )
            val fabShape = RoundedCornerShape(16.dp)
            ExtendedFloatingActionButton(
                onClick = onAdd,
                shape = fabShape,
                containerColor = Neon.SurfaceHigh,
                contentColor = Neon.Green,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(2.dp, 2.dp, 2.dp, 2.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(tr("ÚJ FIGYELÉS", "NEW WATCH", "NEUE BEOBACHTUNG"), fontWeight = FontWeight.Bold) },
                modifier = Modifier.border(0.8.dp, Neon.Green.copy(alpha = borderAlpha), fabShape),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            // Az „Új figyelés” lebegő gomb soha ne takarja el az utolsó kártyát
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            toast?.let { msg ->
                item(key = "toast") {
                    NeonCard(modifier = Modifier.fillMaxWidth().animateItem()) {
                        Text(msg, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (!settings.isReady) {
                item { SetupCard(onSettings) }
            }
            if (notifyBlocked && watches.any { it.notify }) {
                item { BlockedNotificationsCard() }
            }
            if (watches.isEmpty()) {
                item {
                    Text(
                        tr("Még nincs figyelt út. Az „Új figyelés” gombbal adhatsz hozzá egyet.", "No watches yet. Tap “New watch” to add one.", "Noch keine Beobachtungen. Tippe auf „Neue Beobachtung“, um eine hinzuzufügen."),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(watches, key = { _, item -> item.id }) { index, w ->
                WatchCard(
                    modifier = Modifier.animateItem().enterAnimation(delayMs = index * 70, key = w.id),
                    onToggleNotify = {
                        Store.userUpdate(w.id) { it.copy(notify = !it.notify, lastNotifiedPrice = null) }
                    },
                    w = w,
                    currency = settings.currency,
                    isChecking = w.id in checking,
                    canCheck = settings.isReady,
                    onCheck = { AppScope.scope.launch { PriceChecker.checkOne(w.id) } },
                    onEdit = { onEdit(w.id) },
                    onOpen = { url -> Platform.current.openUrl(url) },
                    onShare = {
                        val copied = Platform.current.shareText(ShareCode.message(w, settings.currency))
                        if (copied) toast = tr("A figyelés kódja a vágólapra került – illeszd be egy üzenetbe.", "The watch code was copied – paste it into a message.", "Der Code der Beobachtung wurde kopiert – füge ihn in eine Nachricht ein.")
                    },
                    onCalendar = { evs ->
                        if (!Platform.current.addToCalendar(evs)) toast = tr("Nem sikerült megnyitni a naptárat.", "Couldn't open the calendar.", "Der Kalender konnte nicht geöffnet werden.")
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupCard(onSettings: () -> Unit) {
    NeonCard(color = Neon.Amber, pulse = true, modifier = Modifier.fillMaxWidth().enterAnimation()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Nincs bekapcsolt árforrás", "No price source turned on", "Keine Preisquelle aktiviert"), style = MaterialTheme.typography.titleMedium, color = Neon.Amber)
            Text(
                tr(
                    "Kapcsolj be legalább egy árforrást a Beállításokban (a Google Flights, a Ryanair " +
                        "és a Wizz Air kulcs nélkül működik).",
                    "Turn on at least one price source in Settings (Google Flights, Ryanair " +
                        "and Wizz Air work without a key).",
                    "Aktiviere in den Einstellungen mindestens eine Preisquelle (Google Flights, Ryanair " +
                        "und Wizz Air funktionieren ohne Schlüssel).",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSettings) { Text(tr("Beállítások", "Settings", "Einstellungen")) }
        }
    }
}

@Composable
private fun BlockedNotificationsCard() {
    NeonCard(color = Neon.Amber, modifier = Modifier.fillMaxWidth().enterAnimation()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("Az értesítések le vannak tiltva", "Notifications are turned off", "Benachrichtigungen sind deaktiviert"), style = MaterialTheme.typography.titleMedium, color = Neon.Amber)
            Text(
                tr(
                    "Így árriasztás sem érkezik, akkor sem, ha a csengő be van kapcsolva. " +
                        "Engedélyezd az értesítéseket a REFI-nek.",
                    "So no price alerts will arrive, even with the bell turned on. " +
                        "Please allow notifications for REFI.",
                    "So kommen keine Preisalarme an, auch wenn die Glocke an ist. " +
                        "Bitte erlaube Benachrichtigungen für REFI.",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { Platform.current.openNotificationSettings() }) { Text(tr("Értesítések engedélyezése", "Allow notifications", "Benachrichtigungen erlauben")) }
        }
    }
}

/**
 * Egy szöveg elejéről levágja az ikonként használt jelet (pl. „🚌 ”, „👥 ”, „▼ ”), ha még ott van:
 * a kártyán Material ikon áll előtte. A betűvel, számmal vagy pénznemjellel kezdődő szöveg változatlan.
 */
internal fun stripLeadingIcon(s: String): String {
    if (s.isEmpty()) return s
    val cp = s.codePointAt(0)
    if (Character.isLetterOrDigit(cp) || cp < 0x2190) return s
    var i = Character.charCount(cp)
    // Változatválasztó / összekötő (pl. „⚠️”)
    while (i < s.length && (s[i] == '\uFE0F' || s[i] == '\u200D')) i++
    return s.substring(i).trimStart()
}

/** Másodlagos információ egy kis ikonnal az elején (az ikon a szöveg első sorához igazodik). */
@Composable
private fun IconLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color = Neon.TextDim,
    iconTint: Color = Neon.TextDim,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * A legjobb ajánlat alatti kiegészítők: a távoli reptér belvárosi transzferének becsült költsége,
 * a fejenkénti teljes költség, és a „Naptárba” gomb.
 */
@Composable
private fun CostLines(w: Watch, best: Offer, currency: String, onCalendar: (List<CalEvent>) -> Unit) {
    val info = Transfers.infoFor(best.toCode)
    // Az árfolyam lekérése hálózatot is igényelhet: nem a felület szálán
    val rate by androidx.compose.runtime.produceState<Double?>(null, currency, info != null) {
        if (info != null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Rates.convert(1.0, "EUR", currency) }.getOrNull()
        }
    }
    if (info != null) IconLine(
        Icons.Filled.Place,
        stripLeadingIcon(Transfers.line(w, info, currency) { eur -> rate?.let { eur * it } }),
        modifier = Modifier.padding(top = 8.dp),
    )
    val transferTotal = info?.let { i -> rate?.let { r -> Math.round(Transfers.totalEur(w, i) * r).toInt() } }
    groupCostLine(w, best, currency, transferTotal)?.let {
        IconLine(Icons.Filled.Person, stripLeadingIcon(it), modifier = Modifier.padding(top = 4.dp))
    }
    val events = remember(best, w.from, w.to, Lang.code) { RefiCalendar.eventsFor(w, best) }
    if (events.isNotEmpty()) {
        TextButton(onClick = { onCalendar(events) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (events.size > 1) tr("Oda- és visszaút a naptárba", "Add both flights to calendar", "Hin- und Rückflug in den Kalender") else tr("Naptárba", "Add to calendar", "In den Kalender"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun WatchCard(
    modifier: Modifier,
    onToggleNotify: () -> Unit,
    w: Watch,
    currency: String,
    isChecking: Boolean,
    canCheck: Boolean,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: () -> Unit = {},
    onCalendar: (List<CalEvent>) -> Unit = {},
) {
    val best = w.bestOffer
    // Ugyanaz a szabály, mint a riasztásnál (poggyász, hiányos ár), hogy a kártya ne mondjon mást
    val belowTarget = best != null && w.alertable(best)
    var showAll by remember { mutableStateOf(false) }
    // Fokozatos feltárás: alapból csak a lényeg (ár, tanács), a részletek egy koppintásra
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(w.id) { mutableStateOf(expandCardsInitially) }

    NeonCard(
        modifier = modifier.fillMaxWidth(),
        pulse = belowTarget,
        scanning = isChecking,
    ) {
        Column {
            // ---- Fejléc: útvonal, megosztás, csengő
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.routeTitle,
                    style = MaterialTheme.typography.titleLarge.glow(radius = 12f),
                    color = Neon.Green,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = tr("Figyelés megosztása", "Share watch", "Beobachtung teilen"), tint = Neon.TextDim)
                }
                BellToggle(on = w.notify, onToggle = onToggleNotify)
            }
            Text(dateLine(w), style = MaterialTheme.typography.bodyMedium)
            Text(detailLine(w), style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)

            // ---- Ár: egyetlen fő szám
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Legolcsóbb most", "Cheapest now", "Jetzt am günstigsten"), style = MaterialTheme.typography.labelMedium, color = Neon.TextDim)
                    // Az ár „pörögve” változik az új értékre
                    // Üres állapotból vagy pénznemváltás után nem „pörög fel” nulláról / a régi számról
                    val priceAnim = remember(currency) { Animatable((best?.price ?: 0).toFloat()) }
                    LaunchedEffect(best?.price, currency) {
                        val p = best?.price ?: return@LaunchedEffect
                        if (priceAnim.value <= 0f) priceAnim.snapTo(p.toFloat())
                        else priceAnim.animateTo(p.toFloat(), tween(900, easing = FastOutSlowInEasing))
                    }
                    Text(
                        if (best != null) formatPrice(priceAnim.value.roundToInt(), currency) else "—",
                        style = if (belowTarget) MaterialTheme.typography.headlineMedium.glow(radius = 24f)
                        else MaterialTheme.typography.headlineMedium,
                        color = if (belowTarget) Neon.Mint else Neon.Text,
                    )
                    if (belowTarget) {
                        Row(
                            Modifier
                                .padding(top = 4.dp)
                                .background(Neon.Mint.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Neon.Mint, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr("CÉLÁR ALATT", "BELOW TARGET", "UNTER ZIELPREIS"), style = MaterialTheme.typography.labelSmall, color = Neon.Mint)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        tr("Célár: ${formatPrice(w.targetPrice, currency)}", "Target price: ${formatPrice(w.targetPrice, currency)}", "Zielpreis: ${formatPrice(w.targetPrice, currency)}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    w.lowestPrice?.let {
                        Text(
                            tr("Eddigi min.: ${formatPrice(it, currency)}", "Lowest so far: ${formatPrice(it, currency)}", "Bisher am niedrigsten: ${formatPrice(it, currency)}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = Neon.TextDim,
                        )
                    }
                }
            }

            // ---- Egyetlen fő üzenet: „Most vegyem vagy várjak?” – az eddigi árak alapján
            verdictFor(w, currency = currency)?.let { v ->
                Text(
                    v.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (v.tone) {
                        Verdict.Tone.GOOD -> Neon.Mint
                        Verdict.Tone.WAIT -> Neon.Amber
                        Verdict.Tone.NEUTRAL -> Neon.TextDim
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ---- Fontos: hiba / lejárt figyelés mindig látszik
            val problem = when {
                w.isExpired() -> tr("Az indulás dátuma elmúlt, a figyelés szünetel.", "The departure date has passed, this watch is paused.", "Das Abflugdatum ist vorbei, diese Beobachtung pausiert.")
                w.lastError != null -> w.errorText ?: w.lastError
                else -> null
            }
            problem?.let {
                IconLine(
                    Icons.Filled.Warning, it,
                    color = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // ---- Utolsó ellenőrzés + Részletek kapcsoló
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.lastChecked?.let {
                        tr("Utoljára ellenőrizve: ", "Last checked: ", "Zuletzt geprüft: ") +
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(timeFormat)
                    } ?: tr("Még nem volt ellenőrzés.", "Not checked yet.", "Noch nicht geprüft."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Neon.TextDim,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) tr("Kevesebb", "Less", "Weniger") else tr("Részletek", "Details", "Details"),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ---- Részletek (lenyitva)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // Előrejelzés: másodlagos, semleges színnel (soha ne mondjon ellent a fő tanácsnak)
                    forecastFor(w)?.let { f ->
                        Text(f.text, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim, modifier = Modifier.padding(top = 4.dp))
                    }
                    savingsLine(w, currency)?.let {
                        IconLine(Icons.Filled.Check, stripLeadingIcon(it), iconTint = Neon.Mint, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (best != null) {
                        OfferDetails(best, highlight = true)
                        CostLines(w, best, currency, onCalendar)
                    }

                    // Árgörbe: a Google árelőzménye + a saját mérések (ha van mit mutatni)
                    PriceChart(w, currency, Modifier.fillMaxWidth().padding(top = 12.dp))

                    if (w.sourceStatus.isNotEmpty()) {
                        Text(
                            tr("Források", "Sources", "Quellen"),
                            style = MaterialTheme.typography.labelMedium,
                            color = Neon.TextDim,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            w.sourceStatus.joinToString("  ·  ") { s ->
                                "${s.source} ${if (s.ok) "✓" else "✗"} ${s.shown}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (w.sourceStatus.any { !it.ok }) MaterialTheme.colorScheme.error else Neon.TextDim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    if (w.offers.size > 1) {
                        TextButton(onClick = { showAll = !showAll }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Text(if (showAll) tr("Kevesebb ajánlat", "Fewer offers", "Weniger Angebote") else tr("Mind a ${w.offers.size} ajánlat", "All ${w.offers.size} offers", "Alle ${w.offers.size} Angebote"), maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (showAll) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = showAll && w.offers.size > 1,
                        enter = expandVertically(tween(350)) + fadeIn(tween(350)),
                        exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
                    ) {
                        Column {
                            Text(tr("ÖSSZES AJÁNLAT", "ALL OFFERS", "ALLE ANGEBOTE"), style = MaterialTheme.typography.titleMedium, color = Neon.Green, modifier = Modifier.padding(top = 8.dp))
                            w.offers.forEachIndexed { index, offer ->
                                Column(
                                    Modifier
                                        .padding(top = 8.dp)
                                        .fillMaxWidth()
                                        .border(0.5.dp, Neon.Line, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${index + 1}. ${formatPrice(offer.price, currency)}",
                                            style = MaterialTheme.typography.titleLarge,
                                            modifier = Modifier.weight(1f),
                                        )
                                        offer.url?.let { url ->
                                            TextButton(onClick = { onOpen(url) }) { Text(tr("Megnyitás", "Open", "Öffnen")) }
                                        }
                                    }
                                    OfferDetails(offer, highlight = false)
                                }
                            }
                        }
                    }
                }
            }

            // ---- Fő műveletek (mindig látszanak): egy kiemelt gomb (az ajánlat megnyitása), mellette
            // ikongombok (ellenőrzés, szerkesztés) – egy sorban, nagy betűméretnél is
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 8.dp), color = Neon.Line.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val url = best?.url
                if (url != null) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { onOpen(url) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Ajánlat megnyitása", "Open offer", "Angebot öffnen"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (isChecking) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Neon.Green)
                    }
                } else {
                    IconButton(onClick = onCheck, enabled = canCheck && !w.isExpired()) {
                        Icon(Icons.Filled.Refresh, contentDescription = tr("Ellenőrzés most", "Check now", "Jetzt prüfen"))
                    }
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = tr("Szerkesztés", "Edit", "Bearbeiten"))
                }
            }
        }
    }
}

/** Egy ajánlat részletei: pontos indulás/érkezés, légitársaság, forrás, megjegyzés. */
@Composable
private fun OfferDetails(offer: Offer, highlight: Boolean) {
    Column(Modifier.padding(top = 6.dp)) {
        val out = offer.outboundParts()
        val ret = offer.returnParts()
        if (out != null) LegBlock(tr("INDULÁS", "DEPARTURE", "ABFLUG"), out, highlight) else {
            Text(tr("Indulási idő: a forrás nem adta meg", "Departure time: not given by the source", "Abflugzeit: von der Quelle nicht angegeben"), style = MaterialTheme.typography.bodyLarge)
        }
        if (ret != null) {
            Spacer(Modifier.height(6.dp))
            LegBlock(tr("VISSZA", "RETURN", "RÜCKFLUG"), ret, highlight)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(offer.airline, tr("forrás: ${offer.source}", "source: ${offer.source}", "Quelle: ${offer.source}")).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        offer.noteText?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Egy út nagy, jól olvasható időpontokkal: a napon belüli idő a legnagyobb, mert ezt hasonlítjuk össze. */
@Composable
private fun LegBlock(label: String, parts: LegParts, highlight: Boolean) {
    Text(
        "$label · ${parts.day}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        parts.times,
        style = MaterialTheme.typography.titleLarge,
        color = if (highlight) Neon.Green else Neon.Text,
    )
    parts.route?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
internal fun StatusText(text: String, isError: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun dateLine(w: Watch): String {
    val out = runCatching { LocalDate.parse(w.outboundDate).format(shortDate) }.getOrDefault(w.outboundDate)
    val ret = w.returnDate?.let { r -> runCatching { LocalDate.parse(r).format(shortDate) }.getOrDefault(r) }
    val until = w.weeklyUntil?.let { u -> runCatching { LocalDate.parse(u).format(shortDate) }.getOrDefault(u) }
    val flex = when {
        until != null -> tr(", minden héten $until-ig", ", every week until $until", ", jede Woche bis $until")
        w.flexDays > 0 -> tr(" (±${w.flexDays} nap)", if (w.flexDays == 1) " (±1 day)" else " (±${w.flexDays} days)", if (w.flexDays == 1) " (±1 Tag)" else " (±${w.flexDays} Tage)")
        else -> ""
    }
    return if (ret != null) tr("$out – $ret$flex · oda-vissza", "$out – $ret$flex · return", "$out – $ret$flex · Hin- und Rückflug") else tr("$out$flex · csak oda", "$out$flex · one way", "$out$flex · nur Hinflug")
}

private fun detailLine(w: Watch): String {
    val parts = mutableListOf<String>()
    parts += tr("${w.adults} felnőtt", if (w.adults == 1) "1 adult" else "${w.adults} adults", if (w.adults == 1) "1 Erwachsener" else "${w.adults} Erwachsene")
    if (w.children > 0) parts += tr("${w.children} gyerek", if (w.children == 1) "1 child" else "${w.children} children", if (w.children == 1) "1 Kind" else "${w.children} Kinder")
    val infants = w.infantsInSeat + w.infantsOnLap
    if (infants > 0) parts += tr("$infants csecsemő", if (infants == 1) "1 infant" else "$infants infants", if (infants == 1) "1 Säugling" else "$infants Säuglinge")
    val pax = parts.joinToString(", ")
    val cls = TRAVEL_CLASSES.firstOrNull { it.first == w.travelClass }?.second ?: ""
    val extra = mutableListOf(pax, cls)
    if (w.bags > 0) extra += tr("${w.bags} kézipoggyász", if (w.bags == 1) "1 cabin bag" else "${w.bags} cabin bags", if (w.bags == 1) "1 Handgepäck" else "${w.bags} Handgepäckstücke")
    if (w.checkedBag) extra += tr("feladott poggyász", "checked bag", "Aufgabegepäck")
    if (w.stops != 0) extra += STOP_OPTIONS.firstOrNull { it.first == w.stops }?.second ?: ""
    if (w.depFrom != null || w.depTo != null) {
        extra += tr("indulás ", "departs ", "Abflug ") + listOfNotNull(
            w.depFrom?.let { tr("%02d:00-tól", "from %02d:00", "ab %02d:00").format(it) },
            w.depTo?.let { tr("%02d:00-ig", "until %02d:00", "bis %02d:00").format(it) },
        ).joinToString(" ")
    }
    if (w.airlineTokens.isNotEmpty()) extra += tr("csak: ${w.airlines.trim()}", "only: ${w.airlines.trim()}", "nur: ${w.airlines.trim()}")
    return extra.joinToString(" · ")
}

// ---------------------------------------------------------------- Szerkesztés

@Composable
internal fun EditScreen(id: String?, template: Watch? = null, onDone: () -> Unit) {
    val existing = remember(id) { id?.let { i -> Store.watches.value.find { it.id == i } } }
    // Kezdőértékek: a szerkesztett figyelés, vagy egy sablon (pl. a Felfedezés találatából)
    val init = existing ?: template
    val currency = Store.settings.collectAsState().value.currency
    val today = remember { LocalDate.now() }

    var fromPlace by remember {
        mutableStateOf<Place?>(
            if (init != null) Airports.placeFor(init.from, init.fromLabel)
            else Airports.placeFor("BUD", null)
        )
    }
    var toPlace by remember {
        mutableStateOf<Place?>(init?.let { Airports.placeFor(it.to, it.toLabel) })
    }
    var roundTrip by remember { mutableStateOf(init?.isRoundTrip ?: true) }
    var outDate by remember {
        mutableStateOf(init?.outboundDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today.plusDays(30))
    }
    var retDate by remember {
        mutableStateOf(init?.returnDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: outDate.plusDays(7))
    }
    var travelClass by remember { mutableStateOf(init?.travelClass ?: 1) }
    var adults by remember { mutableStateOf(init?.adults ?: 1) }
    var children by remember { mutableStateOf(init?.children ?: 0) }
    var infantsInSeat by remember { mutableStateOf(init?.infantsInSeat ?: 0) }
    var infantsOnLap by remember { mutableStateOf(init?.infantsOnLap ?: 0) }
    var bags by remember { mutableStateOf(init?.bags ?: 0) }
    var stops by remember { mutableStateOf(init?.stops ?: 0) }
    var target by remember { mutableStateOf(init?.targetPrice?.toString() ?: "") }
    var notify by remember { mutableStateOf(init?.notify ?: true) }
    var checkedBag by remember { mutableStateOf(init?.checkedBag ?: false) }
    var flexDays by remember { mutableStateOf(init?.flexDays ?: 0) }
    // „Minden héten” mód: a megadott napok hetente ismétlődnek eddig a napig (pl. bármelyik hétvége)
    var weekly by remember { mutableStateOf(init?.weeklyUntil != null) }
    var weeklyUntil by remember {
        mutableStateOf(init?.weeklyUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: (init?.outboundDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()).plusWeeks(3))
    }
    var untilValid by remember { mutableStateOf(true) }
    var depFrom by remember { mutableStateOf(init?.depFrom) }
    var depTo by remember { mutableStateOf(init?.depTo) }
    var airlines by remember { mutableStateOf(init?.airlines ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var outValid by remember { mutableStateOf(true) }
    var retValid by remember { mutableStateOf(true) }
    // Egy azonosító a szerkesztő egész életére: dupla koppintásnál sem lesz két egyforma figyelés
    val newId = remember { UUID.randomUUID().toString() }
    var saved by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf<KeyProvider?>(null) }
    var tipRound by remember { mutableStateOf(0) }
    // Ha közben egy másik eszközön törölték, a második Mentés újként menti
    var recreate by remember { mutableStateOf(false) }

    val maxBags = adults + children + infantsInSeat
    if (bags > maxBags) bags = maxBags
    if (infantsOnLap > adults) infantsOnLap = adults

    fun save() {
        if (saved) return
        val targetValue = target.toIntOrNull()
        val from = fromPlace
        val to = toPlace
        error = when {
            !outValid || (roundTrip && !retValid) -> tr("Javítsd a pirossal jelölt dátumot.", "Please fix the date marked in red.", "Bitte korrigiere das rot markierte Datum.")
            from == null -> tr("Válaszd ki az indulási repülőteret a listából.", "Pick the departure airport from the list.", "Wähle den Abflughafen aus der Liste.")
            to == null -> tr("Válaszd ki az érkezési repülőteret a listából.", "Pick the arrival airport from the list.", "Wähle den Zielflughafen aus der Liste.")
            from.codes.split(',').any { it in to.codes.split(',') } ->
                tr("Az indulási és érkezési hely nem lehet ugyanaz.", "Departure and arrival can't be the same place.", "Abflug- und Zielort dürfen nicht gleich sein.")
            // Rugalmas dátumnál elég, ha a tartomány még nem múlt el
            weekly && !untilValid -> tr("Javítsd a pirossal jelölt dátumot.", "Please fix the date marked in red.", "Bitte korrigiere das rot markierte Datum.")
            weekly && weeklyUntil.isBefore(outDate) -> tr("A „minden héten” utolsó napja nem lehet az első indulás előtt.", "The last day of “every week” can't be before the first departure.", "Der letzte Tag von „jede Woche“ darf nicht vor dem ersten Abflug liegen.")
            weekly && weeklyUntil.isAfter(outDate.plusWeeks((MAX_WEEKS - 1).toLong())) ->
                tr("„Minden héten” legfeljebb $MAX_WEEKS hétre állítható.", "“Every week” can be set for at most $MAX_WEEKS weeks.", "„Jede Woche“ ist für höchstens $MAX_WEEKS Wochen möglich.")
            weekly && lastWeekly(outDate, weeklyUntil).isBefore(today) -> tr("Az indulás dátuma nem lehet a múltban.", "The departure date can't be in the past.", "Das Abflugdatum darf nicht in der Vergangenheit liegen.")
            !weekly && outDate.plusDays(flexDays.toLong()).isBefore(today) -> tr("Az indulás dátuma nem lehet a múltban.", "The departure date can't be in the past.", "Das Abflugdatum darf nicht in der Vergangenheit liegen.")
            outDate.isAfter(maxTravelDate(today)) || (roundTrip && retDate.isAfter(maxTravelDate(today))) ||
                (weekly && weeklyUntil.isAfter(maxTravelDate(today))) ->
                tr("Legfeljebb ${maxTravelDate(today).format(typedDateFormat)}-ig lehet dátumot megadni.", "Dates can be set up to ${maxTravelDate(today).format(typedDateFormat)} at most.", "Daten sind höchstens bis ${maxTravelDate(today).format(typedDateFormat)} möglich.")
            roundTrip && retDate.isBefore(outDate) -> tr("A visszaút nem lehet az indulás előtt.", "The return can't be before the departure.", "Der Rückflug darf nicht vor dem Abflug liegen.")
            adults + children + infantsInSeat + infantsOnLap > 9 -> tr("Legfeljebb 9 utas adható meg.", "At most 9 passengers are allowed.", "Höchstens 9 Reisende sind möglich.")
            targetValue == null -> tr("Adj meg egy célárat.", "Please enter a target price.", "Bitte gib einen Zielpreis ein.")
            targetValue <= 0 -> tr("A célár legyen nagyobb 0-nál.", "The target price must be more than 0.", "Der Zielpreis muss größer als 0 sein.")
            depFrom != null && depTo != null && depTo!! <= depFrom!! -> tr("Az indulási időablak vége legyen későbbi, mint az eleje.", "The end of the departure time window must be later than its start.", "Das Ende des Abflug-Zeitfensters muss nach dem Anfang liegen.")
            else -> null
        }
        if (error != null || targetValue == null || from == null || to == null) return
        val stillThere = existing != null && Store.watches.value.any { it.id == existing.id }
        if (existing != null && !stillThere && !recreate) {
            error = tr("Ezt a figyelést közben törölték (pl. a másik eszközödön). Ha mégis kell, nyomd meg újra a Mentést.", "This watch was deleted in the meantime (e.g. on your other device). If you still need it, tap Save again.", "Diese Beobachtung wurde inzwischen gelöscht (z. B. auf deinem anderen Gerät). Wenn du sie noch brauchst, tippe erneut auf Speichern.")
            recreate = true
            return
        }

        val fresh = Watch(
            id = if (stillThere) existing!!.id else newId,
            from = from.codes,
            to = to.codes,
            fromLabel = from.city,
            toLabel = to.city,
            outboundDate = outDate.toString(),
            returnDate = if (roundTrip) retDate.toString() else null,
            travelClass = travelClass,
            adults = adults,
            children = children,
            infantsInSeat = infantsInSeat,
            infantsOnLap = infantsOnLap,
            bags = bags,
            stops = stops,
            checkedBag = checkedBag,
            targetPrice = targetValue,
            notify = notify,
            flexDays = if (weekly) 0 else flexDays,
            weeklyUntil = if (weekly) lastWeekly(outDate, weeklyUntil).toString() else null,
            // Megosztott figyelés szerkesztése után is felismerhető maradjon, ha újra elküldik
            sharedFrom = if (stillThere) existing!!.sharedFrom else null,
            depFrom = depFrom,
            depTo = depTo,
            airlines = airlines.trim(),
        )
        val sameSearch = stillThere && existing!!.searchKey() == fresh.searchKey()
        saved = true
        if (sameSearch) {
            val alertChanged = existing!!.targetPrice != targetValue || existing.notify != notify
            // A tárolt, legfrissebb állapotból: ha közben lefutott egy ellenőrzés,
            // annak eredményét nem írjuk felül a szerkesztő megnyitásakori példánnyal
            Store.userUpdate(existing.id) {
                it.copy(
                    targetPrice = targetValue,
                    notify = notify,
                    // Csak akkor szólunk újra ugyanarról az árról, ha a célár vagy az értesítés változott
                    lastNotifiedPrice = if (it.targetPrice != targetValue || it.notify != notify) null else it.lastNotifiedPrice,
                    // Az „add meg újra a célárat” figyelmeztetés az új célárral megoldódott
                    lastError = it.lastError?.takeUnless { e -> PriceChecker.isCurrencyHint(e) },
                    fromLabel = from.city,
                    toLabel = to.city,
                )
            }
            // Új célárnál azonnal kiderül, alatta van-e már az ár (nem csak a következő ütemezett ellenőrzéskor)
            if (alertChanged && Store.settings.value.isReady) {
                AppScope.scope.launch { PriceChecker.checkOne(existing.id) }
            }
        } else {
            Store.userUpsert(fresh)
            if (Store.settings.value.isReady) {
                AppScope.scope.launch { PriceChecker.checkOne(fresh.id) }
            }
        }
        onDone()
    }

    Scaffold(
        containerColor = Neon.Black,
        topBar = {
            NeonTopBar(if (existing == null) tr("ÚJ FIGYELÉS", "NEW WATCH", "NEUE BEOBACHTUNG") else tr("SZERKESZTÉS", "EDIT", "BEARBEITEN"), onBack = onDone)
        },
        bottomBar = {
            // Rögzített mentés-sáv: a gomb mindig elérhető, a hibaüzenet is itt látszik (nem görgetődik el)
            androidx.compose.material3.Surface(color = Neon.Surface) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(Neon.Line, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f) }
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(
                        onClick = { save() },
                        enabled = !saved,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(tr("Mentés", "Save", "Speichern"), fontWeight = FontWeight.Bold) }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Tippek és trükkök (pl. ingyenes kulcs a több árhoz – végigvezetünk rajta)
            // Sikeres kulcsbeállítás után új tipp jön (a kulcsos tipp már nem aktuális)
            androidx.compose.runtime.key(tipRound) { TipBubble(onOpenGuide = { guide = it }) }
            guide?.let { p -> KeyGuideDialog(p, onClose = { guide = null }, onSaved = { tipRound++ }) }

            // ---- Útvonal
            SettingsCard {
                SectionTitle(tr("Útvonal", "Route", "Strecke"))
                AirportField(tr("Honnan", "From", "Von"), fromPlace) { fromPlace = it }
                AirportField(tr("Hova", "To", "Nach"), toPlace) { toPlace = it }
                SwitchRow(tr("Oda-vissza út", "Return trip", "Hin- und Rückflug"), roundTrip) { roundTrip = it }
            }

            // ---- Dátum
            SettingsCard {
                SectionTitle(tr("Dátum", "Date", "Datum"))
                DateField(
                    if (weekly) tr("Első indulás", "First departure", "Erster Abflug") else tr("Indulás", "Departure", "Abflug"), outDate,
                    minDate = if (weekly) minOf(outDate, today) else today.minusDays(flexDays.toLong()),
                    onValidChange = { outValid = it },
                ) {
                    // Az út hossza marad: ha az indulás eltolódik, a visszaút vele mozog
                    // (gépelés közbeni részleges dátumnál sem vész el az eredeti hossz)
                    val days = java.time.temporal.ChronoUnit.DAYS.between(outDate, retDate).coerceAtLeast(0)
                    // A „minden héten” tartomány is vele mozog
                    val span = java.time.temporal.ChronoUnit.DAYS.between(outDate, weeklyUntil)
                    outDate = it
                    retDate = it.plusDays(days)
                    if (span >= 0) weeklyUntil = it.plusDays(span)
                }
                if (roundTrip) {
                    DateField(tr("Visszaút", "Return", "Rückflug"), retDate, minDate = outDate, onValidChange = { retValid = it }) { retDate = it }
                }
                // Havi árnaptár: melyik nap a legolcsóbb (a fapadosok árnaptárából)
                val calFrom = fromPlace
                val calTo = toPlace
                if (calFrom != null && calTo != null) {
                    var showCal by remember { mutableStateOf(false) }
                    TextButton(onClick = { showCal = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Árnaptár – melyik nap a legolcsóbb?", "Price calendar – which day is cheapest?", "Preiskalender – welcher Tag ist am günstigsten?"))
                    }
                    if (showCal) PriceCalendarDialog(calFrom.codes, calTo.codes, outDate, onPick = { picked ->
                        val days = java.time.temporal.ChronoUnit.DAYS.between(outDate, retDate).coerceAtLeast(0)
                        val span = java.time.temporal.ChronoUnit.DAYS.between(outDate, weeklyUntil)
                        // A visszaút és a „minden héten” vége se lógjon túl a megadható utolsó napon
                        val latest = maxTravelDate(today)
                        outDate = picked
                        retDate = minOf(picked.plusDays(days), latest)
                        if (span >= 0) weeklyUntil = minOf(picked.plusDays(span), latest)
                    }, onClose = { showCal = false },
                        nights = if (roundTrip) java.time.temporal.ChronoUnit.DAYS.between(outDate, retDate).toInt().coerceAtLeast(0) else null)
                }
                ChoiceField(tr("Rugalmasság", "Flexibility", "Flexibilität"), FLEX_OPTIONS + (WEEKLY_CHOICE to tr("Minden héten (pl. bármelyik hétvége)", "Every week (e.g. any weekend)", "Jede Woche (z. B. jedes Wochenende)")), if (weekly) WEEKLY_CHOICE else flexDays) {
                    weekly = it == WEEKLY_CHOICE
                    flexDays = if (weekly) 0 else it
                    if (weekly && (weeklyUntil.isBefore(outDate) || weeklyUntil.isAfter(outDate.plusWeeks((MAX_WEEKS - 1).toLong())))) {
                        weeklyUntil = outDate.plusWeeks(3)
                    }
                }
                if (weekly) {
                    DateField(
                        tr("Utolsó indulás legkésőbb", "Last departure at the latest", "Letzter Abflug spätestens"), weeklyUntil, minDate = outDate,
                        maxDate = minOf(outDate.plusWeeks((MAX_WEEKS - 1).toLong()), maxTravelDate(today)),
                        onValidChange = { untilValid = it },
                    ) { weeklyUntil = it }
                    val weeks = weeklyCount(outDate, weeklyUntil)
                    Text(
                        tr(
                            "Ugyanezeken a napokon minden héten keres ($weeks hét), és a legolcsóbbat mutatja. " +
                                "Pl. péntek–vasárnapot megadva: bármelyik hétvége. (Csak a REFI 1.4-től működik – a többi eszközödön is frissíts.)",
                            "Searches the same days every week ($weeks weeks) and shows the cheapest. " +
                                "E.g. Friday–Sunday means any weekend. (Needs REFI 1.4 or later – update your other devices too.)",
                            "Sucht jede Woche an denselben Tagen (${if (weeks == 1) "1 Woche" else "$weeks Wochen"}) und zeigt das günstigste Angebot. " +
                                "Z. B. Freitag–Sonntag heißt: jedes Wochenende. (Ab REFI 1.4 – aktualisiere auch deine anderen Geräte.)",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Neon.TextDim,
                    )
                }
                if (!weekly && flexDays > 0) {
                    Text(
                        tr("A megadott naptól ±$flexDays napon belül keresi a legolcsóbbat (az út hossza marad).", "Looks for the cheapest within ±$flexDays ${if (flexDays == 1) "day" else "days"} of the chosen date (trip length stays the same).", "Sucht das günstigste Angebot innerhalb von ±$flexDays ${if (flexDays == 1) "Tag" else "Tagen"} um das gewählte Datum (die Reisedauer bleibt gleich)."),
                        style = MaterialTheme.typography.bodySmall,
                        color = Neon.TextDim,
                    )
                }
            }

            // ---- Utasok és osztály
            SettingsCard {
                SectionTitle(tr("Utasok és osztály", "Passengers and class", "Reisende und Klasse"))
                ChoiceField(tr("Osztály", "Class", "Klasse"), TRAVEL_CLASSES, travelClass) { travelClass = it }
                Stepper(tr("Felnőtt", "Adults", "Erwachsene"), tr("12 év felett", "over 12", "über 12"), adults, 1..9) { adults = it }
                Stepper(tr("Gyerek", "Children", "Kinder"), tr("2–11 év", "ages 2–11", "2–11 Jahre"), children, 0..8) { children = it }
                Stepper(tr("Csecsemő saját ülésen", "Infants in own seat", "Säuglinge mit eigenem Sitz"), tr("2 év alatt", "under 2", "unter 2"), infantsInSeat, 0..4) { infantsInSeat = it }
                Stepper(tr("Csecsemő ölben", "Infants on lap", "Säuglinge auf dem Schoß"), tr("2 év alatt, felnőttenként 1", "under 2, 1 per adult", "unter 2, 1 pro Erwachsenem"), infantsOnLap, 0..adults) { infantsOnLap = it }
            }

            // ---- Poggyász és átszállás
            SettingsCard {
                SectionTitle(tr("Poggyász és átszállás", "Bags and stops", "Gepäck und Umstiege"))
                Stepper(tr("Kézipoggyász", "Cabin bags", "Handgepäck"), tr("összesen, minden utasra", "in total, for all passengers", "insgesamt, für alle Reisenden"), bags, 0..maxBags) { bags = it }
                SwitchRow(tr("Feladott poggyász (utasonként 1)", "Checked bag (1 per passenger)", "Aufgabegepäck (1 pro Person)"), checkedBag) { checkedBag = it }
                Text(
                    tr(
                        "A fapadosoknál (Ryanair, Wizz Air, easyJet…) a poggyász becsült díját " +
                            "hozzáadjuk az árhoz. A hagyományos légitársaságoknál úgy számolunk, hogy a " +
                            "poggyász benne van a jegyárban (a legolcsóbb „light” jegyeknél ez nem mindig igaz).",
                        "For low-cost airlines (Ryanair, Wizz Air, easyJet…) we add an estimated bag fee " +
                            "to the price. For traditional airlines we assume the bags are included " +
                            "in the fare (not always true for the cheapest “light” fares).",
                        "Bei Billigfliegern (Ryanair, Wizz Air, easyJet…) rechnen wir eine geschätzte Gepäckgebühr " +
                            "zum Preis dazu. Bei klassischen Airlines gehen wir davon aus, dass das Gepäck im " +
                            "Ticketpreis enthalten ist (bei den günstigsten „Light“-Tarifen nicht immer).",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Neon.TextDim,
                )
                ChoiceField(tr("Átszállás", "Stops", "Umstiege"), STOP_OPTIONS, stops) { stops = it }
            }

            // ---- Szűrők (alapból csukva, egysoros összefoglalóval)
            SettingsCard {
                val filterParts = listOfNotNull(
                    depFrom?.let { f -> HOUR_FROM_OPTIONS.firstOrNull { it.first == f }?.second },
                    depTo?.let { t -> HOUR_TO_OPTIONS.firstOrNull { it.first == t }?.second },
                    airlines.trim().takeIf { it.isNotEmpty() }?.let { tr("csak: $it", "only: $it", "nur: $it") },
                )
                CollapsibleSection(
                    title = tr("Szűrők", "Filters", "Filter"),
                    summary = if (filterParts.isEmpty()) tr("Nincs szűrő", "No filters", "Keine Filter") else filterParts.joinToString(" · "),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { ChoiceField(tr("Indulás legkorábban", "Depart earliest", "Abflug frühestens"), HOUR_FROM_OPTIONS, depFrom) { depFrom = it } }
                        Box(Modifier.weight(1f)) { ChoiceField(tr("Indulás legkésőbb", "Depart latest", "Abflug spätestens"), HOUR_TO_OPTIONS, depTo) { depTo = it } }
                    }
                    OutlinedTextField(
                        value = airlines,
                        onValueChange = { airlines = it.take(80) },
                        label = { Text(tr("Csak ezek a légitársaságok", "Only these airlines", "Nur diese Airlines")) },
                        placeholder = { Text(tr("pl. Wizz, Ryanair – üresen: bármelyik", "e.g. Wizz, Ryanair – empty: any", "z. B. Wizz, Ryanair – leer: alle")) },
                        supportingText = { Text(tr("Vesszővel elválasztva; elég a név része is", "Separated by commas; part of the name is enough", "Durch Kommas getrennt; ein Teil des Namens reicht")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- Riasztás
            SettingsCard {
                SectionTitle(tr("Riasztás", "Alert", "Alarm"))
                OutlinedTextField(
                    value = target,
                    onValueChange = { v ->
                        // Tizedesrész (pl. beillesztett „89,99”) ne szorozza százzal az árat
                        val whole = v.trim().replace(Regex("[.,]\\d{1,2}$"), "")
                        target = whole.filter(Char::isDigit).take(9)
                    },
                    label = { Text(tr("Célár (${currencySymbol(currency)})", "Target price (${currencySymbol(currency)})", "Zielpreis (${currencySymbol(currency)})")) },
                    supportingText = { Text(tr("Szólunk, ha a teljes ár (minden utassal) erre az összegre vagy ez alá csökken", "We'll let you know when the total price (all passengers) drops to this amount or below", "Wir melden uns, wenn der Gesamtpreis (alle Reisenden) auf diesen Betrag oder darunter fällt")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(tr("Értesítés küldése", "Send notification", "Benachrichtigung senden"), notify) { notify = it }
            }

            if (confirmDelete && existing != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(tr("Törlöd a figyelést?", "Delete this watch?", "Diese Beobachtung löschen?")) },
                    text = { Text(tr("${existing.routeTitle} – az árelőzményekkel együtt, minden eszközödről.", "${existing.routeTitle} – together with its price history, from all your devices.", "${existing.routeTitle} – samt Preisverlauf, von all deinen Geräten.")) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            saved = true
                            Store.delete(existing.id)
                            onDone()
                        }) { Text(tr("Törlés", "Delete", "Löschen"), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Mégse", "Cancel", "Abbrechen")) } },
                )
            }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saved,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (saved) 0.3f else 0.7f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Figyelés törlése", "Delete watch", "Beobachtung löschen"))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Beállítások

@Composable
internal fun SettingsScreen(onDone: () -> Unit) {
    // Minden beállítás azonnal mentődik (nincs külön Mentés gomb). A kapcsolók és választók mindig a
    // tárolt, legfrissebb állapotból olvasnak, és csak a saját mezőjüket írják: így a szinkron vagy a
    // kulcsvarázsló közbeni módosításait sem írjuk felül.
    val stored by Store.settings.collectAsState()
    fun update(change: (Settings) -> Settings) {
        val before = Store.settings.value
        val after = change(before)
        if (after != before) Store.saveSettings(after)
    }

    // A kulcsmezők gépelés közben rövid szünet után mentődnek (és a képernyő elhagyásakor is).
    // Csak akkor írjuk, ha itt módosította: a máshonnan (szinkron, varázsló) érkező kulcsot nem írjuk vissza.
    var apiKey by remember { mutableStateOf(stored.apiKey) }
    var ignavKey by remember { mutableStateOf(stored.ignavKey) }
    var apiKeyEdited by remember { mutableStateOf(false) }
    var ignavKeyEdited by remember { mutableStateOf(false) }
    fun flushKeys() {
        val cur = Store.settings.value
        val next = cur.copy(
            apiKey = if (apiKeyEdited) apiKey.trim() else cur.apiKey,
            ignavKey = if (ignavKeyEdited) ignavKey.trim() else cur.ignavKey,
        )
        if (next != cur) Store.saveSettings(next)
        // Mentve: innentől a tárolt (pl. szinkronból frissülő) kulcsot követi a mező
        apiKeyEdited = false
        ignavKeyEdited = false
    }
    LaunchedEffect(apiKey, ignavKey) {
        if (!apiKeyEdited && !ignavKeyEdited) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        flushKeys()
    }
    // Ha nem itt szerkeszti, a mező kövesse a tárolt kulcsot (pl. a másik eszközről szinkronizált)
    LaunchedEffect(stored.apiKey) { if (!apiKeyEdited) apiKey = stored.apiKey }
    LaunchedEffect(stored.ignavKey) { if (!ignavKeyEdited) ignavKey = stored.ignavKey }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { flushKeys() } }

    var keyGuide by remember { mutableStateOf<KeyProvider?>(null) }
    // Pénznemváltás: a háttérben fut (a célárakkal együtt), addig a választott értéket mutatjuk
    var pendingCurrency by remember { mutableStateOf<String?>(null) }
    var confirmCurrency by remember { mutableStateOf<String?>(null) }
    val currency = pendingCurrency ?: stored.currency
    LaunchedEffect(stored.currency) { if (stored.currency == pendingCurrency) pendingCurrency = null }

    // A kulcsmezők még nem mentett, épp gépelt értékével számolunk (különben a figyelmeztetés késne)
    val view = stored.copy(
        apiKey = if (apiKeyEdited) apiKey.trim() else stored.apiKey,
        ignavKey = if (ignavKeyEdited) ignavKey.trim() else stored.ignavKey,
    )
    val interval = stored.intervalHours
    val watches by Store.watches.collectAsState()
    val activeWatches = watches.filter { !it.isExpired() }
    val checksPerMonth = if (interval > 0) (24 / interval) * 30 else 0
    val serpPerMonth = if (view.useSerpApi) activeWatches.size * checksPerMonth else 0
    val ignavPerMonth = if (view.useIgnav) {
        activeWatches.sumOf {
            (it.from.split(',').size * it.to.split(',').size).coerceAtMost(Ignav.MAX_PAIRS)
        } * checksPerMonth
    } else 0

    confirmCurrency?.let { to ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCurrency = null },
            title = { Text(tr("Pénznem váltása?", "Change currency?", "Währung wechseln?")) },
            text = {
                Text(tr(
                    "Pénznemváltáskor az eddigi árelőzmények törlődnek, a célárakat átváltjuk.",
                    "Changing the currency clears the price history so far; target prices are converted.",
                    "Beim Wechsel der Währung wird der bisherige Preisverlauf gelöscht; die Zielpreise werden umgerechnet.",
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCurrency = null
                    pendingCurrency = to
                    AppScope.scope.launch { Store.switchCurrency(to) }
                }) { Text(tr("Váltás", "Change", "Wechseln")) }
            },
            dismissButton = { TextButton(onClick = { confirmCurrency = null }) { Text(tr("Mégse", "Cancel", "Abbrechen")) } },
        )
    }

    keyGuide?.let { p ->
        KeyGuideDialog(p, onClose = { keyGuide = null }, onSaved = { k ->
            // A varázsló már elmentette: a mező is ezt mutassa, és ne írjuk felül egy korábbi gépeléssel
            when (p) {
                KeyProvider.SERPAPI -> { apiKey = k; apiKeyEdited = false }
                KeyProvider.IGNAV -> { ignavKey = k; ignavKeyEdited = false }
            }
        })
    }

    Scaffold(
        containerColor = Neon.Black,
        topBar = {
            NeonTopBar(tr("BEÁLLÍTÁSOK", "SETTINGS", "EINSTELLUNGEN"), onBack = onDone)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                tr("A módosítások azonnal mentődnek.", "Changes are saved automatically.", "Änderungen werden automatisch gespeichert."),
                style = MaterialTheme.typography.bodySmall,
                color = Neon.TextDim,
            )

            // ---- Megjelenés
            SettingsCard {
                SectionTitle(tr("Megjelenés", "Appearance", "Darstellung"))
                ChoiceField("Nyelv / Language / Sprache", Lang.OPTIONS, Lang.setting) { Lang.set(it) }
                ChoiceField(tr("Téma", "Theme", "Design"), THEMES, stored.themeMode) { mode -> update { it.copy(themeMode = mode) } }
                ChoiceField(tr("Betűméret", "Text size", "Textgröße"), TEXT_SCALES, stored.textScale) { scale -> update { it.copy(textScale = scale) } }
            }

            // ---- Árforrások
            SettingsCard {
                SectionTitle(tr("Árforrások", "Price sources", "Preisquellen"))
                if (!view.isReady) {
                    IconLine(
                        Icons.Filled.Warning,
                        tr("Legalább egy forrást kapcsolj be.", "Turn on at least one source.", "Aktiviere mindestens eine Quelle."),
                        color = MaterialTheme.colorScheme.error,
                        iconTint = MaterialTheme.colorScheme.error,
                    )
                }
                val freeOn = listOf(stored.googleOn, stored.ryanairOn, stored.wizzOn).count { it }
                CollapsibleSection(
                    title = tr("Kulcs nélkül", "No key needed", "Ohne Schlüssel"),
                    summary = tr("$freeOn / 3 bekapcsolva", "$freeOn of 3 on", "$freeOn von 3 aktiv"),
                ) {
                    Text(
                        tr(
                            "Minden bekapcsolt forrást egyszerre kérdez le, és az összes ajánlatot ár szerint " +
                                "versenyezteti. Ezek nem hivatalos felületek: ha valamelyik megváltozik, átmenetileg " +
                                "hibát jelez, a többi forrás ettől még működik.",
                            "All sources that are on are checked at once, and every offer competes on price. " +
                                "These are unofficial services: if one changes, it may show an error for a while, " +
                                "but the other sources keep working.",
                            "Alle aktivierten Quellen werden gleichzeitig abgefragt, und alle Angebote treten preislich gegeneinander an. " +
                                "Das sind keine offiziellen Schnittstellen: Ändert sich eine, zeigt sie eine Weile einen Fehler, " +
                                "die anderen Quellen funktionieren aber weiter.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Neon.TextDim,
                    )
                    SwitchRow(tr("Google Flights (légitársaságok és irodák)", "Google Flights (airlines and agencies)", "Google Flights (Airlines und Reisebüros)"), stored.googleOn) { on -> update { it.copy(googleOn = on) } }
                    SwitchRow(tr("Ryanair (közvetlenül)", "Ryanair (direct)", "Ryanair (direkt)"), stored.ryanairOn) { on -> update { it.copy(ryanairOn = on) } }
                    SwitchRow(tr("Wizz Air (közvetlenül)", "Wizz Air (direct)", "Wizz Air (direkt)"), stored.wizzOn) { on -> update { it.copy(wizzOn = on) } }
                }

                val keyedOn = listOf(view.useSerpApi, view.useIgnav).count { it }
                CollapsibleSection(
                    title = tr("Még több ár – ingyenes kulccsal", "More prices – with a free key", "Mehr Preise – mit kostenlosem Schlüssel"),
                    summary = tr("$keyedOn / 2 bekapcsolva", "$keyedOn of 2 on", "$keyedOn von 2 aktiv"),
                ) {
                    Text(
                        tr(
                            "Két további kereső, ingyenes kulccsal. Nem kell hozzá szakértőnek lenni: a varázsló lépésről " +
                                "lépésre végigvezet (regisztráció, kulcs kimásolása, kipróbálás).",
                            "Two more search engines, with a free key. No expertise needed: the wizard guides you " +
                                "step by step (sign up, copy the key, test it).",
                            "Zwei weitere Suchmaschinen mit kostenlosem Schlüssel. Du musst kein Profi sein: Der Assistent führt dich " +
                                "Schritt für Schritt (registrieren, Schlüssel kopieren, testen).",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Neon.TextDim,
                    )
                    SwitchRow(tr("SerpApi (megbízhatóbb Google-árak · havi 250 ingyenes)", "SerpApi (more reliable Google prices · 250 free per month)", "SerpApi (zuverlässigere Google-Preise · 250 kostenlos pro Monat)"), stored.serpOn) { on -> update { it.copy(serpOn = on) } }
                    if (stored.serpOn || apiKey.isBlank()) {
                        OutlinedButton(onClick = { keyGuide = KeyProvider.SERPAPI }) {
                            Text(if (apiKey.isBlank()) tr("Kulcs szerzése lépésről lépésre", "Get a key step by step", "Schlüssel Schritt für Schritt holen") else tr("Új kulcs beállítása (varázsló)", "Set up a new key (wizard)", "Neuen Schlüssel einrichten (Assistent)"))
                        }
                    }
                    if (stored.serpOn) SecretField(tr("SerpApi API-kulcs", "SerpApi API key", "SerpApi-API-Schlüssel"), apiKey) { apiKey = it; apiKeyEdited = true }
                    SwitchRow(tr("Ignav (saját adatforrás · 1000 ingyenes)", "Ignav (own data source · 1000 free)", "Ignav (eigene Datenquelle · 1000 kostenlos)"), stored.ignavOn) { on -> update { it.copy(ignavOn = on) } }
                    if (stored.ignavOn || ignavKey.isBlank()) {
                        OutlinedButton(onClick = { keyGuide = KeyProvider.IGNAV }) {
                            Text(if (ignavKey.isBlank()) tr("Kulcs szerzése lépésről lépésre", "Get a key step by step", "Schlüssel Schritt für Schritt holen") else tr("Új kulcs beállítása (varázsló)", "Set up a new key (wizard)", "Neuen Schlüssel einrichten (Assistent)"))
                        }
                    }
                    if (stored.ignavOn) SecretField(tr("Ignav API-kulcs", "Ignav API key", "Ignav-API-Schlüssel"), ignavKey) { ignavKey = it; ignavKeyEdited = true }
                    if (stored.serpOn || stored.ignavOn) {
                        Text(
                            tr("A kulcsok a Google-fiókod rejtett REFI-területén keresztül a többi eszközödre is átkerülnek.", "The keys are copied to your other devices through a hidden REFI area in your Google account.", "Die Schlüssel werden über einen versteckten REFI-Bereich in deinem Google-Konto auf deine anderen Geräte übertragen."),
                            style = MaterialTheme.typography.bodySmall,
                            color = Neon.TextDim,
                        )
                    }
                }
            }

            // ---- Ellenőrzés
            SettingsCard {
                SectionTitle(tr("Ellenőrzés gyakorisága", "Check frequency", "Prüfhäufigkeit"))
                ChoiceField(tr("Automatikus ellenőrzés", "Automatic check", "Automatische Prüfung"), INTERVALS, interval) { hours ->
                    if (hours != Store.settings.value.intervalHours) {
                        update { it.copy(intervalHours = hours) }
                        Platform.current.reschedule()
                    }
                }
                if (view.useSerpApi) {
                    Text(
                        tr("SerpApi: kb. $serpPerMonth keresés/hó (ingyenes keret: 250).", "SerpApi: about $serpPerMonth searches/month (free limit: 250).", "SerpApi: ca. $serpPerMonth Suchen/Monat (kostenloses Kontingent: 250)."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (serpPerMonth > 250) MaterialTheme.colorScheme.error else Neon.TextDim,
                    )
                }
                if (view.useIgnav) {
                    Text(
                        tr("Ignav: kb. $ignavPerMonth kérés/hó (1000 ingyenes, utána fizetős).", "Ignav: about $ignavPerMonth requests/month (1000 free, paid after that).", "Ignav: ca. $ignavPerMonth Anfragen/Monat (1000 kostenlos, danach kostenpflichtig)."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Neon.TextDim,
                    )
                }
                Text(
                    tr(
                        "A kulcs nélküli forrásoknál nincs keret, de túl gyakori lekérdezésnél ideiglenesen " +
                            "letilthatnak. A 6 óránkénti ellenőrzés biztonságos. ",
                        "Sources without a key have no limit, but checking too often can get you temporarily " +
                            "blocked. Checking every 6 hours is safe. ",
                        "Quellen ohne Schlüssel haben kein Limit, aber bei zu häufigen Abfragen kannst du vorübergehend " +
                            "gesperrt werden. Eine Prüfung alle 6 Stunden ist sicher. ",
                    ) + Platform.current.backgroundHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Neon.TextDim,
                )
            }

            // ---- Értesítések: csendes órák
            SettingsCard {
                SectionTitle(tr("Csendes órák", "Quiet hours", "Ruhezeiten"))
                SwitchRow(tr("Éjszaka ne szóljon és ne rezegjen", "No sound or vibration at night", "Nachts kein Ton und keine Vibration"), stored.quietOn) { on -> update { it.copy(quietOn = on) } }
                if (stored.quietOn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { ChoiceField(tr("Ettől", "From", "Von"), QUIET_HOURS, stored.quietFrom) { h -> update { it.copy(quietFrom = h) } } }
                        Box(Modifier.weight(1f)) { ChoiceField(tr("Eddig", "Until", "Bis"), QUIET_HOURS, stored.quietTo) { h -> update { it.copy(quietTo = h) } } }
                    }
                    if (stored.quietFrom == stored.quietTo) {
                        Text(
                            tr("A kezdő és a záró óra azonos: így a csendes órák nem működnek.", "Start and end hours are the same, so quiet hours won't work.", "Start- und Endzeit sind gleich, daher funktionieren die Ruhezeiten nicht."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        Platform.current.quietHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Neon.TextDim,
                    )
                }
            }

            // ---- Általános: pénznem, indítás, tippek
            SettingsCard {
                SectionTitle(tr("Általános", "General", "Allgemein"))
                ChoiceField(tr("Árak pénzneme", "Price currency", "Währung der Preise"), CURRENCIES, currency) { to ->
                    if (to != currency) confirmCurrency = to
                }
                Text(
                    tr("Pénznemváltáskor az eddigi árelőzmények törlődnek.", "Changing the currency clears the price history so far.", "Beim Wechsel der Währung wird der bisherige Preisverlauf gelöscht."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Neon.TextDim,
                )
                if (Platform.current.autostartSupported) {
                    var auto by remember { mutableStateOf(Platform.current.autostart) }
                    SwitchRow(tr("Indítás a Windows-zal (a tálcán, a háttérben figyel)", "Start with Windows (watches in the background, from the tray)", "Mit Windows starten (beobachtet im Hintergrund, aus dem Infobereich)"), auto) {
                        auto = it
                        // (a rendszerleíró-adatbázis írása lassú lehet: ne akassza meg az ablakot)
                        AppScope.scope.launch { autostartLock.withLock { Platform.current.autostart = auto } }
                    }
                }
                run {
                    var tipsOn by remember { mutableStateOf(!Tips.allOff) }
                    SwitchRow(tr("Tippek a figyelés szerkesztésekor", "Tips while editing a watch", "Tipps beim Bearbeiten einer Beobachtung"), tipsOn) {
                        tipsOn = it
                        Tips.allOff = !it
                        if (it) Tips.showAgain()
                    }
                }
            }

            // ---- Szinkronizálás
            SettingsCard {
                SectionTitle(tr("Szinkronizálás", "Sync", "Synchronisierung"))
                SyncSection()
            }

            // ---- Verzió
            SettingsCard {
                SectionTitle(tr("Verzió", "Version", "Version"))
                Text(
                    tr("Telepítve: ${Updater.currentVersion}.", "Installed: ${Updater.currentVersion}.", "Installiert: ${Updater.currentVersion}."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                var updateMsg by remember { mutableStateOf<String?>(null) }
                if (Platform.current.updatesViaStore) Text(
                    tr("A frissítéseket a Google Play telepíti.", "Updates are installed by Google Play.", "Updates werden über Google Play installiert."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Neon.TextDim,
                ) else OutlinedButton(onClick = {
                    updateMsg = tr("Keresés…", "Searching…", "Suche…")
                    AppScope.scope.launch {
                        val found = Updater.check()
                        updateMsg = if (found == null) tr("Ez a legfrissebb verzió (vagy nem érhető el a GitHub).", "This is the latest version (or GitHub can't be reached).", "Das ist die neueste Version (oder GitHub ist nicht erreichbar).") else null
                    }
                }) { Text(tr("Frissítés keresése", "Check for updates", "Nach Updates suchen")) }
                updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A Beállítások egy csoportja: halvány kártya, egységes belső térközzel. */
@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Neon.Surface, RoundedCornerShape(16.dp))
            .border(0.5.dp, Neon.Line, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Lenyitható alcsoport (alapból csukva): fejléc egysoros összefoglalóval és nyíllal. */
@Composable
private fun CollapsibleSection(
    title: String,
    summary: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) tr("Becsukás", "Collapse", "Einklappen") else tr("Kinyitás", "Expand", "Ausklappen"),
                tint = Neon.TextDim,
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(300)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(if (show) tr("Elrejt", "Hide", "Verbergen") else tr("Mutat", "Show", "Anzeigen")) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------- Közös elemek

@Composable
internal fun AirportField(label: String, selected: Place?, onSelect: (Place?) -> Unit) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(selected?.fieldText ?: "") }
    var expanded by remember { mutableStateOf(false) }
    val results = remember(text, selected) {
        if (selected != null && text == selected.fieldText) emptyList()
        else Airports.search(text)
    }
    val showMenu = expanded && results.isNotEmpty()

    ExposedDropdownMenuBox(expanded = showMenu, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                expanded = true
                if (selected != null) onSelect(null)
            },
            label = { Text(label) },
            placeholder = { Text(tr("Város, repülőtér vagy kód", "City, airport or code", "Stadt, Flughafen oder Code")) },
            supportingText = {
                Text(
                    when {
                        selected != null -> selected.detail
                        text.isNotBlank() && results.isEmpty() -> tr("Nincs találat", "No results", "Keine Treffer")
                        else -> tr("Kezdj el gépelni, pl. Budapest, London, Bécs", "Start typing, e.g. Budapest, London, Vienna", "Tippe los, z. B. Budapest, London, Wien")
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        onSelect(null)
                        expanded = false
                    }) { Icon(Icons.Filled.Clear, contentDescription = tr("Törlés", "Clear", "Leeren")) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = showMenu, onDismissRequest = { expanded = false }) {
            results.forEach { place ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                place.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                place.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        text = place.fieldText
                        onSelect(place)
                        expanded = false
                        focusManager.clearFocus()
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.glow(radius = 10f),
        color = Neon.Green,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Stepper(label: String, hint: String?, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalIconButton(
            onClick = { onChange(value - 1) },
            enabled = value > range.first,
            modifier = Modifier.semantics { contentDescription = tr("$label: kevesebb", "$label: fewer", "$label: weniger") },
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
        }
        Text(
            "$value",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp),
        )
        FilledTonalIconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = tr("$label: több", "$label: more", "$label: mehr"))
        }
    }
}

@Composable
internal fun <T> ChoiceField(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "", modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    minDate: LocalDate,
    maxDate: LocalDate = maxTravelDate(LocalDate.now()),
    onValidChange: (Boolean) -> Unit = {},
    onPick: (LocalDate) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(date.format(typedDateFormat)) }
    var error by remember { mutableStateOf<String?>(null) }
    // A hibás beírást a Mentés is lássa (különben csendben a korábbi dátum mentődne)
    LaunchedEffect(error) { onValidChange(error == null) }

    fun rangeError(d: LocalDate): String? = when {
        d.isBefore(minDate) -> tr("Legkorábban: ${minDate.format(typedDateFormat)}", "Earliest: ${minDate.format(typedDateFormat)}", "Frühestens: ${minDate.format(typedDateFormat)}")
        d.isAfter(maxDate) -> tr("Legkésőbb: ${maxDate.format(typedDateFormat)}", "Latest: ${maxDate.format(typedDateFormat)}", "Spätestens: ${maxDate.format(typedDateFormat)}")
        else -> null
    }

    // Ha a dátum máshonnan változik (pl. naptárból vagy az indulás eltolja a visszautat), frissüljön a mező.
    // A hibaüzenet a tartomány (legkorábbi / legkésőbbi nap) szerint újraértékelődik: pl. ha az
    // indulás eltolása a visszautat a megadható utolsó nap utánra viszi, az piros lesz
    LaunchedEffect(date, minDate, maxDate) {
        val typed = parseTypedDate(text)
        if (typed != date) text = date.format(typedDateFormat)
        error = rangeError(date)
    }
    // Nyelvváltáskor a mező az új nyelv dátumformátumát mutassa
    val langCode = Lang.code
    LaunchedEffect(langCode) {
        if (parseTypedDate(text) == date) text = date.format(typedDateFormat)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() || it in ".-/ " }.take(12)
            val parsed = parseTypedDate(text)
            error = if (parsed == null) {
                tr("Formátum: 2026.10.16", "Format: 2026-10-16 (year-month-day)", "Format: 2026-10-16 (Jahr-Monat-Tag)")
            } else {
                rangeError(parsed)
            }
            if (parsed != null && !parsed.isBefore(minDate) && !parsed.isAfter(maxDate)) onPick(parsed)
        },
        label = { Text(label) },
        placeholder = { Text(tr("éééé.hh.nn", "yyyy-mm-dd", "JJJJ-MM-TT")) },
        supportingText = {
            Text(error ?: date.format(DateTimeFormatter.ofPattern("EEEE", Lang.locale)))
        },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.DateRange, contentDescription = tr("Naptár megnyitása", "Open calendar", "Kalender öffnen"), tint = Neon.Green)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (open) {
        val minMillis = minDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val hi = if (maxDate.isBefore(minDate)) minDate else maxDate
        val maxMillis = hi.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Az évtartománynak a kijelölt dátumot is tartalmaznia kell, különben a naptár összeomlik
        val shown = date.coerceIn(minDate, hi)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minDate.year..hi.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in minMillis..maxMillis
                override fun isSelectableYear(year: Int): Boolean = year in minDate.year..hi.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(tr("Mégse", "Cancel", "Abbrechen")) } },
        ) {
            DatePicker(state = state)
        }
    }
}
