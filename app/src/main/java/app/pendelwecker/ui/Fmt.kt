package app.pendelwecker.ui

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Kleine Formatierhilfen für Uhrzeiten und Wochentage. */
object Fmt {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    fun hm(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochSecond(epochSec).atZone(zone).format(HM)

    fun hm(t: ZonedDateTime): String = t.format(HM)

    fun day(d: DayOfWeek): String = DAYS[d.value - 1]

    /** "Mo–Fr", "Mo, Mi, Fr", "täglich", "Sa–So". */
    fun weekdays(days: Set<Int>): String {
        if (days.isEmpty()) return "keine Tage"
        if (days.size == 7) return "täglich"
        val sorted = days.sorted()
        val contiguous = sorted.last() - sorted.first() == sorted.size - 1
        return if (contiguous && sorted.size >= 3) "${DAYS[sorted.first() - 1]}–${DAYS[sorted.last() - 1]}"
        else sorted.joinToString(", ") { DAYS[it - 1] }
    }

    /** "heute", "morgen" oder der Wochentag. */
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "heute"
        today.plusDays(1) -> "morgen"
        else -> day(date.dayOfWeek)
    }

    fun minutes(n: Int): String = if (n == 1) "1 Minute" else "$n Minuten"
}
