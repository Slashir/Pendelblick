# Pendelblick

**Dein Zug im Blick.** Pendelblick ist eine kostenlose Android-App für Pendler. Sie prüft vor jeder
Fahrt, ob dein Zug oder Bus pünktlich fährt und ob dein Umstieg klappt. Wenn nicht, meldet sie sich
rechtzeitig und schlägt dir gleich eine andere Verbindung vor.

Dir hilft die App? Über Ko-fi kannst du mir einen Kaffee ausgeben:

[![Unterstütze Slashir auf Ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/slashir)

Kein Konto, keine Werbung, kein Tracking. Deine Fahrten bleiben auf deinem Handy.

## Installation

1. Unter [Releases](https://github.com/Slashir/Pendelblick/releases) die neueste
   `pendelblick-v….apk` auf dem Handy herunterladen.
2. Datei öffnen. Android fragt einmal, ob der Browser Apps installieren darf: erlauben.
3. App starten und deine Fahrt anlegen (siehe unten).

Neue Versionen installieren sich genauso über die alte, deine Fahrten bleiben erhalten.

> **Noch im Test:** Die Versionen 0.x sind Vorabversionen, die gerade im Pendelalltag erprobt
> werden. Wenn dir etwas auffällt, schreib gern ein [Issue](https://github.com/Slashir/Pendelblick/issues).

## Was die App kann

- **Antwort auf einen Blick:** Oben auf der Startseite steht, wie es um deine nächste Fahrt steht:
  pünktlich, verspätet, fällt aus oder Anschluss in Gefahr.
- **Automatische Prüfung:** Vor der Abfahrt (standardmäßig 45, 20 und 8 Minuten vorher) und kurz vor
  jedem Umstieg schaut die App nach, ob alles klappt. Die Zeiten kannst du selbst wählen.
- **Nur melden, wenn es wichtig ist:** Eine Benachrichtigung kommt nur, wenn sich etwas ändert, also
  bei Ausfall, Verspätung ab der von dir gewählten Minutenzahl oder einem wackelnden Anschluss. Wer
  möchte, lässt sich auch „alles pünktlich“ melden.
- **Ersatzverbindung:** Fällt etwas aus oder platzt ein Umstieg, zeigt die App die nächste
  Verbindung, mit der du ans Ziel kommst, samt Ankunftszeit.
- **Auch mit Umstiegen:** Du wählst deine Verbindung einfach aus dem Fahrplan, direkt oder mit
  Umstieg. Jede Teilstrecke und jeder Umstieg wird geprüft.
- **Hin- und Rückfahrt:** Zwei Fahrten mit eigenen Wochentagen, die Rückfahrt lässt sich mit einem
  Tipp aus der Hinfahrt übernehmen.
- **Live-Karte:** Zeigt Züge, S-Bahnen, Busse und Bahnen in der Nähe deiner Strecke, deine eigenen
  Linien hervorgehoben. Tippe ein Fahrzeug an, um Ziel, Verspätung und Laufweg zu sehen. Die
  Positionen werden aus Fahrplan und Verspätung berechnet, es ist kein GPS-Tracking.
- **Widget** für den Startbildschirm und **dunkles Design**.

## So richtest du sie ein

1. Fahrt anlegen: Start- und Zielhaltestelle suchen.
2. Deine Verbindung aus dem Fahrplan wählen.
3. Wochentage festlegen, an denen du fährst.
4. Benachrichtigungen erlauben und, wenn die App fragt, auch „Wecker und Erinnerungen“.

Fertig. Ab jetzt prüft Pendelblick im Hintergrund, du musst die App nicht öffnen.

## Gut zu wissen

- **Voraussetzung:** Android 8.0 oder neuer und eine Internetverbindung.
- **„Wecker und Erinnerungen“** sorgt dafür, dass die Prüfung genau zur gewählten Zeit läuft. Ohne
  diese Erlaubnis prüft die App bis zu 10 Minuten früher.
- **Energiesparen:** Manche Hersteller bremsen Apps im Hintergrund stark aus. Kommen Meldungen zu
  spät, nimm Pendelblick in den Akku-Einstellungen von der Optimierung aus.
- **Echtzeitdaten** gibt es nicht für jede Linie. Fehlen sie, zeigt die App „Fährt laut Plan“.
- **Ohne Gewähr:** Die Fahrplandaten kommen von Transitous, einem ehrenamtlich betriebenen Dienst.
  Bei wichtigen Terminen lohnt sich zusätzlich ein Blick in die App deines Verkehrsverbunds.

## Datenschutz

- Kein Konto, keine Werbung, keine Analyse- oder Tracking-Dienste, kein eigener Server.
- Deine Fahrten werden nur auf deinem Handy gespeichert. Sie landen auch nicht im Google-Backup.
- Für die Prüfung fragt die App Haltestellen und Abfahrtszeiten direkt bei
  [Transitous](https://transitous.org) ab. Die Karte lädt ihren Hintergrund von
  [OpenFreeMap](https://openfreemap.org). Dabei sehen diese Dienste wie jede Website deine IP-Adresse.

## Fehler gefunden, Idee?

Schreib gern ein [Issue](https://github.com/Slashir/Pendelblick/issues). Hilfreich sind die
Haltestellen, die Uhrzeit und was die App angezeigt hat.

## Danke

- [Transitous](https://transitous.org): freie Fahrplan- und Echtzeitdaten aus vielen Ländern.
  [Alle Datenquellen](https://transitous.org/sources/)
- [OpenStreetMap](https://www.openstreetmap.org/copyright) und [OpenFreeMap](https://openfreemap.org)
  für die Karte, [MapLibre](https://maplibre.org) für die Darstellung
- Schrift: IBM Plex Sans (SIL Open Font License)

## Lizenz

Copyright © 2026 Slashir. Pendelblick ist freie Software unter der
[GNU General Public License, Version 3](LICENSE). Du darfst die App nutzen, verändern und
weitergeben. Veränderte Fassungen müssen ebenfalls unter der GPL-3.0 und mit Quellcode erscheinen.

Technische Hinweise zum Bauen und zum Aufbau der App stehen in [ENTWICKLUNG.md](ENTWICKLUNG.md).
