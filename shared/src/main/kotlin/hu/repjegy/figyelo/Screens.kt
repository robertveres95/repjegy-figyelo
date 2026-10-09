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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
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

private val dateFormat = DateTimeFormatter.ofPattern("yyyy. MMM d., EEE", HU)
private val typedDateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd")

/** A legkésőbbi megadható utazási nap (a légitársaságok kb. egy évre előre árulnak). */
internal fun maxTravelDate(today: LocalDate): LocalDate = today.plusMonths(18)

/** Begépelt dátum: 2026.10.16, 2026-10-16, 2026/10/16, 2026.10.16. vagy 2026. 10. 16. */
internal fun parseTypedDate(raw: String): LocalDate? {
    val nums = raw.split('.', '-', '/', ' ').filter { it.isNotBlank() }
    if (nums.size != 3 || nums[0].length != 4) return null
    return runCatching { LocalDate.of(nums[0].toInt(), nums[1].toInt(), nums[2].toInt()) }.getOrNull()
}
private val shortDate = DateTimeFormatter.ofPattern("MMM d.", HU)
private val timeFormat = DateTimeFormatter.ofPattern("MMM d. HH:mm", HU)

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    val update by Updater.available.collectAsState()
    var showSplash by remember { mutableStateOf(AppScope.splashPending) }
    Platform.current.BackHandler(enabled = screen != Screen.Home) {
        screen = (screen as? Screen.Edit)?.back ?: Screen.Home
    }

    LaunchedEffect(Unit) { AppScope.scope.launch { Updater.check() } }
    // Szinkronizálás induláskor és minden visszatéréskor (a másik eszköz módosításai)
    val resumes by AppScope.resumeCount.collectAsState()
    LaunchedEffect(resumes) { AppScope.scope.launch { Sync.syncNow() } }
    val sync by Sync.state.collectAsState()
    // Ha a bejelentkezés nem sikerül (pl. nincs net, nincs Google Play), egy hiba után erre az
    // indításra tovább lehet lépni – a figyelések addig is használhatók; a következő indításkor újra kéri
    var loginSkipped by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val loginGate = !showSplash && !sync.enabled && !loginSkipped
    // Ami alatta van, csak akkor reagálhat (pl. megosztott kód), ha semmi sem takarja
    val uncovered = !showSplash && !loginGate && update == null
    // Új verzió első megnyitásakor: mi változott (a bejelentkezés után, frissítési kérés nélkül)
    var whatsNew by remember { mutableStateOf(runCatching { WhatsNew.pending() }.getOrDefault(emptyList())) }

    Box(Modifier.fillMaxSize().background(Neon.Black)) {
        AnimatedContent(
            targetState = screen,
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

        AnimatedVisibility(visible = uncovered && whatsNew.isNotEmpty(), enter = fadeIn(tween(500)), exit = fadeOut(tween(300))) {
            WhatsNewOverlay(whatsNew) {
                WhatsNew.markSeen()
                whatsNew = emptyList()
            }
        }

        // A kötelező frissítés mindennél előrébb való (régi verzióval a bejelentkezés sem biztos, hogy működik)
        AnimatedVisibility(visible = update != null && !showSplash, enter = fadeIn(tween(400)), exit = fadeOut()) {
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
            .padding(24.dp),
    ) {
        NeonCard(pulse = true, modifier = Modifier.fillMaxWidth().enterAnimation()) {
            Text(
                "ÚJ VERZIÓ",
                style = MaterialTheme.typography.headlineMedium.glow(),
                color = Neon.Green,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().scale(pulse),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Megjelent a REFI ${release.version}.\n" +
                    "A használathoz frissítened kell.",
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
                "😎Nyugi a figyeléseid megmaradnak😎",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = { Platform.current.openUrl(release.apkUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Letöltés és frissítés", fontWeight = FontWeight.Bold)
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza", tint = Neon.Green)
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
                            contentDescription = "Összes ellenőrzése",
                            modifier = if (checking.isNotEmpty()) Modifier.rotate(angle) else Modifier,
                        )
                    }
                    IconButton(onClick = onExplore) {
                        Icon(Icons.Filled.Search, contentDescription = "Felfedezés: hova repülhetek olcsón?")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Beállítások")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Továbbiak")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Felfedezés – hova repülhetek olcsón?") },
                                onClick = { menuOpen = false; onExplore() },
                            )
                            DropdownMenuItem(
                                text = { Text("Kód beillesztése") },
                                onClick = {
                                    menuOpen = false
                                    codeDialog = Platform.current.readClipboard()?.takeIf { it.contains("REFI1:") } ?: ""
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Mentés fájlba") },
                                onClick = {
                                    menuOpen = false
                                    val content = Backup.export(Store.watches.value, Store.settings.value)
                                    Platform.current.exportFile(Backup.fileName(), content) { ok ->
                                        toast = if (ok) "Mentve: ${Store.watches.value.size} figyelés." else "A mentés nem sikerült."
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Visszaállítás fájlból") },
                                onClick = {
                                    menuOpen = false
                                    Platform.current.importFile { text ->
                                        if (text == null) return@importFile
                                        val parsed = Backup.parse(text)
                                        if (parsed == null) {
                                            toast = "Ez nem REFI-mentésfájl."
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
                                            toast = "Visszaállítva: $added új, $updated frissített figyelés" +
                                                (if (parsed.skipped > 0) " (${parsed.skipped} hibás kihagyva)." else ".")
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
                text = { Text("ÚJ FIGYELÉS", fontWeight = FontWeight.Bold) },
                modifier = Modifier.border(0.8.dp, Neon.Green.copy(alpha = borderAlpha), fabShape),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
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
                        "Még nincs figyelt út. Az „Új figyelés” gombbal adhatsz hozzá egyet.",
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
                        if (copied) toast = "A figyelés kódja a vágólapra került – illeszd be egy üzenetbe."
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
            Text("Nincs bekapcsolt árforrás", style = MaterialTheme.typography.titleMedium, color = Neon.Amber)
            Text(
                "Kapcsolj be legalább egy árforrást a Beállításokban (a Google Flights, a Ryanair " +
                    "és a Wizz Air kulcs nélkül működik).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onSettings) { Text("Beállítások") }
        }
    }
}

@Composable
private fun BlockedNotificationsCard() {
    NeonCard(color = Neon.Amber, modifier = Modifier.fillMaxWidth().enterAnimation()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Az értesítések le vannak tiltva", style = MaterialTheme.typography.titleMedium, color = Neon.Amber)
            Text(
                "Így árriasztás sem érkezik, akkor sem, ha a csengő be van kapcsolva. " +
                    "Engedélyezd az értesítéseket a REFI-nek.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { Platform.current.openNotificationSettings() }) { Text("Értesítések engedélyezése") }
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
) {
    val good = Neon.Green
    val best = w.bestOffer
    // Ugyanaz a szabály, mint a riasztásnál (poggyász, hiányos ár), hogy a kártya ne mondjon mást
    val belowTarget = best != null && w.alertable(best)
    var showAll by remember { mutableStateOf(false) }

    NeonCard(
        modifier = modifier.fillMaxWidth(),
        pulse = belowTarget,
        scanning = isChecking,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    w.routeTitle,
                    style = MaterialTheme.typography.titleLarge.glow(radius = 12f),
                    color = Neon.Green,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Figyelés megosztása", tint = Neon.TextDim)
                }
                BellToggle(on = w.notify, onToggle = onToggleNotify)
            }
            Text(dateLine(w), style = MaterialTheme.typography.bodyMedium)
            Text(
                detailLine(w),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Legolcsóbb most", style = MaterialTheme.typography.labelMedium)
                    // Az ár „pörögve” változik az új értékre
                    // Üres állapotból vagy pénznemváltás után nem „pörög fel” nulláról / a régi számról
                    val priceAnim = remember(currency) { androidx.compose.animation.core.Animatable((best?.price ?: 0).toFloat()) }
                    LaunchedEffect(best?.price, currency) {
                        val p = best?.price ?: return@LaunchedEffect
                        if (priceAnim.value <= 0f) priceAnim.snapTo(p.toFloat())
                        else priceAnim.animateTo(p.toFloat(), tween(900, easing = FastOutSlowInEasing))
                    }
                    Text(
                        if (best != null) formatPrice(priceAnim.value.roundToInt(), currency) else "—",
                        style = if (belowTarget) MaterialTheme.typography.headlineMedium.glow(radius = 24f)
                        else MaterialTheme.typography.headlineMedium,
                        color = if (belowTarget) good else Neon.Text,
                    )
                    if (belowTarget) {
                        Text("▼ CÉLÁR ALATT", style = MaterialTheme.typography.labelSmall, color = Neon.Mint)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Célár: ${formatPrice(w.targetPrice, currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    w.lowestPrice?.let {
                        Text(
                            "Eddigi min.: ${formatPrice(it, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // „Most vegyem vagy várjak?” – az eddigi árak alapján
            verdictFor(w)?.let { v ->
                Text(
                    v.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (v.tone) {
                        Verdict.Tone.GOOD -> Neon.Mint
                        Verdict.Tone.WAIT -> Neon.Amber
                        Verdict.Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (best != null) {
                OfferDetails(best, highlight = true)
            }

            // Árgörbe: a Google árelőzménye + a saját mérések (ha van mit mutatni)
            PriceChart(w, currency, Modifier.fillMaxWidth().padding(top = 10.dp))

            Spacer(Modifier.height(6.dp))
            when {
                w.isExpired() -> StatusText("Az indulás dátuma elmúlt, a figyelés szünetel.", true)
                w.lastError != null -> StatusText(w.lastError, true)
                w.lastChecked != null -> StatusText(
                    "Utoljára ellenőrizve: " +
                        Instant.ofEpochMilli(w.lastChecked).atZone(ZoneId.systemDefault()).format(timeFormat),
                    false,
                )
                else -> StatusText("Még nem volt ellenőrzés.", false)
            }
            if (w.sourceStatus.isNotEmpty()) {
                Text(
                    w.sourceStatus.joinToString("  ·  ") { s ->
                        "${s.source} ${if (s.ok) "✓" else "✗"} ${s.text}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (w.sourceStatus.any { !it.ok }) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            AnimatedVisibility(
                visible = showAll && w.offers.size > 1,
                enter = expandVertically(tween(350)) + fadeIn(tween(350)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(200)),
            ) {
              Column {
                Spacer(Modifier.height(8.dp))
                Text("ÖSSZES AJÁNLAT", style = MaterialTheme.typography.titleMedium, color = Neon.Green)
                w.offers.forEachIndexed { index, offer ->
                    Column(
                        Modifier
                            .padding(top = 10.dp)
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
                                TextButton(onClick = { onOpen(url) }) { Text("Megnyitás") }
                            }
                        }
                        OfferDetails(offer, highlight = false)
                    }
                }
              }
            }

            // Nagy betűméretnél a gombok új sorba törnek (nem a szavak közepén)
            androidx.compose.foundation.layout.FlowRow {
                val center = Modifier.align(Alignment.CenterVertically)
                if (isChecking) {
                    CircularProgressIndicator(
                        center.padding(12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                        color = Neon.Green,
                    )
                    Text("KERESÉS…", style = MaterialTheme.typography.labelSmall, color = Neon.Green, modifier = center)
                } else {
                    TextButton(onClick = onCheck, enabled = canCheck && !w.isExpired(), modifier = center) {
                        Text("Ellenőrzés", maxLines = 1)
                    }
                }
                best?.url?.let { url ->
                    TextButton(onClick = { onOpen(url) }, modifier = center) { Text("Megnyitás", maxLines = 1) }
                }
                TextButton(onClick = onEdit, modifier = center) { Text("Szerkesztés", maxLines = 1) }
                if (w.offers.size > 1) {
                    TextButton(onClick = { showAll = !showAll }, modifier = center) {
                        Text(if (showAll) "Kevesebb" else "Mind a ${w.offers.size} ajánlat", maxLines = 1)
                    }
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
        if (out != null) LegBlock("INDULÁS", out, highlight) else {
            Text("Indulási idő: a forrás nem adta meg", style = MaterialTheme.typography.bodyLarge)
        }
        if (ret != null) {
            Spacer(Modifier.height(6.dp))
            LegBlock("VISSZA", ret, highlight)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(offer.airline, "forrás: ${offer.source}").joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        offer.note?.let {
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
    val flex = if (w.flexDays > 0) " (±${w.flexDays} nap)" else ""
    return if (ret != null) "$out – $ret$flex · oda-vissza" else "$out$flex · csak oda"
}

private fun detailLine(w: Watch): String {
    val parts = mutableListOf<String>()
    parts += "${w.adults} felnőtt"
    if (w.children > 0) parts += "${w.children} gyerek"
    val infants = w.infantsInSeat + w.infantsOnLap
    if (infants > 0) parts += "$infants csecsemő"
    val pax = parts.joinToString(", ")
    val cls = TRAVEL_CLASSES.firstOrNull { it.first == w.travelClass }?.second ?: ""
    val extra = mutableListOf(pax, cls)
    if (w.bags > 0) extra += "${w.bags} kézipoggyász"
    if (w.checkedBag) extra += "feladott poggyász"
    if (w.stops != 0) extra += STOP_OPTIONS.firstOrNull { it.first == w.stops }?.second ?: ""
    if (w.depFrom != null || w.depTo != null) {
        extra += "indulás " + listOfNotNull(
            w.depFrom?.let { "%02d:00-tól".format(it) },
            w.depTo?.let { "%02d:00-ig".format(it) },
        ).joinToString(" ")
    }
    if (w.airlineTokens.isNotEmpty()) extra += "csak: ${w.airlines.trim()}"
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
            !outValid || (roundTrip && !retValid) -> "Javítsd a pirossal jelölt dátumot."
            from == null -> "Válaszd ki az indulási repülőteret a listából."
            to == null -> "Válaszd ki az érkezési repülőteret a listából."
            from.codes.split(',').any { it in to.codes.split(',') } ->
                "Az indulási és érkezési hely nem lehet ugyanaz."
            // Rugalmas dátumnál elég, ha a tartomány még nem múlt el
            outDate.plusDays(flexDays.toLong()).isBefore(today) -> "Az indulás dátuma nem lehet a múltban."
            outDate.isAfter(maxTravelDate(today)) || (roundTrip && retDate.isAfter(maxTravelDate(today))) ->
                "Legfeljebb ${maxTravelDate(today).format(typedDateFormat)}-ig lehet dátumot megadni."
            roundTrip && retDate.isBefore(outDate) -> "A visszaút nem lehet az indulás előtt."
            adults + children + infantsInSeat + infantsOnLap > 9 -> "Legfeljebb 9 utas adható meg."
            targetValue == null -> "Adj meg egy célárat."
            targetValue <= 0 -> "A célár legyen nagyobb 0-nál."
            depFrom != null && depTo != null && depTo!! <= depFrom!! -> "Az indulási időablak vége legyen későbbi, mint az eleje."
            else -> null
        }
        if (error != null || targetValue == null || from == null || to == null) return
        val stillThere = existing != null && Store.watches.value.any { it.id == existing.id }
        if (existing != null && !stillThere && !recreate) {
            error = "Ezt a figyelést közben törölték (pl. a másik eszközödön). Ha mégis kell, nyomd meg újra a Mentést."
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
            flexDays = flexDays,
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
                    lastError = it.lastError?.takeUnless { e -> e.startsWith(PriceChecker.CURRENCY_HINT_PREFIX) },
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
            NeonTopBar(if (existing == null) "ÚJ FIGYELÉS" else "SZERKESZTÉS", onBack = onDone)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Tippek és trükkök (pl. ingyenes kulcs a több árhoz – végigvezetünk rajta)
            TipBubble(onOpenGuide = { guide = it })
            guide?.let { p -> KeyGuideDialog(p, onClose = { guide = null }) }
            SectionTitle("Útvonal")
            AirportField("Honnan", fromPlace) { fromPlace = it }
            AirportField("Hova", toPlace) { toPlace = it }
            SwitchRow("Oda-vissza út", roundTrip) { roundTrip = it }

            SectionTitle("Dátum")
            DateField("Indulás", outDate, minDate = today.minusDays(flexDays.toLong()), onValidChange = { outValid = it }) {
                // Az út hossza marad: ha az indulás eltolódik, a visszaút vele mozog
                // (gépelés közbeni részleges dátumnál sem vész el az eredeti hossz)
                val days = java.time.temporal.ChronoUnit.DAYS.between(outDate, retDate).coerceAtLeast(0)
                outDate = it
                retDate = it.plusDays(days)
            }
            if (roundTrip) {
                DateField("Visszaút", retDate, minDate = outDate, onValidChange = { retValid = it }) { retDate = it }
            }
            ChoiceField("Rugalmasság", FLEX_OPTIONS, flexDays) { flexDays = it }
            if (flexDays > 0) {
                Text(
                    "A megadott naptól ±$flexDays napon belül keresi a legolcsóbbat (az út hossza marad).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Utasok és osztály")
            ChoiceField("Osztály", TRAVEL_CLASSES, travelClass) { travelClass = it }
            Stepper("Felnőtt", "12 év felett", adults, 1..9) { adults = it }
            Stepper("Gyerek", "2–11 év", children, 0..8) { children = it }
            Stepper("Csecsemő saját ülésen", "2 év alatt", infantsInSeat, 0..4) { infantsInSeat = it }
            Stepper("Csecsemő ölben", "2 év alatt, felnőttenként 1", infantsOnLap, 0..adults) { infantsOnLap = it }

            SectionTitle("Poggyász és átszállás")
            Stepper("Kézipoggyász", "összesen, minden utasra", bags, 0..maxBags) { bags = it }
            SwitchRow("Feladott poggyász (utasonként 1)", checkedBag) { checkedBag = it }
            Text(
                "A fapadosoknál (Ryanair, Wizz Air, easyJet…) a poggyász díját becsült összeggel " +
                    "adom hozzá az árhoz. A hagyományos légitársaságoknál úgy számolok, hogy a " +
                    "poggyász benne van a jegyárban (a legolcsóbb „light” jegyeknél ez nem mindig igaz).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceField("Átszállás", STOP_OPTIONS, stops) { stops = it }

            SectionTitle("Szűrők")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { ChoiceField("Indulás legkorábban", HOUR_FROM_OPTIONS, depFrom) { depFrom = it } }
                Box(Modifier.weight(1f)) { ChoiceField("Indulás legkésőbb", HOUR_TO_OPTIONS, depTo) { depTo = it } }
            }
            OutlinedTextField(
                value = airlines,
                onValueChange = { airlines = it.take(80) },
                label = { Text("Csak ezek a légitársaságok") },
                placeholder = { Text("pl. Wizz, Ryanair – üresen: bármelyik") },
                supportingText = { Text("Vesszővel elválasztva; elég a név része is") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("Riasztás")
            OutlinedTextField(
                value = target,
                onValueChange = { v ->
                    // Tizedesrész (pl. beillesztett „89,99”) ne szorozza százzal az árat
                    val whole = v.trim().replace(Regex("[.,]\\d{1,2}$"), "")
                    target = whole.filter(Char::isDigit).take(9)
                },
                label = { Text("Célár (${currencySymbol(currency)})") },
                supportingText = { Text("Szólunk, ha a teljes ár (minden utassal) eddig vagy ez alá esik") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Értesítés küldése", notify) { notify = it }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { save() }, enabled = !saved, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
            if (confirmDelete && existing != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Törlöd a figyelést?") },
                    text = { Text("${existing.routeTitle} – az árelőzményekkel együtt, minden eszközödről.") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            saved = true
                            Store.delete(existing.id)
                            onDone()
                        }) { Text("Törlés", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Mégse") } },
                )
            }
            if (existing != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !saved,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Figyelés törlése") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Beállítások

@Composable
internal fun SettingsScreen(onDone: () -> Unit) {
    val initial = remember { Store.settings.value }
    var googleOn by remember { mutableStateOf(initial.googleOn) }
    var ryanairOn by remember { mutableStateOf(initial.ryanairOn) }
    var wizzOn by remember { mutableStateOf(initial.wizzOn) }
    var serpOn by remember { mutableStateOf(initial.serpOn) }
    var ignavOn by remember { mutableStateOf(initial.ignavOn) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var ignavKey by remember { mutableStateOf(initial.ignavKey) }
    var keyGuide by remember { mutableStateOf<KeyProvider?>(null) }
    var currency by remember { mutableStateOf(initial.currency) }
    var interval by remember { mutableStateOf(initial.intervalHours) }
    var themeMode by remember { mutableStateOf(initial.themeMode) }
    var textScale by remember { mutableStateOf(initial.textScale) }
    var quietOn by remember { mutableStateOf(initial.quietOn) }
    var quietFrom by remember { mutableStateOf(initial.quietFrom) }
    var quietTo by remember { mutableStateOf(initial.quietTo) }
    val draft = initial.copy(
        googleOn = googleOn, ryanairOn = ryanairOn, wizzOn = wizzOn, serpOn = serpOn, ignavOn = ignavOn,
        apiKey = apiKey, ignavKey = ignavKey, currency = currency, intervalHours = interval,
        themeMode = themeMode, textScale = textScale,
        quietOn = quietOn, quietFrom = quietFrom, quietTo = quietTo,
    )

    val watches by Store.watches.collectAsState()
    val activeWatches = watches.filter { !it.isExpired() }
    val checksPerMonth = if (interval > 0) (24 / interval) * 30 else 0
    val serpPerMonth = if (draft.useSerpApi) activeWatches.size * checksPerMonth else 0
    val ignavPerMonth = if (draft.useIgnav) {
        activeWatches.sumOf {
            (it.from.split(',').size * it.to.split(',').size).coerceAtMost(Ignav.MAX_PAIRS)
        } * checksPerMonth
    } else 0

    Scaffold(
        containerColor = Neon.Black,
        topBar = {
            NeonTopBar("BEÁLLÍTÁSOK", onBack = onDone)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Megjelenés")
            ChoiceField("Téma", THEMES, themeMode) { mode ->
                themeMode = mode
                // Azonnal látszik, mentés nélkül is
                Store.saveSettings(Store.settings.value.copy(themeMode = mode))
            }
            ChoiceField("Betűméret", TEXT_SCALES, textScale) { scale ->
                textScale = scale
                Store.saveSettings(Store.settings.value.copy(textScale = scale))
            }

            SectionTitle("Árforrások – kulcs nélkül")
            Text(
                "Minden bekapcsolt forrást egyszerre kérdez le, és az összes ajánlatot ár szerint " +
                    "versenyezteti. Ezek nem hivatalos felületek: ha valamelyik megváltozik, átmenetileg " +
                    "hibát jelez, a többi forrás ettől még működik.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Google Flights (légitársaságok és irodák)", googleOn) { googleOn = it }
            SwitchRow("Ryanair (közvetlenül)", ryanairOn) { ryanairOn = it }
            SwitchRow("Wizz Air (közvetlenül)", wizzOn) { wizzOn = it }

            SectionTitle("Még több ár – ingyenes kulccsal")
            Text(
                "Két további kereső, ingyenes kulccsal. Nem kell hozzá szakértőnek lenni: a varázsló lépésről " +
                    "lépésre végigvezet (regisztráció, kulcs kimásolása, kipróbálás).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            keyGuide?.let { p ->
                KeyGuideDialog(p, onClose = { keyGuide = null }, onSaved = { k ->
                    // A varázsló már elmentette; a képernyő vázlata is frissüljön, hogy a Mentés ne írja felül
                    when (p) {
                        KeyProvider.SERPAPI -> { apiKey = k; serpOn = true }
                        KeyProvider.IGNAV -> { ignavKey = k; ignavOn = true }
                    }
                })
            }
            SwitchRow("SerpApi (megbízhatóbb Google-árak · havi 250 ingyenes)", serpOn) { serpOn = it }
            if (serpOn || apiKey.isBlank()) {
                OutlinedButton(onClick = { keyGuide = KeyProvider.SERPAPI }) {
                    Text(if (apiKey.isBlank()) "Kulcs szerzése lépésről lépésre" else "Új kulcs beállítása (varázsló)")
                }
            }
            if (serpOn) SecretField("SerpApi API-kulcs", apiKey) { apiKey = it }
            SwitchRow("Ignav (saját adatforrás · 1000 ingyenes)", ignavOn) { ignavOn = it }
            if (ignavOn || ignavKey.isBlank()) {
                OutlinedButton(onClick = { keyGuide = KeyProvider.IGNAV }) {
                    Text(if (ignavKey.isBlank()) "Kulcs szerzése lépésről lépésre" else "Új kulcs beállítása (varázsló)")
                }
            }
            if (ignavOn) SecretField("Ignav API-kulcs", ignavKey) { ignavKey = it }
            if (serpOn || ignavOn) {
                Text(
                    "A kulcsok a Google-fiókod rejtett REFI-területén keresztül a többi eszközödre is átkerülnek.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!draft.isReady) {
                Text(
                    "Legalább egy forrást kapcsolj be.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionTitle("Pénznem")
            ChoiceField("Árak pénzneme", CURRENCIES, currency) { currency = it }
            if (currency != initial.currency) {
                Text(
                    "Pénznemváltáskor az eddigi árelőzmények törlődnek.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Ellenőrzés gyakorisága")
            ChoiceField("Automatikus ellenőrzés", INTERVALS, interval) { interval = it }
            if (draft.useSerpApi) {
                Text(
                    "SerpApi: kb. $serpPerMonth keresés/hó (ingyenes keret: 250).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (serpPerMonth > 250) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (draft.useIgnav) {
                Text(
                    "Ignav: kb. $ignavPerMonth kérés/hó (1000 ingyenes, utána fizetős).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "A kulcs nélküli forrásoknál nincs keret, de túl gyakori lekérdezésnél ideiglenesen " +
                    "letilthatnak. A 6 óránkénti ellenőrzés biztonságos. " + Platform.current.backgroundHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Szinkronizálás")
            SyncSection()

            SectionTitle("Csendes órák")
            SwitchRow("Éjszaka ne szóljon és ne rezegjen", quietOn) { quietOn = it }
            if (quietOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { ChoiceField("Ettől", QUIET_HOURS, quietFrom) { quietFrom = it } }
                    Box(Modifier.weight(1f)) { ChoiceField("Eddig", QUIET_HOURS, quietTo) { quietTo = it } }
                }
                if (quietFrom == quietTo) {
                    Text(
                        "A kezdő és a záró óra azonos: így a csendes órák nem működnek.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    Platform.current.quietHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Verzió")
            Text(
                "Telepítve: ${Updater.currentVersion}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            var updateMsg by remember { mutableStateOf<String?>(null) }
            OutlinedButton(onClick = {
                updateMsg = "Keresés…"
                AppScope.scope.launch {
                    val found = Updater.check()
                    updateMsg = if (found == null) "Ez a legfrissebb verzió (vagy nem érhető el a GitHub)." else null
                }
            }) { Text("Frissítés keresése") }
            updateMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Neon.TextDim) }

            Button(
                onClick = {
                    // A pénznemet csak a háttérbeli átváltás írja (a célárakkal együtt); itt mindig a tárolt
                    // marad, így egy még futó korábbi váltást sem írunk vissza a régire
                    val stored = Store.settings.value
                    // A kulcsokat csak akkor írjuk, ha itt módosította őket – közben a szinkron vagy a
                    // varázsló már frissíthette (különben a régi vázlat visszaírná a régit)
                    val keysTouched = apiKey != initial.apiKey || serpOn != initial.serpOn ||
                        ignavKey != initial.ignavKey || ignavOn != initial.ignavOn
                    Store.saveSettings(
                        draft.copy(currency = stored.currency).let {
                            if (keysTouched) it else it.copy(apiKey = stored.apiKey, serpOn = stored.serpOn, ignavKey = stored.ignavKey, ignavOn = stored.ignavOn)
                        }
                    )
                    if (currency != initial.currency) {
                        val to = currency
                        AppScope.scope.launch { Store.switchCurrency(to) }
                    }
                    if (interval != initial.intervalHours) Platform.current.reschedule()
                    onDone()
                },
                enabled = draft.isReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mentés") }
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
            TextButton(onClick = { show = !show }) { Text(if (show) "Elrejt" else "Mutat") }
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
            placeholder = { Text("Város, repülőtér vagy kód") },
            supportingText = {
                Text(
                    when {
                        selected != null -> selected.subtitle
                        text.isNotBlank() && results.isEmpty() -> "Nincs találat"
                        else -> "Kezdj el gépelni, pl. Budapest, London, Bécs"
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
                    }) { Icon(Icons.Filled.Clear, contentDescription = "Törlés") }
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
            modifier = Modifier.semantics { contentDescription = "$label: kevesebb" },
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
            Icon(Icons.Filled.Add, contentDescription = "$label: több")
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

    // Ha a dátum máshonnan változik (pl. naptárból vagy az indulás eltolja a visszautat), frissüljön a mező
    // A hibaüzenet a legkorábbi dátum (pl. az indulás) változásakor is újraértékelődik
    LaunchedEffect(date, minDate) {
        val typed = parseTypedDate(text)
        if (typed != date) {
            text = date.format(typedDateFormat)
            error = null
        } else if (error != null && !date.isBefore(minDate) && !date.isAfter(maxDate)) {
            error = null
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() || it in ".-/ " }.take(12)
            val parsed = parseTypedDate(text)
            error = when {
                parsed == null -> "Formátum: 2026.10.16"
                parsed.isBefore(minDate) -> "Legkorábban: ${minDate.format(typedDateFormat)}"
                parsed.isAfter(maxDate) -> "Legkésőbb: ${maxDate.format(typedDateFormat)}"
                else -> null
            }
            if (parsed != null && !parsed.isBefore(minDate) && !parsed.isAfter(maxDate)) onPick(parsed)
        },
        label = { Text(label) },
        placeholder = { Text("éééé.hh.nn") },
        supportingText = {
            Text(error ?: date.format(DateTimeFormatter.ofPattern("EEEE", HU)))
        },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.DateRange, contentDescription = "Naptár megnyitása", tint = Neon.Green)
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
            dismissButton = { TextButton(onClick = { open = false }) { Text("Mégse") } },
        ) {
            DatePicker(state = state)
        }
    }
}
