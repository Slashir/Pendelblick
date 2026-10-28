package app.pendelwecker.alarm

import android.content.Context
import app.pendelwecker.data.Itinerary
import app.pendelwecker.data.Leg
import app.pendelwecker.data.Store
import app.pendelwecker.data.TransitousApi
import app.pendelwecker.domain.CheckResult
import app.pendelwecker.domain.CommuteChecker
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.Connection
import app.pendelwecker.domain.PlannedLeg
import app.pendelwecker.domain.Status
import app.pendelwecker.domain.StatusSnapshot
import app.pendelwecker.domain.TripTimes
import app.pendelwecker.widget.CommuteWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Führt die eigentliche Prüfung aus: je Teilfahrt zwei kleine Abfragen (Abfahrten am Start,
 * Ankünfte am Ziel), Bewertung, Benachrichtigung, Widget.
 */
object CommuteRunner {

    /** Wie lange nach der letzten Abfahrt eine Prüfung noch sinnvoll ist. */
    private val RELEVANT_AFTER: Duration = Duration.ofMinutes(30)

    /** So lange gilt eine gefundene Ersatzverbindung für dasselbe Problem. */
    private val REUSE_REPLACEMENT: Duration = Duration.ofMinutes(20)

    /** Ein Fehlerstand überschreibt keinen gültigen Stand, der jünger ist als das. */
    private val KEEP_VALID: Duration = Duration.ofHours(3)

    /** Endpunktversionen der MOTIS-API, neueste zuerst. */
    private val API_VERSIONS = listOf("v6", "v5", "v4")

    /** Nie zwei Prüfungen gleichzeitig: sonst überschreiben sie sich gegenseitig den gespeicherten Stand. */
    private val lock = Mutex()

    /** Ergebnis einer geplanten Prüfung für Receiver und Worker. */
    data class Outcome(
        /** Netz- oder Serverfehler: später erneut versuchen. */
        val retry: Boolean = false,
        /** Problem erkannt, Ersatzverbindung (Routing) steht noch aus. */
        val needsRouting: Boolean = false,
    )

    /**
     * Eine geplante Prüfung genau einer Fahrt. Der Prüfzeitpunkt wird zuerst als erledigt vermerkt,
     * damit [CheckScheduler] ihn nie erneut plant. Routing nur, wenn [allowRouting] (im Worker, nicht im
     * Alarm mit seinem knappen Zeitlimit).
     */
    suspend fun runScheduled(context: Context, job: CheckJob, allowRouting: Boolean): Outcome = lock.withLock {
        val store = Store(context)
        val zone = ZoneId.systemDefault()
        store.markHandled(job.profileId, job.atEpochMs)
        try {
            val p = store.profiles().firstOrNull { it.id == job.profileId }?.takeIf { it.active && it.isComplete }
                ?: return@withLock Outcome()
            val starts = p.legStarts(job.serviceDate, zone)
            // Längst vergangen (z. B. nach langem Ruhemodus): nichts mehr prüfen oder melden.
            if (Instant.now().isAfter(starts.last().plus(RELEVANT_AFTER).toInstant())) return@withLock Outcome()

            val previous = store.snapshots().firstOrNull { it.profileId == p.id }
            val checked = checkDetailed(context, p, job.serviceDate, zone, previous, allowRouting)
            val snapshot = checked.snapshot
            if (snapshot.status == Status.FEHLER) {
                if (!keepsValid(previous)) store.saveSnapshot(snapshot)
                return@withLock Outcome(retry = !checked.permanentError)
            }
            store.saveSnapshot(snapshot)

            val lastCheck = starts.first().minusMinutes((p.checkOffsetsMin.minOrNull() ?: 0).toLong())
                .toInstant().toEpochMilli() == job.atEpochMs
            when {
                Notifier.shouldNotify(previous, snapshot, p.notifyWhenOnTime && lastCheck) -> Notifier.show(context, p, snapshot)
                // Ersatzverbindung nachgereicht: bestehende Meldung leise ergänzen.
                Notifier.addsAlternative(previous, snapshot) -> Notifier.show(context, p, snapshot, silent = true)
            }
            val problem = snapshot.status == Status.AUSFALL || snapshot.status == Status.ANSCHLUSS
            Outcome(needsRouting = !allowRouting && problem && p.hasTransfers && snapshot.alternative == null)
        } finally {
            CheckScheduler.scheduleNext(context)
            CommuteWidget.refresh(context)
        }
    }

