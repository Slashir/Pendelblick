package app.pendelwecker.alarm

import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.Polyline
import app.pendelwecker.domain.Status
import app.pendelwecker.domain.StatusSnapshot
import app.pendelwecker.domain.TripTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Fälle aus dem Code-Review: Wiederholschleife, Mitternacht, Benachrichtigungen. */
class SchedulingTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val monday = LocalDate.of(2026, 11, 2)

    private fun profile(dep: String, days: Set<Int> = setOf(1, 2, 3, 4, 5)) = CommuteProfile(
        id = 0, label = "Hinfahrt", fromStopId = "a", toStopId = "b", departure = dep,
        weekdays = days, active = true,
    )

    private fun at(date: LocalDate, hhmm: String) = LocalDateTime.of(date, java.time.LocalTime.parse(hhmm)).atZone(zone)

    @Test fun handledCheckIsNotPlannedAgain() {
        // Fenster-Alarm für 05:18 kam um 05:11; danach darf nicht wieder 05:18 geplant werden.
        val p = profile("06:03")
        val first = CheckScheduler.nextCheck(p, at(monday, "05:11"))!!
        assertEquals(at(monday, "05:18"), first.at)
        val next = CheckScheduler.nextCheck(p, at(monday, "05:11"), first.at.toInstant().toEpochMilli())!!
        assertEquals(at(monday, "05:43"), next.at)
    }

    @Test fun checkBeforeMidnightBelongsToNextDay() {
        // Abfahrt Montag 00:15, Prüfung Sonntag 23:30 gehört zum Fahrtag Montag.
        val p = profile("00:15")
        val c = CheckScheduler.nextCheck(p, at(monday.minusDays(1), "23:00"))!!
        assertEquals(at(monday.minusDays(1), "23:30"), c.at)
        assertEquals(monday, c.serviceDate)
    }

    @Test fun transferCheckAfterMidnightKeepsServiceDay() {
        val p = profile("23:50", days = setOf(1)).copy(
            legs = listOf(
                app.pendelwecker.domain.PlannedLeg("a", "A", 0.0, 0.0, "m", "M", 0.0, 0.0, "S1", departure = "23:50", arrival = "00:02"),
                app.pendelwecker.domain.PlannedLeg("m", "M", 0.0, 0.0, "b", "B", 0.0, 0.0, "S8", departure = "00:10", arrival = "00:40"),
            ),
        )
        // Dienstag 00:01: Umstiegsprüfung 00:04 gehört noch zum Fahrtag Montag.
        val c = CheckScheduler.nextCheck(p, at(monday.plusDays(1), "00:01"))!!
        assertEquals(at(monday.plusDays(1), "00:04"), c.at)
        assertEquals(monday, c.serviceDate)
    }

    private fun snap(status: Status, dep: Long, message: String = "x", delay: Int = 0, transfer: String? = null) = StatusSnapshot(
        profileId = 0, label = "Hinfahrt", status = status, message = message, checkedAtEpochSec = dep - 3000,
        target = TripTimes("S8", plannedDep = dep, realDep = dep + delay * 60L, plannedArr = dep + 2600, realArr = dep + 2600 + delay * 60L),
        transferProblem = transfer,
    )

    @Test fun sameCancellationNextDayNotifiesAgain() {
        val mon = at(monday, "06:03").toEpochSecond()
        val tue = at(monday.plusDays(1), "06:03").toEpochSecond()
        val previous = snap(Status.AUSFALL, mon, "S8 06:03 fällt aus.")
        assertTrue(Notifier.shouldNotify(previous, snap(Status.AUSFALL, tue, "S8 06:03 fällt aus."), false))
    }

    @Test fun changingMinutesDoNotNotifyAgain() {
        val dep = at(monday, "06:03").toEpochSecond()
        val a = snap(Status.ANSCHLUSS, dep, transfer = "Umstieg in Neustadt: an 06:50, S1 fährt 06:50")
        val b = snap(Status.ANSCHLUSS, dep, transfer = "Umstieg in Neustadt: an 06:51, S1 fährt 06:50")
        assertFalse(Notifier.shouldNotify(a, b, false))
        assertFalse(Notifier.shouldNotify(snap(Status.VERSPAETET, dep, delay = 6), snap(Status.VERSPAETET, dep, delay = 7), false))
        assertTrue(Notifier.shouldNotify(snap(Status.VERSPAETET, dep, delay = 6), snap(Status.VERSPAETET, dep, delay = 12), false))
    }

    @Test fun truncatedPolylineDoesNotCrash() {
        val full = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        assertNotNull(Polyline.decode(full.dropLast(3)))
    }
}
