# REFI – repjegy figyelő

Android- és Windows-app, ami figyeli a repülőjegyárakat, és értesítést küld, ha egy jegy a megadott célár alá esik.

Árforrások, párhuzamosan versenyeztetve:
- **Kulcs nélkül:** Google Flights (légitársaságok és irodák), Ryanair és Wizz Air közvetlenül
- **Opcionális, kulccsal:** SerpApi, Ignav

## Mit tud

- Repülőtér-kereső gépelés közbeni javaslatokkal (név, város – magyarul is –, vagy kód), több repülőteres városoknál „minden repülőtér” opció
- Csak oda vagy oda-vissza; a dátum begépelhető, vagy a naptár ikonnal kiválasztható
- Osztály: turista, prémium turista, business, első
- Utasok: felnőtt, gyerek, csecsemő (saját ülésen vagy ölben)
- Kézipoggyász, feladott poggyász, átszállások száma
- Minden ajánlatnál pontos indulási és érkezési idő, légitársaság, forrás
- Célár; értesítés be/ki egy koppintással a kártyán (csengő gomb); az árriasztás két rövid rezgéssel jelez
- Automatikus ellenőrzés a háttérben (3, 6, 12, 24 óránként vagy kikapcsolva)
- Témák: automatikus (rendszer szerint), nappali, éjszakai (neon) és szemkímélő; animált felület
- Állítható betűméret (normál, nagy, extra nagy); nagy, jól olvasható indulási idők a találati listában
- 3D nyitóanimáció hidegindításkor (three.js, az appba csomagolva)
- Frissítésfigyelő: új kiadásnál az app kötelező frissítést kér, és naponta egyszer értesítést is küld
- Árgörbe a korábbi ellenőrzésekből, „Megnyitás” gomb a foglalási/kereső oldalhoz
- Forrásonkénti állapot minden figyelésnél (melyik forrás hány ajánlatot adott, vagy miért hibázott)

## Telepítés

1. A repó **Releases** részében nyisd meg a legfrissebb (nem „teszt”) kiadást, és töltsd le a `REFI.apk` fájlt.
2. Nyisd meg a telefonon. Ha kéri, engedélyezd a böngészőnek az „ismeretlen forrásból származó alkalmazások” telepítését.
3. **Windowson:** töltsd le a `REFI-Setup.msi` fájlt, és futtasd. Ha a Windows figyelmeztet („A Windows megvédte a számítógépet”), kattints a „További információ”, majd a „Futtatás mindenképp” gombra. Az app bezáráskor a tálcán fut tovább, és bejelentkezéskor magától elindul (ez a Feladatkezelő „Indítási alkalmazások” lapján kikapcsolható).
4. Kulcs nem kell: a Google Flights, Ryanair és Wizz Air alapból be van kapcsolva. A SerpApi és az Ignav a **Beállításokban** kapcsolható be, ha van kulcsod.

## Korlátok

- A kulcs nélküli források nem hivatalos felületek: bármikor megváltozhatnak, és az oldalak feltételei többnyire tiltják az automatizált lekérdezést. Túl gyakori ellenőrzésnél ideiglenesen letilthatnak.
- A Ryanair és a Wizz Air egy főre adja az alapárat poggyász nélkül; az összárat az utasszámmal becsüljük.
- Oda-vissza útnál a Google Flights csak az odaút időpontját adja meg; a visszaút a megnyitott oldalon választható.
- Az ingyenes SerpApi-keret havi 250 keresés.
- A nem forintos árakat az EKB napi árfolyamával (frankfurter.dev) váltjuk át.
- A Google Flights-lekérdezés a nyílt forrású fast-flights, a Wizz Air-felület leírása a flywizz könyvtár alapján készült.
- A repülőtér-lista az OurAirports nyílt adatbázisából származik (menetrend szerinti járatú repterek).
- A nyitóanimáció a three.js (MIT licenc) könyvtárat használja, az appba csomagolva.
- Betűtípus: Plus Jakarta Sans (SIL Open Font License), az appba csomagolva.
- A Google Flights a legtöbb légitársaságot és irodát lefedi, de nem mindet.

## Fejlesztés

Kotlin + Jetpack Compose (Android), Compose Multiplatform Desktop (Windows), WorkManager.

- **Szerkezet:** `shared/` – közös kód (árforrások, adatkezelés, felület, témák); `app/` – Android; `desktop/` – Windows (külön Gradle-build: `gradle -p desktop packageMsi`). A platformfüggő részek a `PlatformApi` felület mögött vannak.

- **Verziószám:** a `version.properties` fájlban (`VERSION_NAME=1.1.0`). Új kiadás előtt ezt kell átírni.
- **Teszt-build:** minden `main`-re pusholt változásból „teszt” jelölésű (prerelease) kiadás készül. Letölthető kipróbálásra, de a telepített appok **nem** ajánlják fel frissítésként.
- **Éles kiadás:** csak külön kérésre, egy `v*` címke pusholásával (vagy a workflow kézi indításával, publish=true). Ekkor `v<verzió>-build-<N>` címkéjű kiadás készül, és a telepített appok kötelező frissítést kérnek.
- A frissítésfigyelő a GitHub „legfrissebb kiadását” nézi (a teszt-buildeket nem), ezért a repónak nyilvánosnak kell maradnia.
