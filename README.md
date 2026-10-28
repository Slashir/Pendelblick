# Pendelblick

Kostenlose Open-Source-App für Pendler: warnt bei Ausfall oder Verspätung der eigenen
Stammverbindung, zeigt die nächste Alternative mit Ankunftszeit und eine Live-Karte der Strecke.
Ohne eigenen Server, ohne Konto, ohne Werbung.

Name: **Pendelblick** (Arbeitstitel war Pendelwecker; Paketname und Ordner heißen weiter so).

**Verbindungen mit Umstieg:** Beim Einrichten wählt man eine Verbindung aus dem Routing (`/plan`), direkt
oder mit Umstiegen. Geprüft wird jede Teilfahrt wie eine direkte Fahrt (zwei Abfragen je Teilfahrt,
gleichzeitig), dazu jeder Umstieg (`CommuteChecker.combine`, mindestens 2 Minuten oder so viel wie im Plan).
Zusätzlich prüft die App 6 Minuten vor jeder weiteren Teilfahrt. Klappt ein Anschluss nicht, sucht sie die
nächste Gesamtverbindung ab dem nächsten noch nicht abgefahrenen Halt.

**Stand (28.10.2026):** baut lokal (beide Varianten, Unit-Tests grün) und läuft im Emulator
mit echten Transitous-Daten: Haltestellensuche, Zugauswahl aus dem Fahrplan, Prüfung, Live-Karte.
Noch nicht auf einem echten Gerät über mehrere Tage getestet.

## Oberfläche

Gestaltung wie die übrigen eigenen Oberflächen: Kopfband in Navy mit Goldlinie und Wortmarke
„Pendel**blick**“, Farben aus dem Design-System (Navy, Blau, Gold, Bordeaux), Schrift IBM Plex Sans
(OFL, `assets/licenses/IBMPlexSans-OFL.txt`). Wie bei Grenztanken gilt „Antwort zuerst“: oben eine
Statuskarte für die nächste Fahrt (pünktlich, später, fällt aus, mit Alternative), Gold nur für die
eine Hauptaktion. Grün/Gelb/Bordeaux stehen ausschließlich für den Zustand einer Fahrt; ohne
Echtzeitdaten zeigt die Karte neutral „Fährt laut Plan“.

- `ui/HomeScreen.kt`: Statuskarte, Hinweise zu Berechtigungen, Liste der Fahrten
- `ui/EditScreen.kt`: Haltestellensuche mit Vorschlägen, Zug aus dem Fahrplan wählen, Tage, Warnung
- `ui/Theme.kt`, `ui/Components.kt`: Farben, Schrift, Bausteine
- Live-Karte (`map/LiveMap.kt`): als laufende Vorschau gleich unter der Statuskarte und im Vollbild.
  Eigene Linien (aus den eingerichteten Fahrten) mit ganzem Laufweg und Halten, groß und immer
  beschriftet; andere Züge mittel, Bus und Tram klein. Antippen zeigt Ziel, Verspätung, nächsten
  Halt, Betreiber und den Laufweg des Fahrzeugs. Filter „Nur meine Linien“.

## Zwei Varianten

| Variante | Karte | Status |
|---|---|---|
| `travic` | TRAVIC (Uni Freiburg) per WebView | **erst nach schriftlicher Erlaubnis veröffentlichen** |
| `eigen` | eigene Karte: MapLibre + OpenFreeMap + Transitous `map/trips` | frei nutzbar im Rahmen der Transitous-Richtlinie |

Alles andere (Prüfung, Alarm, Widget, Benachrichtigung) ist in beiden Varianten gleich.

## So funktioniert es

```
Alarm (z. B. 45, 20 und 8 min vor Abfahrt)
  └─ 2 Abfragen bei Transitous:
       Abfahrten am Start   (stoptimes, arriveBy=false)
       Ankünfte am Ziel     (stoptimes, arriveBy=true)
  └─ über tripId zu Verbindungen verknüpfen (nur Fahrten, die Start vor Ziel bedienen)
  └─ Bewertung: PÜNKTLICH / VERSPÄTET / AUSFALL (+ nächste Alternative, weitere Ausfälle)
  └─ Benachrichtigung nur bei Änderung, Widget aktualisieren, nächsten Alarm planen
```

