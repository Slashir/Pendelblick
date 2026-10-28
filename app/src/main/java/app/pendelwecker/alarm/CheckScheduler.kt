package app.pendelwecker.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.pendelwecker.data.Store
import app.pendelwecker.domain.CommuteProfile
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Plant die nächste Prüfung auf dem Gerät – ohne eigenen Server.
 *
 * Android 14+: Die Berechtigung für minutengenaue Alarme (SCHEDULE_EXACT_ALARM) ist bei
 * neu installierten Apps standardmäßig verweigert. Die App bittet den Nutzer einmal darum
 * (siehe MainActivity). Ohne Erlaubnis wird auf setWindow() mit 10-Minuten-Fenster ausgewichen,
 * das Fenster endet am geplanten Prüfzeitpunkt.
 *
 * Jeder Alarm trägt seinen geplanten Prüfzeitpunkt und das Fahrtdatum. Ist ein Zeitpunkt erledigt,
 * merkt sich das der Store; geplant wird immer erst der nächste danach. Sonst würde ein zu früh
 * zugestellter Alarm (Fenster) denselben Zeitpunkt sofort wieder planen und sich endlos wiederholen.
 */
object CheckScheduler {

    private val WINDOW: Duration = Duration.ofMinutes(10)

    /** Ein Prüfzeitpunkt und der Fahrttag, zu dem er gehört (kann der Vortag sein, z. B. 23:30 für 00:15). */
    data class CheckPoint(val at: ZonedDateTime, val serviceDate: LocalDate)

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val store = Store(context)
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        store.profiles().forEach { p ->
            val next = nextCheck(p, now, store.lastHandledCheck(p.id))
            val pi = pendingIntent(context, p.id, next)
            am.cancel(pi)
            if (next == null) return@forEach
            val at = next.at.toInstant().toEpochMilli()
            try {
                if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setWindow(AlarmManager.RTC_WAKEUP, at - WINDOW.toMillis(), WINDOW.toMillis(), pi)
            } catch (e: SecurityException) {
                // Erlaubnis zwischen Prüfung und Aufruf entzogen: ungenau planen.
                am.setWindow(AlarmManager.RTC_WAKEUP, at - WINDOW.toMillis(), WINDOW.toMillis(), pi)
            }
        }
    }

    fun canExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /**
     * Nächster Prüfzeitpunkt nach jetzt und nach dem zuletzt erledigten ([handledEpochMs]).
     * Fahrttage ab gestern, damit Umstiegsprüfungen nach Mitternacht nicht verloren gehen.
     */
    fun nextCheck(p: CommuteProfile, now: ZonedDateTime, handledEpochMs: Long = 0): CheckPoint? {
        if (!p.active || !p.isComplete) return null
        val after = maxOf(now.plusSeconds(30).toInstant().toEpochMilli(), handledEpochMs)
        for (dayOffset in -1L..7L) {
            val day = now.toLocalDate().plusDays(dayOffset)
            if (!p.runsOn(day.dayOfWeek)) continue
            val candidate = checkTimes(p, day, now.zone).firstOrNull { it.toInstant().toEpochMilli() > after }
            if (candidate != null) return CheckPoint(candidate, day)
        }
        return null
    }

    /** Alle Prüfzeitpunkte eines Fahrttags: vor der ersten Abfahrt und kurz vor jedem Umstieg. */
    fun checkTimes(p: CommuteProfile, day: LocalDate, zone: ZoneId): List<ZonedDateTime> {
        val starts = p.legStarts(day, zone)
        val first = starts.firstOrNull() ?: return emptyList()
        return (p.checkOffsetsMin.map { first.minusMinutes(it.toLong()) } + starts.drop(1).map { it.minus(TRANSFER_CHECK) })
            .sorted()
    }

    private val TRANSFER_CHECK: Duration = Duration.ofMinutes(6)

    private fun pendingIntent(context: Context, profileId: Int, next: CheckPoint?): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + profileId,
            Intent(context, CheckAlarmReceiver::class.java).apply {
                putExtra(EXTRA_PROFILE, profileId)
                if (next != null) {
                    putExtra(EXTRA_AT, next.at.toInstant().toEpochMilli())
                    putExtra(EXTRA_DATE, next.serviceDate.toEpochDay())
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    const val EXTRA_PROFILE = "profile"
    const val EXTRA_AT = "at"
    const val EXTRA_DATE = "date"
    private const val REQUEST_BASE = 4200
}
