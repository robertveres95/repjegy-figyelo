package hu.repjegy.figyelo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Egy „tipp és trükk” buborék. [action]: egy gomb felirata és teendője (pl. a kulcs-varázsló). */
internal class Tip(
    val id: String,
    val title: String,
    val text: String,
    val action: String? = null,
    val provider: KeyProvider? = null,
)

internal object Tips {
    private const val HIDDEN = "tipsHidden"
    private const val COUNTER = "tipCounter"

    /** Az összes tipp; a kulcsos tippek csak akkor, ha az a forrás még nincs beállítva. */
    fun all(s: Settings): List<Tip> = buildList {
        if (!s.useSerpApi) add(Tip(
            "serpapi", tr("Megbízhatóbb Google-árak, ingyen", "More reliable Google prices, for free"),
            tr(
                "Egy ingyenes SerpApi-kulccsal a REFI akkor is lát Google-árat, amikor a sima keresés akadozik. " +
                    "Havonta 250 keresés ingyenes, és Google-fiókkal 2 perc alatt megvan. Végigvezetünk rajta.",
                "With a free SerpApi key, REFI sees Google prices even when the plain search stalls. " +
                    "250 searches a month are free, and with a Google account it takes 2 minutes. We’ll guide you through it.",
            ),
            tr("Megmutatom, hogyan", "Show me how"), KeyProvider.SERPAPI,
        ))
        if (!s.useIgnav) add(Tip(
            "ignav", tr("Még egy kereső = több esély olcsó jegyre", "One more search source = more chances of a cheap ticket"),
            tr(
                "Az Ignav saját adatforrásból keres, így olyan ajánlatot is találhat, amit a többi nem. Az első " +
                    "1000 keresés ingyenes, bankkártya nélkül. Lépésről lépésre segítünk.",
                "Ignav searches its own data source, so it can find deals the others miss. The first " +
                    "1000 searches are free, no bank card needed. We’ll help you step by step.",
            ),
            tr("Megmutatom, hogyan", "Show me how"), KeyProvider.IGNAV,
        ))
        add(Tip(
            "flex", tr("Egy-két nap rugalmasság sokat érhet", "A day or two of flexibility can be worth a lot"),
            tr(
                "Ha nem ragaszkodsz a pontos naphoz, állítsd a „Rugalmasság” mezőt ±1–3 napra: a REFI a környező " +
                    "napokat is végignézi, és a legolcsóbbat mutatja.",
                "If you’re not tied to an exact day, set “Flexibility” to ±1–3 days: REFI also checks the " +
                    "surrounding days and shows you the cheapest.",
            ),
        ))
        add(Tip(
            "city", tr("Egész várost is figyelhetsz", "You can watch a whole city"),
            tr(
                "Ha a „Hova” mezőbe városnevet írsz (pl. London, Milánó, Párizs), és a várost választod a listából, " +
                    "a REFI az összes ottani reptérre keres – a fapadosok gyakran a kisebb reptereket használják.",
                "If you type a city name in “To” (e.g. London, Milan, Paris) and pick the city from the list, " +
                    "REFI searches all its airports – low-cost airlines often use the smaller ones.",
            ),
        ))
        add(Tip(
            "bags", tr("Add meg a poggyászt is", "Add your baggage too"),
            tr(
                "A fapadosoknál a csomag külön kerül pénzbe. Ha megadod, mennyi poggyásszal utazol, a REFI " +
                    "beleszámolja a becsült díjat, így reális árakat hasonlítasz össze.",
                "Low-cost airlines charge extra for bags. If you enter how much baggage you’re taking, REFI " +
                    "adds the estimated fee, so you compare realistic prices.",
            ),
        ))
        add(Tip(
            "target", tr("Milyen célárat érdemes megadni?", "What target price should you set?"),
            tr(
                "Nézd meg a kártyán a „szokásos ár” sort (a Google adata): a sáv alsó része reális, de jó cél. " +
                    "Ha túl alacsony a célár, lehet, hogy sosem szól a REFI.",
                "Look at the “usual price” line on the card (Google’s data): the lower end of the range is a realistic but good target. " +
                    "If the target price is too low, REFI may never alert you.",
            ),
        ))
        add(Tip(
            "weekday", tr("Nem mindegy, melyik nap indulsz", "The day you fly matters"),
            tr(
                "Kedden, szerdán vagy szombaton indulni sokszor olcsóbb, mint pénteken vagy vasárnap, amikor a " +
                    "legtöbben utaznak. Ha teheted, próbálj ki több napot (vagy használd a rugalmasságot).",
                "Flying on Tuesday, Wednesday or Saturday is often cheaper than on Friday or Sunday, when " +
                    "most people travel. If you can, try a few days (or use flexibility).",
            ),
        ))
        add(Tip(
            "early", tr("Mikor érdemes figyelni kezdeni?", "When should you start watching?"),
            tr(
                "Európai utaknál általában 1–3 hónappal az indulás előtt a legjobb figyelni. Az utolsó két hétben " +
                    "az árak inkább emelkednek.",
                "For European trips it’s usually best to watch 1–3 months before departure. In the last two weeks " +
                    "prices tend to go up.",
            ),
        ))
    }

