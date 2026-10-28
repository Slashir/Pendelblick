package app.pendelwecker.domain

import app.pendelwecker.data.TripSegment
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class TrainPosition(
    val tripId: String,
    val label: String,
    val position: LatLon,
    val delayMin: Int,
    val realtime: Boolean,
    val standing: Boolean,
    /** Verkehrsmittel laut MOTIS, z. B. "SUBURBAN", "BUS". */
    val mode: String = "",
)

/** Google-Polyline-Dekodierung (Präzision 5 bei map/trips). */
object Polyline {
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        var factor = 1.0
        repeat(precision) { factor *= 10.0 }
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (which in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    if (index >= encoded.length) return out // abgeschnitten: bis hierher verwenden
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
                if (which == 0) lat += delta else lon += delta
            }
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }
}

/**
 * Berechnet, wo sich die Züge gerade befinden – nach dem TRAVIC-Prinzip:
 * Fahrplan plus gemeldete Verspätung, zeitlich und räumlich interpoliert.
 * Das ist keine GPS-Position.
 */
object TrainPositions {

    /** Vorbereitete Segmente, damit Polylines nur einmal pro Abruf dekodiert werden. */
    class Prepared(val segment: TripSegment, val points: List<LatLon>, val dep: Instant, val arr: Instant) {
        val tripId: String = segment.trips.firstOrNull()?.tripId ?: ""
        val label: String = segment.trips.firstOrNull()?.let { it.displayName ?: it.routeShortName } ?: ""
        private val cumulative: DoubleArray = DoubleArray(points.size).also { acc ->
            for (i in 1 until points.size) acc[i] = acc[i - 1] + distance(points[i - 1], points[i])
        }

        fun pointAt(fraction: Double): LatLon {
            if (points.isEmpty()) return LatLon(segment.from.lat, segment.from.lon)
            val total = cumulative.last()
            if (total <= 0.0) return points.first()
            val target = fraction.coerceIn(0.0, 1.0) * total
            var i = 1
            while (i < points.size && cumulative[i] < target) i++
            if (i >= points.size) return points.last()
            val segLen = cumulative[i] - cumulative[i - 1]
            val t = if (segLen > 0) (target - cumulative[i - 1]) / segLen else 0.0
            val a = points[i - 1]
            val b = points[i]
            return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }

        val delayMin: Int
            get() {
                val planned = CommuteChecker.parse(segment.scheduledDeparture) ?: return 0
                return kotlin.math.round((dep.epochSecond - planned.epochSecond) / 60.0).toInt()
            }
    }

    fun prepare(segments: List<TripSegment>): List<Prepared> = segments.mapNotNull { s ->
        val dep = CommuteChecker.parse(s.departure) ?: return@mapNotNull null
        val arr = CommuteChecker.parse(s.arrival) ?: return@mapNotNull null
        runCatching { Prepared(s, Polyline.decode(s.polyline, 5), dep, arr) }.getOrNull()
    }

    fun at(now: Instant, prepared: List<Prepared>): List<TrainPosition> =
        prepared.groupBy { it.tripId }.mapNotNull { (tripId, segs) ->
            val sorted = segs.sortedBy { it.dep }
            // 1) fährt gerade auf einem Abschnitt
            sorted.firstOrNull { !now.isBefore(it.dep) && now.isBefore(it.arr) }?.let { s ->
                val span = (s.arr.toEpochMilli() - s.dep.toEpochMilli()).coerceAtLeast(1)
                val f = (now.toEpochMilli() - s.dep.toEpochMilli()).toDouble() / span
                return@mapNotNull TrainPosition(tripId, s.label, s.pointAt(f), s.delayMin, s.segment.realTime, false, s.segment.mode)
            }
            // 2) steht an einem Halt zwischen zwei Abschnitten
            for (i in 0 until sorted.size - 1) {
                val cur = sorted[i]
                val next = sorted[i + 1]
                if (!now.isBefore(cur.arr) && now.isBefore(next.dep)) {
                    val p = LatLon(cur.segment.to.lat, cur.segment.to.lon)
                    return@mapNotNull TrainPosition(tripId, cur.label, p, next.delayMin, cur.segment.realTime, true, cur.segment.mode)
                }
            }
            null
        }

    /** Näherungsweise Distanz in Metern (reicht für die Interpolation entlang der Strecke). */
    private fun distance(a: LatLon, b: LatLon): Double {
        val kLat = 111_320.0
        val kLon = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dy = (b.lat - a.lat) * kLat
        val dx = (b.lon - a.lon) * kLon
        return sqrt(dx * dx + dy * dy)
    }
}
