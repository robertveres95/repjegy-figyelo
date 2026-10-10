# REFI a Google Play-en – előkészítés és teendők

Ez a lista a Google Play-kiadáshoz kell. Amit a kód és a build már tud, az ki van pipálva; a többi
a fejlesztői fiókban (Play Console, Google Cloud) végzendő kézi lépés.

## Ami már kész a tárolóban

- [x] **Play-változat a frissítő nélkül.** A Play házirendje tiltja, hogy az app a Play-en kívülről
  frissítse magát. A `gradle -Prefi.play=true :app:bundleRelease` paranccsal készült változatban a
  GitHub-os frissítéskeresés ki van kapcsolva (a Beállításokban „A frissítéseket a Google Play
  telepíti.” szöveg látszik).
- [x] **App Bundle (.aab).** A „APK build” folyamat minden futása elkészíti a Play-be feltölthető,
  aláírt `REFI-play.aab` fájlt is (a futás „Artifacts” részében, nem kerül a nyilvános kiadásba).
  Aláírás: a meglévő REFI-kulccsal (ez lesz a Play „feltöltési kulcsa”).
- [x] **targetSdk 36** (Android 16) – a Play 2026-os követelménye.
- [x] **Adatkezelési tájékoztató** magyarul és angolul: `PRIVACY.md`, weboldalként `docs/privacy.html`.
- [x] Nincs hirdetés, analitika, követés; csak a szükséges engedélyek (internet, értesítés, rezgés).

## Kézi teendők

1. **Fejlesztői fiók:** play.google.com/console – egyszeri 25 USD. Magánszemélyként a Google
   2023 óta kötelező **zárt tesztet** kér: legalább 12 tesztelő, 14 napig, mielőtt éles lehet.
   (A család és ismerősök jók tesztelőnek.)
2. **Adatkezelési oldal címe:** a GitHub tárolóban Settings → Pages → Branch: `main`, mappa `/docs`
   → mentés. A tájékoztató ezután itt érhető el:
   `https://robertveres95.github.io/repjegy-figyelo/privacy.html` – ezt kell megadni a Play Console-ban.
3. **Google-bejelentkezés a Play-es változatban:** a Play a feltöltés után saját kulccsal írja alá az
   appot (Play App Signing). A Play Console → Teszt és kiadás → App integrity oldalon lévő
   **„App signing key” SHA-1** ujjlenyomatát fel kell venni a Google Cloud „REFI” projektben egy új
   Android OAuth-kliensként (csomagnév: `hu.repjegy.figyelo`). Enélkül a Play-ről telepített appban
   nem működik a szinkronizálás.
4. **OAuth hitelesítés:** a Google Cloud OAuth-hozzájárulási képernyőt „Production” állapotba kell
   tenni. A `drive.appdata` nem „érzékeny” hatókör, így elég az alap ellenőrzés (név, logó,
   adatkezelési link, domain).
5. **Áruházi adatlap:** lent a kész szövegek. Képernyőképek: a „Diagnosztika” folyamat készít
   telefonos képeket (01–10), ezek közül 4–6 elég.
6. **Adatbiztonsági űrlap (Data safety):** lent a válaszok.
7. **Tartalmi besorolás:** kérdőív – nincs erőszak, szerencsejáték stb. → „Mindenki” (PEGI 3).
8. **Jogi kockázat – fontos:** a REFI a Google Flights nyilvános oldalát és a Ryanair / Wizz Air
   weboldalak belső felületeit olvassa. Ezek felhasználási feltételei ezt tilthatják, és egy
   nyilvános, sok felhasználós appnál a Google vagy a légitársaságok kifogást emelhetnek (eltávolítás
   a Play-ből). Biztonságosabb nyilvános kiadáshoz: alapból csak a hivatalos, kulcsos források
   (SerpApi, Ignav – a felhasználó saját kulcsával), a többit „kísérleti” kapcsolóként kínálni.
   Kiadás előtt érdemes jogásszal átnézetni.
9. **Angol nyelv:** a Play-en világszerte csak akkor érdemes megjelenni, ha az app angolul is tud.
   Ez még nincs kész (az összes szöveg magyar) – nagyobb munka, külön verzióban.

## Áruházi adatlap

**Név:** REFI – repjegy-árfigyelő

**Rövid leírás (max. 80 karakter):**
Figyeli a repülőjegyárakat, és szól, ha a célárad alá csökken.

**Teljes leírás:**
A REFI figyeli a kiválasztott repülőjáratok árát, és értesít, amint az ár a célárad alá esik.

• Egyszerre több forrásból keres: Google Flights, Ryanair, Wizz Air – és ingyenes kulccsal SerpApi és Ignav.
• A fapadosok poggyászdíját is beszámolja, így reális árakat hasonlítasz össze.
• Rugalmas dátum: ±1–3 nap, vagy „minden héten” – például bármelyik hétvége a következő két hónapban.
• „Vegyem most vagy várjak?” – az árgörbe és a Google szokásos ársávja alapján megmondja, jó-e az ár.
• Felfedezés: hová lehet olcsón eljutni a következő hónapokban.
• Megosztás a családdal egyetlen üzenetben; a widget a kezdőképernyőn mutatja a legjobb árakat.
• Szinkronizálás a telefon, a számítógép (Windows) és a Chrome-bővítmény között a saját Google-fiókodon keresztül.
• Nincs hirdetés, nincs regisztráció, nincs adatgyűjtés.

## Adatbiztonsági űrlap – válaszok

- Gyűjt vagy oszt meg felhasználói adatot? **Nem.** (A szinkronizált adat a felhasználó saját
  Google Drive-jára kerül, a fejlesztő nem fér hozzá; a Google szerint ez nem „gyűjtés”.)
- Titkosítva továbbít? **Igen** (minden kapcsolat HTTPS).
- Kérhető az adatok törlése? **Igen** – az appban törölhetők a figyelések, a szinkronizálás
  kikapcsolható, a Drive-hozzáférés a Google-fiókban visszavonható.
