package app.pendelwecker.domain

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Eine Pendelfahrt, z. B. Hinfahrt S8 06:03 Kamenz -> Dresden Hbf. */
@Serializable
data class CommuteProfile(
    val id: Int,
    val label: String,
    val fromStopId: String = "",
    val fromName: String = "",
    val fromLat: Double = 0.0,
    val fromLon: Double = 0.0,
    val toStopId: String = "",
    val toName: String = "",
    val toLat: Double = 0.0,
    val toLon: Double = 0.0,
    /** Linie, z. B. "S8". Leer = jede Linie zwischen den beiden Halten. */
    val line: String = "",
    /** Geplante Abfahrt "HH:mm" am Start. */
    val departure: String = "06:00",
    /** Geplante Ankunft "HH:mm" am Ziel, nur zur Anzeige; leer, solange nicht aus dem Fahrplan gewählt. */
    val arrival: String = "",
    /** ISO-Wochentage, 1 = Montag … 7 = Sonntag. */
    val weekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Ab so vielen Minuten Verspätung wird gewarnt. */
    val delayThresholdMin: Int = 3,
    /** Minuten vor Abfahrt, zu denen geprüft wird. */
    val checkOffsetsMin: List<Int> = listOf(45, 20, 8),
    /** Auch melden, wenn alles pünktlich ist (beim letzten Prüfzeitpunkt). */
    val notifyWhenOnTime: Boolean = false,
    val active: Boolean = false,
    /**
     * Teilfahrten einer Verbindung mit Umstieg, aus dem Fahrplan gewählt. Leer bei einer direkten
     * Fahrt; dann gelten Von, Nach, Linie und Abfahrt oben.
     */
    val legs: List<PlannedLeg> = emptyList(),
) {
    val hasTransfers: Boolean get() = legs.size > 1

    /** Die zu prüfenden Teilfahrten; bei einer direkten Fahrt genau eine. */
    val trips: List<PlannedLeg>
        get() = legs.ifEmpty {
            listOf(PlannedLeg(fromStopId, fromName, fromLat, fromLon, toStopId, toName, toLat, toLon, line, "", departure, arrival))
        }

    /** Alle Linien der Fahrt, z. B. ["Bus 21", "S8"]. */
    val lines: List<String> get() = trips.map { it.line }.filter { it.isNotBlank() }

    /** Minuten von der ersten bis zur letzten Abfahrt (über Mitternacht hinweg). */
    val spanMinutes: Long
        get() {
            var total = 0L
            var prev = departureTime
            trips.drop(1).forEach { leg ->
                val t = runCatching { LocalTime.parse(leg.departure) }.getOrNull() ?: return@forEach
                var d = java.time.Duration.between(prev, t).toMinutes()
                if (d < 0) d += 24 * 60
                total += d
                prev = t
            }
            return total
        }

    /**
     * Abfahrt jeder Teilfahrt an einem Fahrttag als echte Uhrzeit. Geht die Uhrzeit zurück, liegt die
     * Teilfahrt nach Mitternacht; gerechnet wird je Datum, damit die Zeitumstellung richtig wirkt.
     */
    fun legStarts(day: LocalDate, zone: ZoneId): List<ZonedDateTime> {
        var date = day
        var prev: LocalTime? = null
        return trips.map { leg ->
            val t = LocalTime.parse(leg.departure)
            if (prev != null && t.isBefore(prev)) date = date.plusDays(1)
            prev = t
            date.atTime(t).atZone(zone)
        }
    }

    /** Alle Teilfahrten haben Halte und eine lesbare Abfahrtszeit. */
    val isComplete: Boolean
        get() = trips.all { leg ->
            leg.fromStopId.isNotBlank() && leg.toStopId.isNotBlank() && runCatching { LocalTime.parse(leg.departure) }.isSuccess
        }

    val departureTime: LocalTime
        get() = LocalTime.parse(departure)

    fun runsOn(day: DayOfWeek): Boolean = day.value in weekdays

    companion object {
        fun defaults() = listOf(
            CommuteProfile(id = 0, label = "Hinfahrt"),
            CommuteProfile(id = 1, label = "Rückfahrt", departure = "16:00"),
        )
    }
}

/** Eine Teilfahrt einer Verbindung mit Umstieg. */
@Serializable
data class PlannedLeg(
    val fromStopId: String,
    val fromName: String,
    val fromLat: Double,
    val fromLon: Double,
    val toStopId: String,
    val toName: String,
    val toLat: Double,
    val toLon: Double,
    val line: String,
    val mode: String = "",
    /** Geplante Abfahrt und Ankunft "HH:mm". */
    val departure: String,
    val arrival: String = "",
)
