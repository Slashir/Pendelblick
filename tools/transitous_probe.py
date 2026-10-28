#!/usr/bin/env python3
"""
Testskript: Liefert Transitous für deine Pendelstrecke brauchbare Echtzeitdaten?

Prüft, welche API-Version Transitous gerade anbietet, sucht die Haltestellen,
holt Abfahrten am Start und Ankünfte am Ziel, verknüpft sie zu Verbindungen
und zeigt Ausfälle und Verspätungen. Am Ende wird dieselbe Bewertung wie in
der App berechnet und der Kartenendpunkt getestet.

Nur Python-Standardbibliothek, keine Installation nötig.

Beispiele:
  python3 transitous_probe.py --von "Kamenz" --nach "Dresden Hbf" --linie S8 --zeit 06:03
  python3 transitous_probe.py --von "Dresden Hbf" --nach "Kamenz" --linie S8 --zeit 16:10 --datum 2026-10-28

Nutzungsrichtlinie von Transitous beachten: https://transitous.org/api/
Das Skript schickt einen User-Agent mit Kontaktangabe (bitte --kontakt setzen).
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

BASE = "https://api.transitous.org"
API_VERSIONS = ["v6", "v5", "v4"]  # neueste zuerst, die erste funktionierende wird genutzt
TZ = ZoneInfo("Europe/Berlin")


# ---------------------------------------------------------------- HTTP

class Api:
    def __init__(self, contact: str, version: str | None = None):
        self.ua = f"Pendelwecker-Probe/0.1 ({contact})"
        self.version = version

    def get(self, path: str, params: dict) -> object:
        query = urllib.parse.urlencode(
            {k: v for k, v in params.items() if v is not None}, doseq=True
        )
        url = f"{BASE}{path}?{query}"
        req = urllib.request.Request(url, headers={"User-Agent": self.ua, "Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode("utf-8"))

    def detect_version(self, stop_id: str) -> str:
        for v in API_VERSIONS:
            try:
                self.get(f"/api/{v}/stoptimes", {"stopId": stop_id, "n": 1})
                self.version = v
                return v
            except urllib.error.HTTPError as e:
                print(f"  /api/{v}/stoptimes -> HTTP {e.code}")
        raise SystemExit("Keine der API-Versionen funktioniert: " + ", ".join(API_VERSIONS))

    def geocode(self, text: str) -> list[dict]:
        return self.get("/api/v1/geocode", {"text": text, "type": "STOP"})

    def stoptimes(self, stop_id: str, t: datetime, arrive_by: bool, window_s: int, n: int,
                  realtime_mode: str | None = "REALTIME_ANNOTATION_ONLY") -> dict:
        return self.get(f"/api/{self.version}/stoptimes", {
            "stopId": stop_id,
            "time": iso(t),
            "arriveBy": str(arrive_by).lower(),
            "direction": "LATER",
            "window": window_s,
            "n": n,
            "realtimeMode": realtime_mode,
        })

    def map_trips(self, mn: tuple[float, float], mx: tuple[float, float], zoom: float,
                  start: datetime, end: datetime) -> list[dict]:
        return self.get(f"/api/{self.version}/map/trips", {
            "zoom": zoom,
            "min": f"{mn[0]},{mn[1]}",
            "max": f"{mx[0]},{mx[1]}",
            "startTime": iso(start),
            "endTime": iso(end),
            "precision": 5,
        })


def iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_ts(s: str | None) -> datetime | None:
    if not s:
        return None
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


# ---------------------------------------------------------------- Logik (identisch zur App)

@dataclass
class Connection:
    trip_id: str
    line: str
    headsign: str
    planned_dep: datetime
    real_dep: datetime
    planned_arr: datetime
    real_arr: datetime
    cancelled: bool
    realtime: bool
    track: str | None

    @property
    def delay_dep_min(self) -> int:
        return round((self.real_dep - self.planned_dep).total_seconds() / 60)

    @property
    def delay_arr_min(self) -> int:
        return round((self.real_arr - self.planned_arr).total_seconds() / 60)


def line_name(st: dict) -> str:
    return st.get("displayName") or st.get("routeShortName") or st.get("tripShortName") or "?"


def build_connections(departures: list[dict], arrivals: list[dict]) -> list[Connection]:
    """Verbindet Abfahrten am Start und Ankünfte am Ziel über die tripId.
    Nur Fahrten, die erst am Start und danach am Ziel halten, zählen."""
    arr_by_trip: dict[str, dict] = {}
    for a in arrivals:
        arr_by_trip.setdefault(a["tripId"], a)
    out: list[Connection] = []
    for d in departures:
        a = arr_by_trip.get(d["tripId"])
        if a is None:
            continue
        dp, ap = d["place"], a["place"]
        p_dep = parse_ts(dp.get("scheduledDeparture"))
        p_arr = parse_ts(ap.get("scheduledArrival"))
        if p_dep is None or p_arr is None or p_arr <= p_dep:
            continue
        r_dep = parse_ts(dp.get("departure")) or p_dep
        r_arr = parse_ts(ap.get("arrival")) or p_arr
        cancelled = bool(
            d.get("cancelled") or d.get("tripCancelled") or a.get("cancelled")
            or dp.get("cancelled") or ap.get("cancelled")
        )
        out.append(Connection(
            trip_id=d["tripId"], line=line_name(d), headsign=d.get("headsign", ""),
            planned_dep=p_dep, real_dep=r_dep, planned_arr=p_arr, real_arr=r_arr,
            cancelled=cancelled, realtime=bool(d.get("realTime") or a.get("realTime")),
            track=dp.get("track") or dp.get("scheduledTrack"),
        ))
    out.sort(key=lambda c: c.planned_dep)
    return out


def matches_line(c: Connection, line: str) -> bool:
    """'S8', 's 8' und 'S8 ' gelten als gleich; leere Vorgabe passt auf alles."""
    if not line.strip():
        return True
    norm = lambda s: "".join(s.split()).lower()
    return norm(c.line) == norm(line)


@dataclass
class CheckResult:
    status: str  # PUENKTLICH, VERSPAETET, AUSFALL, UNBEKANNT
    target: Connection | None
    alternative: Connection | None
    further_cancellations: int
    message: str


def evaluate(connections: list[Connection], line: str, dep_local: time, service_date: date,
             threshold_min: int = 3, tz=TZ) -> CheckResult:
    wanted = datetime.combine(service_date, dep_local, tzinfo=tz)
    target = next(
        (c for c in connections
         if matches_line(c, line) and abs((c.planned_dep - wanted).total_seconds()) <= 60),
        None,
    )
    if target is None:
        return CheckResult("UNBEKANNT", None, None, 0,
                           f"Keine {line or 'Fahrt'} um {dep_local:%H:%M} gefunden.")

    later = [c for c in connections if c.planned_dep > target.planned_dep]
    further = sum(1 for c in later if c.cancelled and matches_line(c, line))
    usable = [c for c in connections if c is not target and not c.cancelled
              and c.real_dep >= target.planned_dep]

    def hm(t: datetime) -> str:
        return t.astimezone(tz).strftime("%H:%M")

    if target.cancelled:
        alt = min(usable, key=lambda c: c.real_arr, default=None)
        msg = f"{target.line} {hm(target.planned_dep)} fällt aus."
        if alt:
            msg += (f" Nächste: {alt.line} {hm(alt.real_dep)}"
                    f"{f' (+{alt.delay_dep_min})' if alt.delay_dep_min > 0 else ''},"
                    f" an {hm(alt.real_arr)}.")
        if further:
            msg += f" Danach noch {further} weitere Ausfälle."
        return CheckResult("AUSFALL", target, alt, further, msg)

    if target.delay_dep_min >= threshold_min or target.delay_arr_min >= threshold_min:
        faster = [c for c in usable if c.real_arr < target.real_arr]
        alt = min(faster, key=lambda c: c.real_arr, default=None)
        msg = (f"{target.line} {hm(target.planned_dep)} +{target.delay_dep_min} min,"
               f" an {hm(target.real_arr)} statt {hm(target.planned_arr)}.")
        if alt:
            msg += f" Schneller: {alt.line} {hm(alt.real_dep)}, an {hm(alt.real_arr)}."
        return CheckResult("VERSPAETET", target, alt, further, msg)

    suffix = "" if target.realtime else " (keine Echtzeitdaten)"
    return CheckResult("PUENKTLICH", target, None, further,
                       f"{target.line} {hm(target.planned_dep)} pünktlich, an {hm(target.real_arr)}.{suffix}")


def decode_polyline(encoded: str, precision: int = 5) -> list[tuple[float, float]]:
    """Google-Polyline-Dekodierung (gleicher Algorithmus wie in der App)."""
    factor = 10 ** precision
    coords, idx, lat, lon = [], 0, 0, 0
    while idx < len(encoded):
        for which in (0, 1):
            shift = result = 0
            while True:
                b = ord(encoded[idx]) - 63
                idx += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += delta
            else:
                lon += delta
        coords.append((lat / factor, lon / factor))
    return coords


# ---------------------------------------------------------------- Ablauf

def pick_stop(api: Api, text: str) -> dict:
    matches = api.geocode(text)
    stops = [m for m in matches if m.get("type") == "STOP"]
    if not stops:
        raise SystemExit(f"Keine Haltestelle für '{text}' gefunden.")
    print(f"\nHaltestellen für '{text}':")
    for m in stops[:5]:
        print(f"  {m['name']:<40} id={m['id']}  ({m['lat']:.4f}, {m['lon']:.4f})")
    return stops[0]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--von", required=True)
    ap.add_argument("--nach", required=True)
    ap.add_argument("--linie", default="", help="z. B. S8, leer = alle Linien")
    ap.add_argument("--zeit", required=True, help="geplante Abfahrt HH:MM")
    ap.add_argument("--datum", help="YYYY-MM-DD, Standard: heute")
    ap.add_argument("--fenster", type=int, default=150, help="Minuten ab 15 min vor Abfahrt")
    ap.add_argument("--schwelle", type=int, default=3, help="ab wie vielen Minuten Verspätung melden")
    ap.add_argument("--kontakt", default="kontakt-bitte-setzen", help="E-Mail oder Website für den User-Agent")
    ap.add_argument("--json", action="store_true", help="Rohantworten zusätzlich speichern")
    args = ap.parse_args()

    api = Api(args.kontakt)
    service_date = date.fromisoformat(args.datum) if args.datum else datetime.now(TZ).date()
    dep_local = time.fromisoformat(args.zeit)

    von = pick_stop(api, args.von)
    nach = pick_stop(api, args.nach)

    print("\nAPI-Version:")
    print(f"  genutzt: {api.detect_version(von['id'])}")

    start = datetime.combine(service_date, dep_local, tzinfo=TZ) - timedelta(minutes=15)
    window_s = args.fenster * 60

    try:
        deps = api.stoptimes(von["id"], start, False, window_s, 40)
        arrs = api.stoptimes(nach["id"], start, True, window_s + 3600, 80)
        mode_note = "REALTIME_ANNOTATION_ONLY"
    except urllib.error.HTTPError as e:
        print(f"  realtimeMode nicht unterstützt (HTTP {e.code}), versuche ohne")
        deps = api.stoptimes(von["id"], start, False, window_s, 40, realtime_mode=None)
        arrs = api.stoptimes(nach["id"], start, True, window_s + 3600, 80, realtime_mode=None)
        mode_note = "Standard (REALTIME)"
    print(f"  realtimeMode: {mode_note}")

    if args.json:
        with open("probe_abfahrten.json", "w", encoding="utf-8") as f:
            json.dump(deps, f, ensure_ascii=False, indent=1)
        with open("probe_ankuenfte.json", "w", encoding="utf-8") as f:
            json.dump(arrs, f, ensure_ascii=False, indent=1)
        print("  Rohdaten: probe_abfahrten.json, probe_ankuenfte.json")

    dep_list = deps.get("stopTimes", [])
    arr_list = arrs.get("stopTimes", [])
    rt_count = sum(1 for s in dep_list if s.get("realTime"))
    cx_count = sum(1 for s in dep_list if s.get("cancelled") or s.get("tripCancelled"))
    print(f"\nAbfahrten {von['name']}: {len(dep_list)} (mit Echtzeit: {rt_count}, ausgefallen: {cx_count})")
    print(f"Ankünfte {nach['name']}: {len(arr_list)}")

    conns = build_connections(dep_list, arr_list)
    print(f"\nVerbindungen {von['name']} -> {nach['name']}: {len(conns)}")
    print(f"  {'Linie':<8}{'ab Plan':>8}{'ab Ist':>8}{'an Plan':>9}{'an Ist':>8}  Gleis  Status")
    for c in conns:
        status = "AUSFALL" if c.cancelled else (f"+{c.delay_dep_min}" if c.delay_dep_min > 0 else "ok")
        if not c.realtime and not c.cancelled:
            status += " (ohne Echtzeit)"
        print(f"  {c.line:<8}{c.planned_dep.astimezone(TZ):%H:%M}{'':>3}{c.real_dep.astimezone(TZ):%H:%M}"
              f"{'':>4}{c.planned_arr.astimezone(TZ):%H:%M}{'':>3}{c.real_arr.astimezone(TZ):%H:%M}"
              f"  {c.track or '-':<5}  {status}")

    res = evaluate(conns, args.linie, dep_local, service_date, args.schwelle)
    print(f"\nBewertung wie in der App: {res.status}\n  {res.message}")

    # Kartenendpunkt: beide Lesarten der Eckpunkte testen (Spezifikation ist widersprüchlich formuliert)
    lat0, lat1 = sorted([von["lat"], nach["lat"]])
    lon0, lon1 = sorted([von["lon"], nach["lon"]])
    pad = 0.03
    now = datetime.now(TZ)
    print("\nKartenendpunkt map/trips (jetzt bis +10 min):")
    variants = {
        "min=Süd-West, max=Nord-Ost": ((lat0 - pad, lon0 - pad), (lat1 + pad, lon1 + pad)),
        "min=Süd-Ost, max=Nord-West": ((lat0 - pad, lon1 + pad), (lat1 + pad, lon0 - pad)),
    }
    for label, (mn, mx) in variants.items():
        try:
            segs = api.map_trips(mn, mx, 11, now, now + timedelta(minutes=10))
            trips = {t["tripId"] for s in segs for t in s.get("trips", [])}
            with_rt = sum(1 for s in segs if s.get("realTime"))
            print(f"  {label}: {len(segs)} Abschnitte, {len(trips)} Fahrten, {with_rt} mit Echtzeit")
            if segs:
                pts = decode_polyline(segs[0]["polyline"], 5)
                print(f"    Beispiel-Polyline: {len(pts)} Punkte, Start {pts[0]}")
        except urllib.error.HTTPError as e:
            print(f"  {label}: HTTP {e.code}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.URLError as e:
        sys.exit(f"Netzwerkfehler: {e}")