    /** Alle aktiven Fahrten für ihren nächsten Fahrtag aktualisieren (Widget-Knopf), ohne Benachrichtigung. */
    suspend fun refreshAll(context: Context) {
        lock.withLock {
            val store = Store(context)
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            for (p in store.profiles().filter { it.active && it.isComplete }) {
                val previous = store.snapshots().firstOrNull { it.profileId == p.id }
                val snapshot = check(context, p, serviceDate(p, now), zone, previous)
                if (snapshot.status != Status.FEHLER || !keepsValid(previous)) store.saveSnapshot(snapshot)
            }
        }
        CommuteWidget.refresh(context)
    }

    /**
     * Prüft ein Profil sofort ("Jetzt prüfen"), auch wenn es nicht aktiv ist. Geprüft wird die
     * nächste Fahrt laut Wochentagen; ist die heutige schon länger vorbei, die am nächsten Fahrtag.
     */
    suspend fun checkNow(context: Context, p: CommuteProfile): StatusSnapshot {
        val snapshot = lock.withLock {
            val store = Store(context)
            val zone = ZoneId.systemDefault()
            val previous = store.snapshots().firstOrNull { it.profileId == p.id }
            check(context, p, serviceDate(p, ZonedDateTime.now(zone)), zone, previous).also { store.saveSnapshot(it) }
        }
        CommuteWidget.refresh(context)
        return snapshot
    }

    /** Ein frischer, gültiger Stand (kein Fehler) wird durch einen Netzfehler nicht überschrieben. */
    private fun keepsValid(previous: StatusSnapshot?): Boolean =
        previous != null && previous.status != Status.FEHLER &&
            Instant.now().epochSecond - previous.checkedAtEpochSec < KEEP_VALID.seconds

    /** Der Fahrtag, dessen Fahrt als Nächstes ansteht (heute, solange die letzte Teilfahrt nicht lange vorbei ist). */
    fun serviceDate(p: CommuteProfile, now: ZonedDateTime): LocalDate {
        for (offset in -1L..7L) {
            val day = now.toLocalDate().plusDays(offset)
            if (!p.runsOn(day.dayOfWeek)) continue
            val last = runCatching { p.legStarts(day, now.zone).last() }.getOrNull() ?: continue
            if (last.plus(RELEVANT_AFTER).isAfter(now)) return day
        }
        return now.toLocalDate()
    }

    suspend fun check(
        context: Context, p: CommuteProfile, date: LocalDate, zone: ZoneId,
        previous: StatusSnapshot? = null, allowRouting: Boolean = true,
    ): StatusSnapshot = checkDetailed(context, p, date, zone, previous, allowRouting).snapshot

    private class Checked(val snapshot: StatusSnapshot, val permanentError: Boolean)

    private suspend fun checkDetailed(
        context: Context, p: CommuteProfile, date: LocalDate, zone: ZoneId,
        previous: StatusSnapshot?, allowRouting: Boolean,
    ): Checked {
        val store = Store(context)
        val api = TransitousApi(version = store.apiVersion, sendRealtimeMode = store.realtimeModeSupported)
        var permanent = false
        val result: CheckResult = try {
            withFallbacks(api, store) { evaluate(it, p, date, zone, previous, allowRouting) }
        } catch (e: CancellationException) {
            throw e // Zeitlimit des Alarms: nicht als Fehler speichern
        } catch (e: TransitousApi.ApiException) {
            // 400/404 nach allen Ausweichversuchen: Halt oder Fahrt gibt es beim Anbieter nicht mehr.
            permanent = e.code == 400 || e.code == 404
            failure(
                if (permanent) "Halt nicht mehr im Fahrplan gefunden. Bitte die Verbindung neu wählen."
                else "Fahrplandienst nicht erreichbar (HTTP ${e.code})."
            )
        } catch (e: Exception) {
            failure("Keine Verbindung zum Fahrplandienst.")
        }
        val snapshot = StatusSnapshot(
            profileId = p.id,
            label = p.label,
            status = result.status,
            message = result.message,
            checkedAtEpochSec = Instant.now().epochSecond,
            target = result.target?.let { TripTimes.of(it, p.fromName, p.toName) },
            alternative = result.alternative?.let(TripTimes::of),
            furtherCancellations = result.furtherCancellations,
            legs = result.legs.mapIndexed { i, c ->
                val leg = p.trips.getOrNull(i)
                TripTimes.of(c, leg?.fromName.orEmpty(), leg?.toName.orEmpty())
            },
            transferProblem = result.transferProblem,
        )
        return Checked(snapshot, permanent)
    }

