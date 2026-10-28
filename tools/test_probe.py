"""Tests für die Logik im Testskript. Gleiche Fälle wie die Kotlin-Unit-Tests der App.
Aufruf: python3 -m unittest test_probe.py   (im Ordner tools)"""
import unittest
from datetime import date, datetime, time, timedelta, timezone

from transitous_probe import TZ, build_connections, decode_polyline, evaluate

DAY = date(2026, 10, 27)


def ts(hhmm: str) -> str:
    h, m = map(int, hhmm.split(":"))
    return datetime(DAY.year, DAY.month, DAY.day, h, m, tzinfo=TZ).astimezone(timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%SZ")


def dep(trip, plan, real=None, line="S8", cancelled=False, rt=True):
    return {"tripId": trip, "displayName": line, "headsign": "Dresden Hbf", "realTime": rt,
            "cancelled": cancelled, "tripCancelled": cancelled,
            "place": {"name": "Kamenz", "lat": 0, "lon": 0, "scheduledDeparture": ts(plan),
                      "departure": ts(real or plan), "track": "1"}}


def arr(trip, plan, real=None, line="S8", cancelled=False, rt=True):
    return {"tripId": trip, "displayName": line, "realTime": rt, "cancelled": cancelled,
            "tripCancelled": cancelled,
            "place": {"name": "Dresden Hbf", "lat": 0, "lon": 0, "scheduledArrival": ts(plan),
                      "arrival": ts(real or plan)}}


class PolylineTest(unittest.TestCase):
    def test_google_reference(self):
        # Referenzbeispiel aus der Google-Dokumentation
        pts = decode_polyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        self.assertEqual(pts, [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)])


class ConnectionTest(unittest.TestCase):
    def test_only_trips_serving_both_in_order(self):
        deps = [dep("a", "06:03"), dep("b", "06:10"), dep("c", "06:20")]
        arrs = [arr("a", "06:42"), arr("c", "06:00")]  # c kommt vorher an -> Gegenrichtung
        conns = build_connections(deps, arrs)
        self.assertEqual([c.trip_id for c in conns], ["a"])


class EvaluateTest(unittest.TestCase):
    def run_eval(self, deps, arrs, line="S8", at="06:03"):
        return evaluate(build_connections(deps, arrs), line, time.fromisoformat(at), DAY, 3)

    def test_on_time(self):
        r = self.run_eval([dep("a", "06:03")], [arr("a", "06:42")])
        self.assertEqual(r.status, "PUENKTLICH")

    def test_cancelled_with_next_train(self):
        deps = [dep("a", "06:03", cancelled=True), dep("b", "06:33"), dep("c", "07:03", cancelled=True)]
        arrs = [arr("a", "06:42", cancelled=True), arr("b", "07:12"), arr("c", "07:42", cancelled=True)]
        r = self.run_eval(deps, arrs)
        self.assertEqual(r.status, "AUSFALL")
        self.assertEqual(r.alternative.trip_id, "b")
        self.assertEqual(r.further_cancellations, 1)

    def test_cancelled_other_line_counts_as_alternative(self):
        deps = [dep("a", "06:03", cancelled=True), dep("re", "06:15", line="RB33")]
        arrs = [arr("a", "06:42", cancelled=True), arr("re", "06:50", line="RB33")]
        r = self.run_eval(deps, arrs)
        self.assertEqual(r.alternative.trip_id, "re")

    def test_delayed_earlier_train_is_alternative(self):
        # Zug von 05:33 hat +35 und fährt erst 06:08 -> noch erreichbar
        deps = [dep("x", "05:33", "06:08"), dep("a", "06:03", cancelled=True)]
        arrs = [arr("x", "06:12", "06:47"), arr("a", "06:42", cancelled=True)]
        r = self.run_eval(deps, arrs)
        self.assertEqual(r.alternative.trip_id, "x")

    def test_delayed(self):
        r = self.run_eval([dep("a", "06:03", "06:10")], [arr("a", "06:42", "06:49")])
        self.assertEqual(r.status, "VERSPAETET")

    def test_small_delay_below_threshold(self):
        r = self.run_eval([dep("a", "06:03", "06:05")], [arr("a", "06:42", "06:44")])
        self.assertEqual(r.status, "PUENKTLICH")

    def test_line_name_with_space(self):
        r = self.run_eval([dep("a", "06:03", line="S 8")], [arr("a", "06:42", line="S 8")])
        self.assertEqual(r.status, "PUENKTLICH")

    def test_unknown(self):
        r = self.run_eval([dep("a", "06:10")], [arr("a", "06:50")])
        self.assertEqual(r.status, "UNBEKANNT")


if __name__ == "__main__":
    unittest.main()
