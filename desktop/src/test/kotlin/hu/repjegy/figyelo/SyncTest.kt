package hu.repjegy.figyelo

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A Google-fiókos szinkronizálás összefésülési szabályai (hálózat nélkül). */
class SyncTest {
    @BeforeTest
    fun setUp() {
        Platform.current = DesktopPlatform
    }

    private fun w(id: String, target: Int = 30000, edited: Long = 0, checked: Long? = null, price: Int? = null, to: String = "STN") = Watch(
        id = id, from = "BUD", to = to, outboundDate = "2026-12-01", returnDate = null, travelClass = 1, adults = 1,
        children = 0, infantsInSeat = 0, infantsOnLap = 0, bags = 0, stops = 0, targetPrice = target, notify = true,
        editedAt = edited, lastChecked = checked, lastPrice = price,
        offers = if (price != null) listOf(Offer(price, "x")) else emptyList(),
        history = if (price != null && checked != null) listOf(PricePoint(checked, price)) else emptyList(),
    )

    @Test fun laterEditWins() {
        val (m, _) = Sync.merge(listOf(w("a", target = 100, edited = 10)), emptyMap(), listOf(w("a", target = 200, edited = 20)), emptyMap())
        assertEquals(200, m.single().targetPrice)
        val (m2, _) = Sync.merge(listOf(w("a", target = 100, edited = 30)), emptyMap(), listOf(w("a", target = 200, edited = 20)), emptyMap())
        assertEquals(100, m2.single().targetPrice)
    }

    @Test fun fresherPricesKeptWithNewerSettings() {
        val local = w("a", target = 100, edited = 50, checked = 1000, price = 900)
        val remote = w("a", target = 200, edited = 10, checked = 2000, price = 800)
        val merged = Sync.merge(listOf(local), emptyMap(), listOf(remote), emptyMap()).first.single()
        assertEquals(100, merged.targetPrice, "a helyi beállítás az újabb")
        assertEquals(800, merged.lastPrice, "a távoli ár a frissebb")
        assertEquals(2, merged.history.size, "az árgörbe egyesül")
    }

    @Test fun pricesNotMixedAcrossDifferentSearches() {
        val local = w("a", edited = 50, checked = 1000, price = 900, to = "LTN")
        val remote = w("a", edited = 10, checked = 2000, price = 800, to = "STN")
        val merged = Sync.merge(listOf(local), emptyMap(), listOf(remote), emptyMap()).first.single()
        assertEquals("LTN", merged.to)
        assertEquals(900, merged.lastPrice)
    }

    @Test fun deletionPropagatesButNewerEditSurvives() {
        val base = System.currentTimeMillis()
        val (m, t) = Sync.merge(listOf(w("a", edited = base + 10), w("b", edited = base + 100)), emptyMap(), emptyList(), mapOf("a" to base + 50, "b" to base + 50))
        assertEquals(listOf("b"), m.map { it.id }, "„a” törölve (régebbi módosítás), „b” megmarad (újabb)")
        assertEquals(setOf("a", "b"), t.keys)
    }

    @Test fun newRemoteWatchArrives() {
        val (m, _) = Sync.merge(listOf(w("a")), emptyMap(), listOf(w("a"), w("b")), emptyMap())
        assertEquals(listOf("a", "b"), m.map { it.id })
    }

    @Test fun notificationNotRepeatedAcrossDevices() {
        // A másik eszköz később ellenőrzött és már szólt: ez az eszköz ne szóljon újra ugyanerről
        val local = w("a", edited = 10, checked = 1000, price = 26000).copy(lastNotifiedPrice = null)
        val remote = w("a", edited = 10, checked = 2000, price = 25000).copy(lastNotifiedPrice = 25000)
        val merged = Sync.merge(listOf(local), emptyMap(), listOf(remote), emptyMap()).first.single()
        assertEquals(25000, merged.lastNotifiedPrice)
    }