    /**
     * Alle Fahrten von [fromStopId] nach [toStopId] ab [from] für gut zwei Stunden (Laufwege der
     * eigenen Linien auf der Karte).
     */
    suspend fun connections(context: Context, fromStopId: String, toStopId: String, from: Instant): ConnectionSearch {
        val store = Store(context)
        val api = TransitousApi(version = store.apiVersion, sendRealtimeMode = store.realtimeModeSupported)
        return withFallbacks(api, store) {
            val deps = it.stopTimes(fromStopId, from, arriveBy = false, windowSeconds = 150 * 60, n = 150).stopTimes
            val arrs = it.stopTimes(toStopId, from, arriveBy = true, windowSeconds = 210 * 60, n = 250).stopTimes
            ConnectionSearch(
                connections = CommuteChecker.buildConnections(deps, arrs),
                // Wohin fahren die Züge am Start? Hilft, wenn es keine direkte Fahrt gibt.
                directions = deps.filter { d -> d.mode != "BUS" && d.mode != "TRAM" }.ifEmpty { deps }
                    .map { d -> d.lineName to d.headsign }
                    .distinct()
                    .take(6),
            )
        }
    }

    /** Ergebnis der Zugsuche. */
    data class ConnectionSearch(val connections: List<Connection>, val directions: List<Pair<String, String>>)

    /**
     * Nur für Abfahrtslisten (stoptimes): 404 -> andere API-Version probieren und die funktionierende
     * merken; 400 mit realtimeMode -> ohne den Parameter wiederholen, nur bei Erfolg dauerhaft abschalten.
     * Routing wird nie automatisch wiederholt – das ist die teure Anfrage.
     */
    private suspend fun <T> withFallbacks(api: TransitousApi, store: Store, block: suspend (TransitousApi) -> T): T {
        try {
            return block(api)
        } catch (e: TransitousApi.ApiException) {
            if (!e.path.endsWith("/stoptimes")) throw e
            when {
                e.code == 404 -> {
                    for (v in API_VERSIONS.filter { it != api.version }) {
                        api.version = v
                        try {
                            val r = block(api)
                            store.apiVersion = v
                            return r
                        } catch (e2: TransitousApi.ApiException) {
                            if (e2.code != 404) throw e2
                        }
                    }
                    throw e
                }
                e.code == 400 && api.sendRealtimeMode -> {
                    api.sendRealtimeMode = false
                    val r = block(api)
                    store.realtimeModeSupported = false
                    return r
                }
                else -> throw e
            }
        }
    }

    private suspend fun evaluate(
        api: TransitousApi, p: CommuteProfile, date: LocalDate, zone: ZoneId,
        previous: StatusSnapshot?, allowRouting: Boolean,
    ): CheckResult {
        if (p.hasTransfers) return evaluateLegs(api, p, date, zone, previous, allowRouting)
        val dep = p.legStarts(date, zone).first()
        val conns = fetchConnections(api, p.fromStopId, p.toStopId, dep.toInstant(), arrivalOf(p.arrival, dep))
        return CommuteChecker.evaluate(conns, p.line, p.departureTime, dep.toLocalDate(), p.delayThresholdMin, zone)
    }

