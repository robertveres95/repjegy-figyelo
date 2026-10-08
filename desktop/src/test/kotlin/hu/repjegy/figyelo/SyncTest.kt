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
        val (m, t) = Sync.merge(listOf(w("a", edited = 10), w("b", edited = 100)), emptyMap(), emptyList(), mapOf("a" to 50L, "b" to 50L))
        assertEquals(listOf("b"), m.map { it.id }, "„a” törölve (régebbi módosítás), „b” megmarad (újabb)")
        assertEquals(setOf("a", "b"), t.keys)
    }

    @Test fun newRemoteWatchArrives() {
        val (m, _) = Sync.merge(listOf(w("a")), emptyMap(), listOf(w("a"), w("b")), emptyMap())
        assertEquals(listOf("a", "b"), m.map { it.id })
    }

    @Test fun notificationNotRepeatedAcrossDevices() {
        val local = w("a", edited = 10).copy(lastNotifiedPrice = null)
        val remote = w("a", edited = 10).copy(lastNotifiedPrice = 25000)
        val merged = Sync.merge(listOf(local), emptyMap(), listOf(remote), emptyMap()).first.single()
        assertEquals(25000, merged.lastNotifiedPrice)
    }

    @Test fun fileRoundTrip() {
        val ws = listOf(w("a", edited = 5, checked = 7, price = 9), w("b"))
        val text = Sync.serialize(ws, mapOf("x" to 3L), "EUR")
        val back = assertNotNull(Sync.parse(text))
        assertEquals(ws, back.watches)
        assertEquals(mapOf("x" to 3L), back.tombstones)
        assertEquals("EUR", back.currency)
        assertNull(Sync.parse("{\"format\":\"más\"}"))
        assertTrue(Sync.parse("szemét") == null)
    }
}
