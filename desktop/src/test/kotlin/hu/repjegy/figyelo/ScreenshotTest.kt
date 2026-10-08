package hu.repjegy.figyelo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.time.LocalDate
import kotlin.test.Test

/**
 * Képernyőképek a felületről, ablak nélkül (-Drefi.screens=true). A diagnosztika feltölti őket,
 * így a fejlesztés közben látszik, hogy néznek ki az új képernyők, és nem lóg-e ki semmi.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {

    private val out = File("build/screens")

    private val failures = mutableListOf<String>()

    private fun shot(name: String, heightDp: Int = 915, theme: String = THEME_NIGHT, textScale: Int = 100, content: @Composable () -> Unit) {
        runCatching { render(name, heightDp, theme, textScale, content) }
            .onFailure { failures += "$name: ${it.javaClass.simpleName}: ${it.message}"; it.printStackTrace() }
    }

    private fun render(name: String, heightDp: Int, theme: String, textScale: Int, content: @Composable () -> Unit) {
        val density = 2f
        val scene = ImageComposeScene(width = (412 * density).toInt(), height = (heightDp * density).toInt(), density = Density(density)) {
            NeonTheme(mode = theme, textScale = textScale) {
                Surface(Modifier.fillMaxSize(), color = Neon.Black) { content() }
            }
        }
        var image = scene.render(0)
        // Néhány képkocka, hogy a rajzolás-animációk (pl. árgörbe) a végállapotba érjenek
        for (i in 1..90) image = scene.render(i * 33_000_000L)
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG hiba")
        out.mkdirs()
        File(out, "$name.png").writeBytes(data.bytes)
        scene.close()
    }

    @Test
    fun screens() {
        if (System.getProperty("refi.screens") != "true") return
        Platform.current = DesktopPlatform
        AppScope.reduceMotion = true
        Store.init(DesktopPrefs)
        Store.watches.value.forEach { Store.delete(it.id) }
        Store.saveSettings(Settings(currency = "HUF", themeMode = THEME_NIGHT, quietOn = true))
        val d = LocalDate.now().plusDays(35)
        val london = Watch(
            id = "s1", from = "BUD", to = "LHR,LGW,STN,LTN,LCY,SEN", fromLabel = "Budapest", toLabel = "London",
            outboundDate = d.toString(), returnDate = d.plusDays(5).toString(), travelClass = 1, adults = 2,
            children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 2, stops = 0, targetPrice = 60000, notify = true,
            flexDays = 2, depFrom = 7,
            lastPrice = 54200, lowestPrice = 54200, lastChecked = System.currentTimeMillis(),
            offers = listOf(
                Offer(54200, "Google Flights", "Wizz Air", "BUD", "LTN", "${d}T12:50", "${d}T14:35", 0,
                    "${d.plusDays(5)}T20:00", "${d.plusDays(5)}T23:30", 0, "https://www.google.com/travel/flights",
                    true, "becsült poggyász-díjjal (+22 010 Ft)"),
                Offer(58900, "Ryanair", "Ryanair", "BUD", "STN", "${d}T17:55", "${d}T19:30", 0, null, null, null,
                    "https://www.ryanair.com"),
            ),
            sourceStatus = listOf(SourceStatus("Google Flights", true, "141 ajánlat"), SourceStatus("Ryanair", true, "1 ajánlat"),
                SourceStatus("Wizz Air", true, "3 ajánlat")),
            history = listOf(68000, 66500, 61200, 59900, 57300, 55800, 54200).mapIndexed { i, p -> PricePoint(i * 21_600_000L, p) },
        )
        val milan = Watch(
            id = "s2", from = "BUD", to = "MXP,LIN,BGY", fromLabel = "Budapest", toLabel = "Milánó",
            outboundDate = d.plusDays(10).toString(), returnDate = null, travelClass = 1, adults = 1, children = 0,
            infantsInSeat = 0, infantsOnLap = 0, bags = 0, checkedBag = true, stops = 1, targetPrice = 12000, notify = true,
            airlines = "Ryanair",
            lastPrice = 18311, lastChecked = System.currentTimeMillis(),
            offers = listOf(Offer(18311, "Google Flights", "Ryanair", "BUD", "BGY", "${d.plusDays(10)}T08:35",
                "${d.plusDays(10)}T10:15", 0, url = "https://www.google.com/travel/flights", note = "becsült poggyász-díjjal (+12 837 Ft)")),
            history = listOf(17000, 16500, 18900, 19500, 18311).mapIndexed { i, p -> PricePoint(i * 21_600_000L, p) },
        )
        val jfk = Watch(
            id = "s3", from = "BUD", to = "JFK", fromLabel = "Budapest", toLabel = "New York",
            outboundDate = d.plusDays(40).toString(), returnDate = d.plusDays(50).toString(), travelClass = 3, adults = 1,
            children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 0, stops = 2, targetPrice = 900000, notify = false,
        )
        listOf(london, milan, jfk).forEach { Store.upsert(it) }

        shot("01-fooldal") { HomeScreen(onAdd = {}, onEdit = {}, onSettings = {}) }
        shot("02-fooldal-nappali", theme = THEME_DAY) { HomeScreen(onAdd = {}, onEdit = {}, onSettings = {}) }
        shot("03-fooldal-extra-nagy-betu", textScale = 130) { HomeScreen(onAdd = {}, onEdit = {}, onSettings = {}) }
        shot("04-szerkesztes", heightDp = 2300) { EditScreen(id = "s1", onDone = {}) }
        shot("05-beallitasok", heightDp = 2100) { SettingsScreen(onDone = {}) }
        val results = listOf(
            Discover.Result("BGY", "Milánó", "Italy", 5474, "${d}T08:35", null, "BUD"),
            Discover.Result("STN", "London", "United Kingdom", 12406, "${d}T17:55", null, "BUD"),
            Discover.Result("CRL", "Brüsszel", "Belgium", 13990, "${d.plusDays(3)}T06:15", null, "BUD"),
            Discover.Result("MLA", "Málta", "Malta", 15200, "${d.plusDays(1)}T21:40", null, "BUD"),
            Discover.Result("PFO", "Ciprus", "Cyprus", 21990, "${d.plusDays(6)}T13:05", null, "BUD"),
        )
        shot("06-felfedezes", heightDp = 1400) { DiscoverScreen(onBack = {}, onPick = {}, initialResults = results) }
        shot("08-bejelentkezes") { LoginGate() }
        val code = ShareCode.message(london, "HUF")
        shot("07-kod-beillesztese") {
            HomeScreen(onAdd = {}, onEdit = {}, onSettings = {})
            ImportCodeDialog(initial = code, onDismiss = {}, onImported = {})
        }
        File(out, "megosztott-uzenet.txt").writeText(code)
        File(out, "hibak.txt").writeText(failures.joinToString("\n").ifEmpty { "nincs" })
        if (failures.isNotEmpty()) error("Képernyőkép-hibák:\n" + failures.joinToString("\n"))
    }
}