    @Test fun notificationResetFromFresherDeviceWins() {
        // A frissebben ellenőrző eszközön az ár visszament a célár fölé → a jelzés törlődött
        val fresh = w("a", edited = 10, checked = 2000, price = 40000).copy(lastNotifiedPrice = null)
        val old = w("a", edited = 10, checked = 1000, price = 25000).copy(lastNotifiedPrice = 25000)
        val merged = Sync.merge(listOf(old), emptyMap(), listOf(fresh), emptyMap()).first.single()
        assertNull(merged.lastNotifiedPrice, "különben a következő esésnél egyik eszköz sem szólna")
    }

    @Test fun oldTombstonesArePruned() {
        val ancient = System.currentTimeMillis() - 200L * 24 * 3_600_000L
        val (_, t) = Sync.merge(emptyList(), mapOf("x" to ancient), emptyList(), mapOf("y" to System.currentTimeMillis()))
        assertEquals(setOf("y"), t.keys)
    }

    @Test fun fileRoundTrip() {
        val ws = listOf(w("a", edited = 5, checked = 7, price = 9), w("b"))
        val now = System.currentTimeMillis()
        val text = Sync.serialize(ws, mapOf("x" to now), "EUR")
        val back = assertNotNull(Sync.parse(text))
        assertEquals(ws, back.watches)
        assertEquals(mapOf("x" to now), back.tombstones)
        assertEquals("EUR", back.currency)
        assertNull(Sync.parse("{\"format\":\"más\"}"))
        assertTrue(Sync.parse("szemét") == null)
    }

    @Test fun newerFormatAndBrokenWatchesAreNotSilentlyDropped() {
        // Egy későbbi app-verzió formátumát nem fésüljük össze (különben a régi app felülírná)
        val v2 = Sync.serialize(listOf(w("a")), emptyMap(), "HUF").replace("\"version\":1", "\"version\":${Sync.FORMAT_VERSION + 1}")
        assertNull(Sync.parse(v2))
        // Egy beolvashatatlan figyelés: számon tartjuk, hogy a feltöltés ne törölje ki a felhőből
        val broken = Sync.serialize(listOf(w("a"), w("b")), emptyMap(), "HUF")
            .replaceFirst("\"adults\":1", "\"adults\":0")
        val snap = assertNotNull(Sync.parse(broken))
        assertEquals(1, snap.watches.size)
        assertEquals(1, snap.skipped)
    }

    @Test fun longAbsentDeviceDoesNotResurrectDeletedWatches() {
        // A régóta nem szinkronizált eszközön megvan „a” (régi) és „b” (azóta létrehozott); a felhőben csak „c”
        val lastSync = 1_000L
        val (m, _) = Sync.merge(
            listOf(w("a", edited = 500), w("b", edited = 5_000)), emptyMap(),
            listOf(w("c", edited = 900)), emptyMap(), dropLocalOnlyBefore = lastSync,
        )
        assertEquals(setOf("b", "c"), m.map { it.id }.toSet())
        // Szokásos esetben (nincs hosszú kimaradás) semmi sem vész el
        val (m2, _) = Sync.merge(listOf(w("a", edited = 500)), emptyMap(), listOf(w("c")), emptyMap())
        assertEquals(setOf("a", "c"), m2.map { it.id }.toSet())
    }

    @Test fun keysTravelInSyncFile() {
        val k = Store.SyncedKeys("serp-123", true, 42L, "ign-456", false, 7L)
        val back = assertNotNull(Sync.parse(Sync.serialize(listOf(w("a")), emptyMap(), "HUF", k)))
        assertEquals(k, back.keys)
        // Kulcs nélkül (vagy ha sosem volt beállítva) nem kerül a fájlba
        assertNull(assertNotNull(Sync.parse(Sync.serialize(listOf(w("a")), emptyMap(), "HUF"))).keys)
    }

    @Test fun stampIsMonotonicEvenWithFutureClock() {
        // Egy siető órájú eszköz „jövőbeli” módosítása után a mostani módosítás is későbbi legyen
        val future = System.currentTimeMillis() + 3_600_000L
        assertTrue(Store.stamp(future) > future)
        val now = System.currentTimeMillis()
        assertTrue(Store.stamp(0L) >= now)
        // A törlés így a jövőbeli módosítás után is nyer
        val (m, _) = Sync.merge(listOf(w("a", edited = future)), emptyMap(), emptyList(), mapOf("a" to Store.stamp(future)))
        assertTrue(m.isEmpty())
    }
}
