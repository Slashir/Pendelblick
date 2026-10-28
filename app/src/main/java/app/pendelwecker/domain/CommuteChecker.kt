package app.pendelwecker.domain

import app.pendelwecker.data.StopTime
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/** Eine Fahrt, die erst am Start und danach am Ziel hält. */
data class Connection(
    val tripId: String,
    val line: String,
    val headsign: String,
    val plannedDep: Instant,
    val realDep: Instant,
    val plannedArr: Instant,
    val realArr: Instant,
    val cancelled: Boolean,
    val realtime: Boolean,
    val track: String?,
) {
    val delayDepMin: Int get() = minutesBetween(plannedDep, realDep)
    val delayArrMin: Int get() = minutesBetween(plannedArr, realArr)
}

/** ANSCHLUSS: bei Verbindungen mit Umstieg reicht die Zeit zum Umsteigen nicht mehr. */
enum class Status { PUENKTLICH, VERSPAETET, AUSFALL, ANSCHLUSS, UNBEKANNT, FEHLER }

data class CheckResult(
    val status: Status,
    val target: Connection?,
    val alternative: Connection?,
    val furtherCancellations: Int,
    val message: String,
    /** Teilfahrten bei Verbindungen mit Umstieg, sonst leer. */
    val legs: List<Connection> = emptyList(),
    /** Welcher Umstieg nicht mehr klappt, als kurzer Text. */
    val transferProblem: String? = null,
)

/** Kompakter Stand für Widget und Benachrichtigung (wird gespeichert). */
@Serializable
data class StatusSnapshot(
    val profileId: Int,
    val label: String,
    val status: Status,
    val message: String,
    val checkedAtEpochSec: Long,
    /** Die geprüfte Fahrt; fehlt, wenn keine gefunden wurde. */
    val target: TripTimes? = null,
    /** Nächste oder schnellere Fahrt bei Ausfall oder Verspätung. */
    val alternative: TripTimes? = null,
    val furtherCancellations: Int = 0,
    /** Teilfahrten bei Verbindungen mit Umstieg; [target] umfasst dann die ganze Verbindung. */
    val legs: List<TripTimes> = emptyList(),
    val transferProblem: String? = null,
)

/** Zeiten einer Fahrt in Epochensekunden, für die Darstellung in App und Widget. */
@Serializable
data class TripTimes(
    val line: String,
    val headsign: String = "",
    val plannedDep: Long,
    val realDep: Long,
    val plannedArr: Long,
    val realArr: Long,
    val track: String? = null,
    val realtime: Boolean = false,
    val cancelled: Boolean = false,
    val fromName: String = "",
    val toName: String = "",
) {
    val delayDepMin: Int get() = ((realDep - plannedDep) / 60.0).roundToInt()
    val delayArrMin: Int get() = ((realArr - plannedArr) / 60.0).roundToInt()

    fun toConnection() = Connection(
        tripId = "",
        line = line,
        headsign = headsign,
        plannedDep = Instant.ofEpochSecond(plannedDep),
        realDep = Instant.ofEpochSecond(realDep),
        plannedArr = Instant.ofEpochSecond(plannedArr),
        realArr = Instant.ofEpochSecond(realArr),
        cancelled = cancelled,
        realtime = realtime,
        track = track,
    )

    companion object {
        fun of(c: Connection, fromName: String = "", toName: String = "") = TripTimes(
            line = c.line,
            headsign = c.headsign,
            plannedDep = c.plannedDep.epochSecond,
            realDep = c.realDep.epochSecond,
            plannedArr = c.plannedArr.epochSecond,
            realArr = c.realArr.epochSecond,
            track = c.track,
            realtime = c.realtime,
            cancelled = c.cancelled,
            fromName = fromName,
            toName = toName,
        )
    }
}

/**
 * Bewertet die Pendelverbindung. Reine Logik ohne Android-Abhängigkeiten,
 * identisch mit tools/transitous_probe.py (gleiche Testfälle).
 */
object CommuteChecker {

    fun buildConnections(departures: List<StopTime>, arrivals: List<StopTime>): List<Connection> {
        val arrByTrip = LinkedHashMap<String, StopTime>()
        arrivals.forEach { arrByTrip.putIfAbsent(it.tripId, it) }
        return departures.mapNotNull { d ->
            val a = arrByTrip[d.tripId] ?: return@mapNotNull null
            val pDep = parse(d.place.scheduledDeparture) ?: return@mapNotNull null
            val pArr = parse(a.place.scheduledArrival) ?: return@mapNotNull null
            if (!pArr.isAfter(pDep)) return@mapNotNull null // Gegenrichtung
            Connection(
                tripId = d.tripId,
                line = d.lineName,
                headsign = d.headsign,
                plannedDep = pDep,
                realDep = parse(d.place.departure) ?: pDep,
                plannedArr = pArr,
                realArr = parse(a.place.arrival) ?: pArr,
                cancelled = d.cancelled || d.tripCancelled || a.cancelled || a.tripCancelled ||
                    d.place.cancelled || a.place.cancelled,
                realtime = d.realTime || a.realTime,
                track = d.place.track ?: d.place.scheduledTrack,
            )
        }.sortedBy { it.plannedDep }
    }

    fun matchesLine(c: Connection, line: String): Boolean {
        if (line.isBlank()) return true
        return normalize(c.line) == normalize(line)
    }