    /**
     * Verbindung mit Umstiegen: jede Teilfahrt wie eine direkte Fahrt prüfen (gleichzeitig, damit der
     * Alarm in seinem Zeitlimit bleibt), dann Umstiege nachrechnen. Klappt es nicht, die nächste
     * Gesamtverbindung bis zum Ziel suchen.
     */
    private suspend fun evaluateLegs(
        api: TransitousApi, p: CommuteProfile, date: LocalDate, zone: ZoneId,
        previous: StatusSnapshot?, allowRouting: Boolean,
    ): CheckResult {
        val planned = p.trips.zip(p.legStarts(date, zone))
        val results = coroutineScope {
            planned.map { (leg, dep) ->
                async {
                    val conns = fetchConnections(api, leg.fromStopId, leg.toStopId, dep.toInstant(), arrivalOf(leg.arrival, dep))
                    CommuteChecker.evaluate(conns, leg.line, dep.toLocalTime(), dep.toLocalDate(), p.delayThresholdMin, zone)
                }
            }.awaitAll()
        }
        val result = CommuteChecker.combine(results, p.trips.dropLast(1).map { it.toName }, p.delayThresholdMin, zone)
        if (result.status != Status.AUSFALL && result.status != Status.ANSCHLUSS) return result
        // Routing ist die teure Anfrage (Transitous-Richtlinie): steht dasselbe Problem schon mit einer
        // Ersatzverbindung fest, die noch erreichbar ist, diese wiederverwenden.
        val now = Instant.now()
        previous?.takeIf {
            it.status == result.status && it.message == result.message &&
                now.epochSecond - it.checkedAtEpochSec < REUSE_REPLACEMENT.seconds
        }?.alternative?.takeIf { it.realDep >= now.epochSecond + 60 }
            ?.let { return result.copy(alternative = it.toConnection()) }
        if (!allowRouting) return result
        val alternative = try {
            replacement(api, p, result.legs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // ohne Ersatz bleibt die Warnung trotzdem richtig
        }
        return result.copy(alternative = alternative)
    }

    /**
     * Nächste Gesamtverbindung bis zum Ziel. Gesucht wird ab dem Halt der ersten Teilfahrt, die noch
     * nicht abgefahren ist, und frühestens ab der tatsächlichen Ankunft dort plus Umsteigezeit.
     * Sitzt der Nutzer schon in der letzten Teilfahrt, gibt es keinen sinnvollen Ersatz.
     */
    private suspend fun replacement(api: TransitousApi, p: CommuteProfile, legs: List<Connection>): Connection? {
        val now = Instant.now()
        val index = legs.indexOfFirst { it.realDep.isAfter(now) }
        if (index < 0) return null
        val earliest = if (index == 0) maxOf(now, legs[0].plannedDep)
        else maxOf(now, legs[index - 1].realArr.plusSeconds(CommuteChecker.MIN_TRANSFER_MIN * 60))
        val remaining = legs.drop(index)
        val sameAsPlanned = remaining.joinToString(" → ") { it.line }
        return api.plan(p.trips[index].fromStopId, p.trips.last().toStopId, earliest).itineraries
            .mapNotNull { asConnection(it) }
            .filter { !it.cancelled && !it.realDep.isBefore(earliest) }
            // Nicht die gerade geplatzte Verbindung als Ersatz anbieten.
            .filterNot { it.plannedDep == remaining.first().plannedDep && it.line == sameAsPlanned }
            .minByOrNull { it.realArr }
    }

    /** Eine gefundene Verbindung als eine Fahrt zusammengefasst ("Bus 21 → S8", erste Abfahrt, letzte Ankunft). */
    private fun asConnection(it: Itinerary): Connection? {
        val transit = it.legs.filter(::isTransit)
        if (transit.isEmpty()) return null
        val first = transit.first()
        val last = transit.last()
        return Connection(
            tripId = first.tripId.orEmpty(),
            line = transit.joinToString(" → ") { l -> lineOf(l) },
            headsign = last.to.name,
            plannedDep = CommuteChecker.parse(first.scheduledStartTime) ?: return null,
            realDep = CommuteChecker.parse(first.startTime) ?: return null,
            plannedArr = CommuteChecker.parse(last.scheduledEndTime) ?: return null,
            realArr = CommuteChecker.parse(last.endTime) ?: return null,
            cancelled = transit.any { l -> l.cancelled },
            realtime = transit.any { l -> l.realTime },
            track = first.from.track ?: first.from.scheduledTrack,
        )
    }

    /** Verbindungen ab [time] zum Auswählen beim Einrichten, auch mit Umstiegen. */
    suspend fun itineraries(context: Context, fromStopId: String, toStopId: String, time: Instant): List<Itinerary> {
        val store = Store(context)
        val api = TransitousApi(version = store.apiVersion, sendRealtimeMode = store.realtimeModeSupported)
        return api.plan(fromStopId, toStopId, time).itineraries.filter { it.legs.any(::isTransit) }
    }

    /**
     * Teilfahrten einer gewählten Verbindung zum Speichern im Profil; Fußwege entfallen. Fehlt einer
     * Teilfahrt eine Zeit oder ein Halt, ist die Verbindung unbrauchbar: leere Liste.
     */
    fun plannedLegs(it: Itinerary, zone: ZoneId): List<PlannedLeg> {
        val legs = it.legs.filter(::isTransit).map { l ->
            PlannedLeg(
                fromStopId = l.from.parentId ?: l.from.stopId.orEmpty(),
                fromName = l.from.name,
                fromLat = l.from.lat,
                fromLon = l.from.lon,
                toStopId = l.to.parentId ?: l.to.stopId.orEmpty(),
                toName = l.to.name,
                toLat = l.to.lat,
                toLon = l.to.lon,
                line = lineOf(l),
                mode = l.mode,
                departure = hm(l.scheduledStartTime, zone),
                arrival = hm(l.scheduledEndTime, zone),
            )
        }
        val valid = legs.all { l ->
            l.fromStopId.isNotBlank() && l.toStopId.isNotBlank() && l.departure.isNotBlank() && l.arrival.isNotBlank()
        }
        return if (valid) legs else emptyList()
    }

    fun isTransit(l: Leg): Boolean = l.tripId != null && l.mode != "WALK"

    fun lineOf(l: Leg): String = CommuteChecker.cleanLine(l.displayName.orEmpty()).ifBlank { "?" }

    private fun hm(iso: String, zone: ZoneId): String =
        CommuteChecker.parse(iso)?.atZone(zone)?.format(HM).orEmpty()

    private val HM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    /** Geplante Ankunft "HH:mm" als Zeitpunkt nach [dep] (über Mitternacht); null, wenn unbekannt. */
    private fun arrivalOf(arrival: String, dep: ZonedDateTime): Instant? {
        val t = runCatching { LocalTime.parse(arrival) }.getOrNull() ?: return null
        var at = dep.toLocalDate().atTime(t).atZone(dep.zone)
        if (at.isBefore(dep)) at = at.plusDays(1)
        return at.toInstant()
    }

    /**
     * Abfahrten am Start ab kurz vor der Abfahrt und Ankünfte am Ziel. Ist die Ankunft bekannt, beginnt
     * die Ankunftsliste kurz davor: An großen Bahnhöfen mit Tram und Bus wären die Einträge sonst
     * aufgebraucht, bevor der eigene Zug ankommt.
     */
    private suspend fun fetchConnections(
        api: TransitousApi, fromStopId: String, toStopId: String, dep: Instant, arrival: Instant?,
    ): List<Connection> {
        val start = dep.minus(Duration.ofMinutes(15))
        val deps = api.stopTimes(fromStopId, start, arriveBy = false, windowSeconds = 120 * 60, n = 150)
        val arrs = if (arrival != null) {
            api.stopTimes(toStopId, arrival.minus(Duration.ofMinutes(20)), arriveBy = true, windowSeconds = 180 * 60, n = 200)
        } else {
            api.stopTimes(toStopId, start, arriveBy = true, windowSeconds = 240 * 60, n = 300)
        }
        return CommuteChecker.buildConnections(deps.stopTimes, arrs.stopTimes)
    }

    private fun failure(message: String) = CheckResult(Status.FEHLER, null, null, 0, message)
}
