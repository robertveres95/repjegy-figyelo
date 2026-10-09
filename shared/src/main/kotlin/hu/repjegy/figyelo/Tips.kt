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
            "serpapi", "Megbízhatóbb Google-árak, ingyen",
            "Egy ingyenes SerpApi-kulccsal a REFI akkor is lát Google-árat, amikor a sima keresés akadozik. " +
                "Havonta 250 keresés ingyenes, és Google-fiókkal 2 perc alatt megvan. Végigvezetünk rajta.",
            "Megmutatom, hogyan", KeyProvider.SERPAPI,
        ))
        if (!s.useIgnav) add(Tip(
            "ignav", "Még egy kereső = több esély olcsó jegyre",
            "Az Ignav saját adatforrásból keres, így olyan ajánlatot is találhat, amit a többi nem. Az első " +
                "1000 keresés ingyenes, bankkártya nélkül. Lépésről lépésre segítünk.",
            "Megmutatom, hogyan", KeyProvider.IGNAV,
        ))
        add(Tip(
            "flex", "Egy-két nap rugalmasság sokat érhet",
            "Ha nem ragaszkodsz a pontos naphoz, állítsd a „Rugalmasság” mezőt ±1–3 napra: a REFI a környező " +
                "napokat is végignézi, és a legolcsóbbat mutatja.",
        ))
        add(Tip(
            "city", "Egész várost is figyelhetsz",
            "Ha a „Hova” mezőbe városnevet írsz (pl. London, Milánó, Párizs), és a várost választod a listából, " +
                "a REFI az összes ottani reptérre keres – a fapadosok gyakran a kisebb reptereket használják.",
        ))
        add(Tip(
            "bags", "Add meg a poggyászt is",
            "A fapadosoknál a csomag külön kerül pénzbe. Ha megadod, mennyi poggyásszal utazol, a REFI " +
                "beleszámolja a becsült díjat, így reális árakat hasonlítasz össze.",
        ))
        add(Tip(
            "target", "Milyen célárat érdemes megadni?",
            "Nézd meg a kártyán a „szokásos ár” sort (a Google adata): a sáv alsó része reális, de jó cél. " +
                "Ha túl alacsony a célár, lehet, hogy sosem szól a REFI.",
        ))
        add(Tip(
            "weekday", "Hétköznap gyakran olcsóbb",
            "Kedden, szerdán vagy szombaton indulni sokszor olcsóbb, mint pénteken vagy vasárnap, amikor a " +
                "legtöbben utaznak. Ha teheted, próbálj ki több napot (vagy használd a rugalmasságot).",
        ))
        add(Tip(
            "early", "Mikor érdemes figyelni kezdeni?",
            "Európai utaknál általában 1–3 hónappal az indulás előtt a legjobb figyelni. Az utolsó két hétben " +
                "az árak inkább emelkednek.",
        ))
    }

    private fun hidden(): Set<String> =
        Store.prefs.getString(HIDDEN, "")?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()

    fun hide(id: String) {
        Store.prefs.edit { putString(HIDDEN, (hidden() + id).joinToString(",")) }
    }

    /**
     * A következő mutatandó tipp (minden megnyitáskor másik). Ha a kulcsok nincsenek beállítva,
     * minden második alkalommal a kulcsos tipp jön, mert az segít a legtöbbet.
     */
    fun next(s: Settings = Store.settings.value): Tip? {
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
        val t = tip ?: return@AnimatedVisibility
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
                    Icon(Icons.Filled.Close, contentDescription = "Tipp bezárása", tint = Neon.TextDim)
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
                    Text("Következő tipp")
                }
                TextButton(onClick = { Tips.hide(t.id); visible = false }) {
                    Text("Ne mutasd többé", color = Neon.TextDim)
                }
            }
        }
    }
}
