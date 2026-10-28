package app.pendelwecker.domain

import app.pendelwecker.data.Place
import app.pendelwecker.data.StopTime
import app.pendelwecker.data.TripInfo
import app.pendelwecker.data.TripSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Gleiche Fälle wie tools/test_probe.py. */
class CommuteCheckerTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val day = LocalDate.of(2026, 10, 27)

    private fun ts(hhmm: String) = day.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant().toString()

    private fun dep(trip: String, plan: String, real: String = plan, line: String = "S8", cancelled: Boolean = false) =
        StopTime(
            place = Place("Kamenz", lat = 0.0, lon = 0.0, scheduledDeparture = ts(plan), departure = ts(real), track = "1"),
            tripId = trip, displayName = line, realTime = true, cancelled = cancelled, tripCancelled = cancelled,
        )

    private fun arr(trip: String, plan: String, real: String = plan, line: String = "S8", cancelled: Boolean = false) =
        StopTime(
            place = Place("Dresden Hbf", lat = 0.0, lon = 0.0, scheduledArrival = ts(plan), arrival = ts(real)),
            tripId = trip, displayName = line, realTime = true, cancelled = cancelled, tripCancelled = cancelled,
        )

    private fun eval(deps: List<StopTime>, arrs: List<StopTime>, line: String = "S8", at: String = "06:03") =
        CommuteChecker.evaluate(
            CommuteChecker.buildConnections(deps, arrs), line, LocalTime.parse(at), day, 3, zone,
        )

    @Test fun onlyTripsServingBothInOrder() {
        val conns = CommuteChecker.buildConnections(
            listOf(dep("a", "06:03"), dep("b", "06:10"), dep("c", "06:20")),
            listOf(arr("a", "06:42"), arr("c", "06:00")),
        )
        assertEquals(listOf("a"), conns.map { it.tripId })
    }

    @Test fun onTime() = assertEquals(Status.PUENKTLICH, eval(listOf(dep("a", "06:03")), listOf(arr("a", "06:42"))).status)

    @Test fun cancelledWithNextTrain() {
        val r = eval(
            listOf(dep("a", "06:03", cancelled = true), dep("b", "06:33"), dep("c", "07:03", cancelled = true)),
            listOf(arr("a", "06:42", cancelled = true), arr("b", "07:12"), arr("c", "07:42", cancelled = true)),
        )
        assertEquals(Status.AUSFALL, r.status)
        assertEquals("b", r.alternative?.tripId)
        assertEquals(1, r.furtherCancellations)
    }

    @Test fun otherLineCountsAsAlternative() {
        val r = eval(
            listOf(dep("a", "06:03", cancelled = true), dep("re", "06:15", line = "RB33")),
            listOf(arr("a", "06:42", cancelled = true), arr("re", "06:50", line = "RB33")),
        )
        assertEquals("re", r.alternative?.tripId)
    }

    @Test fun delayedEarlierTrainIsAlternative() {
        val r = eval(
            listOf(dep("x", "05:33", "06:08"), dep("a", "06:03", cancelled = true)),
            listOf(arr("x", "06:12", "06:47"), arr("a", "06:42", cancelled = true)),
        )
        assertEquals("x", r.alternative?.tripId)
    }

    @Test fun delayed() =
        assertEquals(Status.VERSPAETET, eval(listOf(dep("a", "06:03", "06:10")), listOf(arr("a", "06:42", "06:49"))).status)

    @Test fun smallDelayBelowThreshold() =
        assertEquals(Status.PUENKTLICH, eval(listOf(dep("a", "06:03", "06:05")), listOf(arr("a", "06:42", "06:44"))).status)

    @Test fun lineNameWithSpace() =
        assertEquals(Status.PUENKTLICH, eval(listOf(dep("a", "06:03", line = "S 8")), listOf(arr("a", "06:42", line = "S 8"))).status)

    @Test fun unknown() = assertEquals(Status.UNBEKANNT, eval(listOf(dep("a", "06:10")), listOf(arr("a", "06:50"))).status)

    @Test fun polylineGoogleReference() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453)), pts)
    }

    @Test fun trainIsInterpolatedHalfway() {
        val seg = TripSegment(
            trips = listOf(TripInfo("t1", displayName = "S8")),
            from = Place("A", lat = 0.0, lon = 0.0), to = Place("B", lat = 0.0, lon = 0.0),
            departure = "2026-10-27T05:00:00Z", arrival = "2026-10-27T05:10:00Z",
            scheduledDeparture = "2026-10-27T04:58:00Z", scheduledArrival = "2026-10-27T05:08:00Z",
            realTime = true,
            polyline = "_p~iF~ps|U_ulLnnqC", // (38.5,-120.2) -> (40.7,-120.95)
        )
        val pos = TrainPositions.at(Instant.parse("2026-10-27T05:05:00Z"), TrainPositions.prepare(listOf(seg)))
        assertEquals(1, pos.size)
        assertEquals(39.6, pos[0].position.lat, 0.01)
        assertEquals(2, pos[0].delayMin)
        assertTrue(!pos[0].standing)
    }

    // ------------------------------------------------------------ Umstiege

    private fun inst(hhmm: String) = day.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant()

    private fun leg(line: String, dep: String, arr: String, depReal: String = dep, arrReal: String = arr, cancelled: Boolean = false) =
        CheckResult(
            Status.PUENKTLICH,
            Connection("t-$line", line, "", inst(dep), inst(depReal), inst(arr), inst(arrReal), cancelled, true, null),
            null, 0, "",
        )

    private fun combine(vararg legs: CheckResult) =
        CommuteChecker.combine(legs.toList(), listOf("Neustadt", "Hbf"), 3, zone)

    @Test fun transfersAllWork() {
        val r = combine(leg("S8", "06:03", "06:47"), leg("S1", "06:50", "06:57"))
        assertEquals(Status.PUENKTLICH, r.status)
        assertEquals("S8 → S1", r.target!!.line)
        assertEquals(inst("06:57"), r.target!!.plannedArr)
    }

    @Test fun lateFeederBreaksTransfer() {
        val r = combine(leg("S8", "06:03", "06:47", arrReal = "06:50"), leg("S1", "06:50", "06:57"))
        assertEquals(Status.ANSCHLUSS, r.status)
        assertTrue(r.transferProblem!!.contains("Neustadt"))
    }

    @Test fun tightPlannedTransferStaysValid() {
        // Laut Plan nur 1 Minute zum Umsteigen: gilt, solange niemand zu spät ist.
        val r = combine(leg("S8", "06:03", "06:49"), leg("S1", "06:50", "06:57"))
        assertEquals(Status.PUENKTLICH, r.status)
    }

    @Test fun cancelledSecondLeg() {
        val r = combine(leg("S8", "06:03", "06:47"), leg("S1", "06:50", "06:57", cancelled = true))
        assertEquals(Status.AUSFALL, r.status)
        assertTrue(r.message.startsWith("S1"))
    }

    @Test fun delayedArrivalWithWorkingTransfer() {
        val r = combine(leg("S8", "06:03", "06:47"), leg("S1", "06:50", "06:57", depReal = "06:55", arrReal = "07:02"))
        assertEquals(Status.VERSPAETET, r.status)
        assertEquals(5, r.target!!.delayArrMin)
    }

    @Test fun missingLegIsUnknown() {
        val unknown = CheckResult(Status.UNBEKANNT, null, null, 0, "Keine S1 um 06:50 gefunden.")
        assertEquals(Status.UNBEKANNT, combine(leg("S8", "06:03", "06:47"), unknown).status)
    }

    @Test fun trainNumberInBracketsStillMatchesLine() {
        val r = eval(listOf(dep("a", "06:03", line = "RE50 (16522)")), listOf(arr("a", "06:42", line = "RE50 (16522)")), line = "RE50")
        assertEquals(Status.PUENKTLICH, r.status)
    }
}