Live-Karte (Variante `eigen`): einmal pro Minute alle Fahrten im Ausschnitt um die
Pendelstrecke holen, jede Sekunde die Positionen aus Fahrplan + Verspätung auf dem Gerät
interpolieren (TRAVIC-Prinzip). **Keine GPS-Positionen.**

## Projektstruktur

```
app/src/main/java/app/pendelwecker/
  data/        TransitousApi (OkHttp), Models (Teilmenge der MOTIS-API), Store (lokal)
  domain/      CommuteProfile, CommuteChecker (Bewertung), TrainPositions (Polyline, Interpolation)
  alarm/       CheckScheduler (AlarmManager), Receiver, CheckWorker, Notifier, CommuteRunner
  widget/      CommuteWidget (Glance)
  MainActivity.kt   Profile einrichten (Hin- und Rückfahrt), Haltestellensuche
app/src/travic/…/map/MapActivity.kt   Variante A: WebView
app/src/eigen/…/map/MapActivity.kt    Variante B: MapLibre
app/src/test/…                        Unit-Tests (gleiche Fälle wie tools/test_probe.py)
tools/transitous_probe.py             Testskript für die Datenquelle
```

## Zuerst: Datenquelle testen

Bevor App-Code wichtig wird, mit dem Testskript prüfen, ob Transitous für die Strecke
Ausfälle und Verspätungen liefert (nur Python 3.9+, keine Pakete):

```bash
cd tools
python3 transitous_probe.py --von "Kamenz" --nach "Dresden Hbf" --linie S8 --zeit 06:03 --kontakt deine@mail.de
python3 transitous_probe.py --von "Dresden Hbf" --nach "Kamenz" --linie S8 --zeit 16:10 --kontakt deine@mail.de
```

Das Skript zeigt außerdem:
- welche API-Version (v6/v5/v4) Transitous gerade anbietet (die App wechselt bei HTTP 404 selbst
  auf eine andere Version und merkt sie sich; Startwert in `data/Store.kt`)
- ob der Parameter `realtimeMode` akzeptiert wird (die App fällt sonst automatisch zurück)
- welche Lesart der Kartenecken bei `map/trips` Ergebnisse liefert (die Spezifikation ist
  dort widersprüchlich formuliert)

Am besten an einem Tag mit Störung laufen lassen und mit der VVO-App vergleichen.

## Bauen

- **GitHub Actions:** Repo pushen, der Workflow baut beide Varianten und lädt die APKs als Artefakt hoch.
- **Android Studio:** Projekt öffnen, Variante unter *Build Variants* wählen (`eigenDebug` / `travicDebug`).
- **Kommandozeile:** `./gradlew assembleEigenDebug assembleTravicDebug`

Beim ersten Sync zu prüfen (nicht lokal getestet, weil Maven-Repositories in meiner
Umgebung gesperrt waren):
- AGP 9 nutzt *built-in Kotlin*. Die Kotlin-Version wird im Root-`build.gradle.kts` per
  Klassenpfad auf 2.4.21 gehoben, damit Compose- und Serialization-Plugin passen. Meckert
  Gradle hier, die Zeile `classpath(...)` entfernen und beide Plugin-Versionen auf die von
  AGP mitgebrachte Kotlin-Version setzen.
- `compileSdk`/`targetSdk` 37 (AGP 9.3 unterstützt bis API 37).

## Verwendete Versionen (geprüft am 27.10.2026)