    fun evaluate(
        connections: List<Connection>,
        line: String,
        departure: LocalTime,
        serviceDate: LocalDate,
        thresholdMin: Int,
        zone: ZoneId,
    ): CheckResult {
        val wanted = serviceDate.atTime(departure).atZone(zone).toInstant()
        val target = connections.firstOrNull {
            matchesLine(it, line) && abs(Duration.between(wanted, it.plannedDep).seconds) <= 60
        } ?: return CheckResult(
            Status.UNBEKANNT, null, null, 0,
            "Keine ${line.ifBlank { "Fahrt" }} um ${departure.format(HM)} gefunden.",
        )

        val further = connections.count {
            it.plannedDep.isAfter(target.plannedDep) && it.cancelled && matchesLine(it, line)
        }
        val usable = connections.filter {
            it !== target && !it.cancelled && !it.realDep.isBefore(target.plannedDep)
        }
        fun hm(t: Instant) = t.atZone(zone).format(HM)

        if (target.cancelled) {
            val alt = usable.minByOrNull { it.realArr }
            val msg = buildString {
                append("${target.line} ${hm(target.plannedDep)} fällt aus.")
                if (alt != null) {
                    append(" Nächste: ${alt.line} ${hm(alt.realDep)}")
                    if (alt.delayDepMin > 0) append(" (+${alt.delayDepMin})")
                    append(", an ${hm(alt.realArr)}.")
                }
                if (further > 0) append(" Danach noch $further weitere Ausfälle.")
            }
            return CheckResult(Status.AUSFALL, target, alt, further, msg)
        }

        if (target.delayDepMin >= thresholdMin || target.delayArrMin >= thresholdMin) {
            val alt = usable.filter { it.realArr.isBefore(target.realArr) }.minByOrNull { it.realArr }
            val msg = buildString {
                append("${target.line} ${hm(target.plannedDep)} +${target.delayDepMin} min, ")
                append("an ${hm(target.realArr)} statt ${hm(target.plannedArr)}.")
                if (alt != null) append(" Schneller: ${alt.line} ${hm(alt.realDep)}, an ${hm(alt.realArr)}.")
            }
            return CheckResult(Status.VERSPAETET, target, alt, further, msg)
        }

        val suffix = if (target.realtime) "" else " (keine Echtzeitdaten)"
        return CheckResult(
            Status.PUENKTLICH, target, null, further,
            "${target.line} ${hm(target.plannedDep)} pünktlich, an ${hm(target.realArr)}.$suffix",
        )
    }

    /** Mindestzeit zum Umsteigen; war im Plan weniger vorgesehen, gilt der Plan. */
    const val MIN_TRANSFER_MIN = 2L

    /**
     * Bewertet eine Verbindung mit Umstiegen aus den Ergebnissen ihrer Teilfahrten (je ein [evaluate]):
     * Ausfall einer Teilfahrt, geplatzter Anschluss, Verspätung am Ziel. [transferStops] sind die
     * Halte zwischen Teilfahrt i und i+1.
     */
    fun combine(results: List<CheckResult>, transferStops: List<String>, thresholdMin: Int, zone: ZoneId): CheckResult {
        fun hm(t: Instant) = t.atZone(zone).format(HM)
        results.firstOrNull { it.target == null }?.let { return it }
        val legs = results.map { it.target!! }
        val first = legs.first()
        val last = legs.last()
        val whole = Connection(
            tripId = first.tripId,
            line = legs.joinToString(" → ") { it.line },
            headsign = last.headsign,
            plannedDep = first.plannedDep,
            realDep = first.realDep,
            plannedArr = last.plannedArr,
            realArr = last.realArr,
            cancelled = legs.any { it.cancelled },
            realtime = legs.any { it.realtime },
            track = first.track,
        )
        legs.firstOrNull { it.cancelled }?.let { c ->
            return CheckResult(Status.AUSFALL, whole, null, 0, "${c.line} ${hm(c.plannedDep)} fällt aus.", legs)
        }
        for (i in 1 until legs.size) {
            val a = legs[i - 1]
            val b = legs[i]
            val plannedGap = Duration.between(a.plannedArr, b.plannedDep).toMinutes().coerceAtLeast(0)
            val needed = minOf(plannedGap, MIN_TRANSFER_MIN)
            if (a.realArr.plusSeconds(needed * 60).isAfter(b.realDep)) {
                val problem = "Umstieg in ${transferStops.getOrElse(i - 1) { "?" }}: an ${hm(a.realArr)}, " +
                    "${b.line} fährt ${hm(b.realDep)}"
                return CheckResult(Status.ANSCHLUSS, whole, null, 0, "Anschluss gefährdet. $problem.", legs, problem)
            }
        }
        if (whole.delayDepMin >= thresholdMin || whole.delayArrMin >= thresholdMin) {
            return CheckResult(
                Status.VERSPAETET, whole, null, 0,
                "Ankunft ${hm(last.realArr)} statt ${hm(last.plannedArr)}, alle Anschlüsse klappen.", legs,
            )
        }
        val suffix = if (whole.realtime) "" else " (keine Echtzeitdaten)"
        return CheckResult(Status.PUENKTLICH, whole, null, 0, "Alle Anschlüsse klappen, an ${hm(last.realArr)}.$suffix", legs)
    }

    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun normalize(s: String) = cleanLine(s).filterNot { it.isWhitespace() }.lowercase()

    /** "RE50 (16522)" -> "RE50": Die Zugnummer in Klammern gehört nicht zur Linie. */
    fun cleanLine(s: String): String = s.substringBefore(" (").trim()

    fun parse(s: String?): Instant? =
        s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
}

private fun minutesBetween(a: Instant, b: Instant): Int =
    (Duration.between(a, b).seconds / 60.0).roundToInt()