    private fun hidden(): Set<String> =
        Store.prefs.getString(HIDDEN, "")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    private const val ALL_OFF = "tipsAllOff"

    /** Az összes tipp kikapcsolása (a beállítás-képernyőről vagy a buborékból). */
    var allOff: Boolean
        get() = Store.prefs.getBoolean(ALL_OFF, false)
        set(v) { Store.prefs.edit { putBoolean(ALL_OFF, v) } }

    /** A korábban elrejtett tippek újra jöhetnek. */
    fun showAgain() {
        Store.prefs.edit { putString(HIDDEN, "") }
    }

    fun hide(id: String) {
        Store.prefs.edit { putString(HIDDEN, (hidden() + id).joinToString(",")) }
    }

    /**
     * A következő mutatandó tipp (minden megnyitáskor másik). Ha a kulcsok nincsenek beállítva,
     * minden második alkalommal a kulcsos tipp jön, mert az segít a legtöbbet.
     */
    fun next(s: Settings = Store.settings.value): Tip? {
        if (allOff) return null
        val list = all(s).filter { it.id !in hidden() }
        if (list.isEmpty()) return null
        val n = Store.prefs.getInt(COUNTER, 0)
        Store.prefs.edit { putInt(COUNTER, n + 1) }
        val keyTips = list.filter { it.provider != null }
        val others = list.filter { it.provider == null }
        return when {
            keyTips.isNotEmpty() && (n % 2 == 0 || others.isEmpty()) -> keyTips[(n / 2) % keyTips.size]
            others.isNotEmpty() -> others[(n / 2) % others.size]
            else -> keyTips[n % keyTips.size]
        }
    }
}

/** A tipp-buborék a figyelés szerkesztőjében: bezárható, lapozható, „ne mutasd többé” is választható. */
@Composable
internal fun TipBubble(onOpenGuide: (KeyProvider) -> Unit) {
    var tip by remember { mutableStateOf(runCatching { Tips.next() }.getOrNull()) }
    var visible by remember { mutableStateOf(tip != null) }
    AnimatedVisibility(visible = visible && tip != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val cur = tip ?: return@AnimatedVisibility
        // Nyelvváltáskor a tipp szövege is az új nyelven jelenjen meg
        val t = runCatching { Tips.all(Store.settings.value).firstOrNull { it.id == cur.id } }.getOrNull() ?: cur
        Column(
            Modifier
                .fillMaxWidth()
                .background(Neon.SurfaceHigh, RoundedCornerShape(16.dp))
                .border(1.dp, Neon.Green.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                .padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💡", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Neon.Green,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { visible = false }) {
                    Icon(Icons.Filled.Close, contentDescription = tr("Tipp bezárása", "Close tip"), tint = Neon.TextDim)
                }
            }
            Text(t.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
            if (t.action != null && t.provider != null) {
                androidx.compose.material3.Button(
                    onClick = { onOpenGuide(t.provider) },
                    modifier = Modifier.padding(top = 8.dp, end = 10.dp).fillMaxWidth(),
                ) { Text(t.action, fontWeight = FontWeight.Bold) }
            }
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { tip = runCatching { Tips.next() }.getOrNull(); if (tip == null) visible = false }) {
                    Text(tr("Következő tipp", "Next tip"))
                }
                TextButton(onClick = { Tips.hide(t.id); visible = false }) {
                    Text(tr("Ezt a tippet ne mutasd", "Don’t show this tip"), color = Neon.TextDim)
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { Tips.allOff = true; visible = false }) {
                    Text(tr("Egyik tippet se mutasd", "Don’t show any tips"), color = Neon.TextDim)
                }
            }
        }
    }
}