| Komponente | Version | Quelle |
|---|---|---|
| Android Gradle Plugin | 9.3.3 | developer.android.com, AGP-9.3-Release-Notes |
| Gradle | 9.5.0 (Mindest-/Standardversion für AGP 9.3) | ebenda |
| Kotlin | 2.4.21 | GitHub-Tags JetBrains/kotlin |
| Compose BOM | 2026.08.00 | Android Developers Blog, Compose-Release August 2026 |
| activity-compose / core-ktx / lifecycle | 1.13.0 / 1.19.1 / 2.11.0 | AndroidX Stable Channel |
| WorkManager / Glance | 2.12.0 / 1.2.0 | AndroidX Stable Channel |
| OkHttp | 5.5.1 | GitHub-Tags square/okhttp |
| kotlinx-serialization / -coroutines | 1.12.0 / 1.11.0 | GitHub-Tags |
| MapLibre Android | 13.7.1 | GitHub-Tag android-v13.7.1 |
| GitHub Actions | checkout v7, setup-java v6, setup-gradle v6, upload-artifact v7 | GitHub-Tags |

## Regeln der Datenquellen

**Transitous** (https://transitous.org/api/):
- Code muss Open Source sein, nicht kommerziell
- User-Agent mit App-Name, Version, Kontakt → `CONTACT` in `app/build.gradle.kts` setzen
- sichtbarer Link auf https://transitous.org/sources/ (Fußzeile der Startseite) + OpenStreetMap-Attribution
- Routing (`/plan`) zählt als teure Anfrage: die App nutzt es nur beim Einrichten und, wenn eine
  Verbindung mit Umstieg nicht klappt, für die Ersatzverbindung
- Abstimmung im Matrix-Raum `#transitous:matrix.spline.de`
- ab mehr als 720 Routing-Anfragen/24 h vorher abstimmen; viele Nutzer mit offener Karte
  ebenfalls ansprechen
- Abgestimmt am 28.10.2026 in [transitous#2630](https://github.com/public-transport/transitous/issues/2630):
  Abfragemuster, Kartenabruf im Minutentakt und Ko-fi-Link sind in Ordnung. Die 720er-Schwelle gilt
  für die ganze App; langsames Wachstum ist okay, ein plötzlicher Ansturm nicht. Der Kontakt im
  User-Agent muss erreichbar sein.
- Dienst läuft ehrenamtlich ohne Garantie

**OpenFreeMap** (https://openfreemap.org): kostenlos, ohne API-Key, auch kommerziell und
in Apps erlaubt; Attribution setzt MapLibre automatisch.

**TRAVIC:** keine öffentlichen Nutzungsbedingungen für fremde Apps. Variante `travic` nur
mit schriftlicher Erlaubnis der Betreiber veröffentlichen.

## Android-Hintergrund (ohne Server)

Seit Android 14 ist `SCHEDULE_EXACT_ALARM` für neu installierte Apps standardmäßig
verweigert. Die App fragt den Nutzer einmal nach „Wecker und Erinnerungen“. Ohne
Erlaubnis plant sie mit `setWindow()` (10-Minuten-Fenster, endet am Prüfzeitpunkt).
`USE_EXACT_ALARM` ist Wecker-/Kalender-Apps vorbehalten und wird nicht genutzt.

## Offene Punkte

- [ ] Testskript an mehreren Tagen laufen lassen, API-Version festlegen
- [ ] Antwort von TRAVIC abwarten; ggf. Startausschnitt per URL in `startUrl()` einbauen
- [x] Ko-fi-Link in der App (`DONATE_URL`, Fußzeile der Startseite)
- [x] Nutzung und Ko-fi-Link mit Transitous abgestimmt (Issue #2630)
- [x] Lizenz: GPL-3.0 (`LICENSE`)
- [x] Repo `Slashir/Pendelblick` (steht als `CONTACT` im User-Agent)
- [x] App-Symbol (Zug mit goldener Uhr auf Navy, adaptiv)
- [ ] Auf einem echten Gerät über mehrere Pendeltage testen (Alarm, Benachrichtigung, Widget)

## Lizenz

Copyright © 2026 Slashir. Pendelblick ist freie Software unter der
[GNU General Public License, Version 3](LICENSE). Du darfst die App nutzen, verändern und
weitergeben. Veränderte Fassungen müssen ebenfalls unter der GPL-3.0 und mit Quellcode erscheinen.

Die Schrift IBM Plex Sans steht unter der SIL Open Font License (`app/src/main/assets/licenses/IBMPlexSans-OFL.txt`).
