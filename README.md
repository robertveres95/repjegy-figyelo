# Repjegy figyelő

Android-app, ami figyeli a repülőjegyárakat (Google Flights-adat a SerpApi-n keresztül), és értesítést küld, ha egy jegy a megadott célár alá esik.

## Mit tud

- Repülőtér-kereső gépelés közbeni javaslatokkal (név, város – magyarul is –, vagy kód), több repülőteres városoknál „minden repülőtér” opció
- Csak oda vagy oda-vissza, dátumválasztóval
- Osztály: turista, prémium turista, business, első
- Utasok: felnőtt, gyerek, csecsemő (saját ülésen vagy ölben)
- Kézipoggyász száma, átszállások száma
- Célár és értesítés be/ki figyelésenként
- Automatikus ellenőrzés a háttérben (3, 6, 12 vagy 24 óránként)
- Árgörbe a korábbi ellenőrzésekből, „Megnyitás” gomb a Google Flights-találathoz

## Telepítés

1. A repó **Releases** részében nyisd meg a legfrissebb buildet, és töltsd le a `RepjegyFigyelo.apk` fájlt.
2. Nyisd meg a telefonon. Ha kéri, engedélyezd a böngészőnek az „ismeretlen forrásból származó alkalmazások” telepítését.
3. Az appban: **Beállítások** → másold be a SerpApi-kulcsot (ingyenes: https://serpapi.com/manage-api-key).

## Korlátok

- Az ingyenes SerpApi-keret havi 250 keresés. A Beállítások képernyő mutatja a becsült fogyasztást.
- A feladott poggyász díja nincs benne az árban, csak a kézipoggyász-szűrő.
- A repülőtér-lista az OurAirports nyílt adatbázisából származik (menetrend szerinti járatú repterek).
- A Google Flights a legtöbb légitársaságot és irodát lefedi, de nem mindet.

## Fejlesztés

Kotlin + Jetpack Compose, WorkManager. Minden `main`-re pusholt változás után a GitHub Actions automatikusan új APK-t készít.
