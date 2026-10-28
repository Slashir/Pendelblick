package app.pendelwecker.data

import app.pendelwecker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Schlanker Client für die Transitous-API (MOTIS 2). Die App fragt nur ab, was der
 * Nutzer gerade braucht: Abfahrten am Start, Ankünfte am Ziel, Fahrten im Kartenausschnitt.
 *
 * Nutzungsrichtlinie: https://transitous.org/api/
 *  - User-Agent mit App-Name, Version und Kontakt (siehe [userAgent])
 *  - sichtbarer Quellenhinweis in der App
 *  - App muss Open Source und nicht kommerziell sein
 *
 * [version] ist die Endpunktversion. v5 gibt es seit MOTIS 2.5, v6 seit 2.9.
 * Welche Transitous gerade anbietet, zeigt tools/transitous_probe.py.
 */
class TransitousApi(
    private val http: OkHttpClient = defaultClient,
    private val base: String = BuildConfig.TRANSITOUS_BASE,
    var version: String = "v5",
    /** Wird auf false gesetzt, falls der Server den Parameter realtimeMode ablehnt. */
    var sendRealtimeMode: Boolean = true,
) {
    suspend fun geocodeStops(text: String): List<Match> =
        get(url("/api/v1/geocode") {
            addQueryParameter("text", text)
            addQueryParameter("type", "STOP")
        })

    /**
     * Abfahrten (arriveBy=false) oder Ankünfte (arriveBy=true) ab [from] für [windowSeconds].
     * REALTIME_ANNOTATION_ONLY: sortiert nach Plan, mit Echtzeit und Ausfällen markiert.
     */
    suspend fun stopTimes(
        stopId: String,
        from: Instant,
        arriveBy: Boolean,
        windowSeconds: Int,
        n: Int,
    ): StopTimesResponse =
        get(url("/api/$version/stoptimes") {
            addQueryParameter("stopId", stopId)
            addQueryParameter("time", iso(from))
            addQueryParameter("arriveBy", arriveBy.toString())
            addQueryParameter("direction", "LATER")
            addQueryParameter("window", windowSeconds.toString())
            addQueryParameter("n", n.toString())
            if (sendRealtimeMode) addQueryParameter("realtimeMode", "REALTIME_ANNOTATION_ONLY")
        })

    /** Verbindungen mit Umstiegen von Halt zu Halt ab [time] (Endpunkt plan, zählt als Routing-Anfrage). */
    suspend fun plan(fromStopId: String, toStopId: String, time: Instant): PlanResponse =
        get(client = routingClient, url = url("/api/$version/plan") {
            addQueryParameter("fromPlace", fromStopId)
            addQueryParameter("toPlace", toStopId)
            addQueryParameter("time", iso(time))
            addQueryParameter("arriveBy", "false")
        })

    suspend fun trip(tripId: String): Itinerary =
        get(url("/api/$version/trip") { addQueryParameter("tripId", tripId) })

    /**
     * Alle Fahrten im Kartenausschnitt und Zeitfenster.
     * Hinweis: Die Spezifikation beschreibt min als "lower right" und max als "upper left".
     * Hier wird min = Süd-West, max = Nord-Ost geschickt; das Testskript prüft beide Lesarten.
     */
    suspend fun mapTrips(
        minLat: Double, minLon: Double,
        maxLat: Double, maxLon: Double,
        zoom: Double,
        start: Instant, end: Instant,
    ): List<TripSegment> =
        get(url("/api/$version/map/trips") {
            addQueryParameter("zoom", zoom.toString())
            addQueryParameter("min", "$minLat,$minLon")
            addQueryParameter("max", "$maxLat,$maxLon")
            addQueryParameter("startTime", iso(start))
            addQueryParameter("endTime", iso(end))
            addQueryParameter("precision", "5")
        })

    // ------------------------------------------------------------------ intern

    private fun url(path: String, block: HttpUrl.Builder.() -> Unit): HttpUrl =
        (base + path).toHttpUrl().newBuilder().apply(block).build()

    private suspend inline fun <reified T> get(url: HttpUrl, client: OkHttpClient = http): T {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        val body = execute(client, request, url.encodedPath)
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }
    }

    /** Abbrechbar: Endet die Koroutine (z. B. Zeitlimit des Alarms), wird auch die HTTP-Anfrage abgebrochen. */
    private suspend fun execute(client: OkHttpClient, request: Request, path: String): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) cont.resumeWithException(ApiException(it.code, path))
                        else runCatching { it.body.string() }
                            .onSuccess { body -> cont.resume(body) }
                            .onFailure { e -> cont.resumeWithException(e) }
                    }
                }
            })
        }

    class ApiException(val code: Int, val path: String) : IOException("HTTP $code bei $path")

    companion object {
        val userAgent = "Pendelblick/${BuildConfig.VERSION_NAME} (+${BuildConfig.CONTACT})"

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            // Gesamtlimit pro Anfrage: der Alarm-Receiver hat nur ~10 s.
            .callTimeout(4, TimeUnit.SECONDS)
            .build()

        // Ganze Sekunden: Bruchteile versteht der Server nicht und liefert dann Fahrten aus falscher Zeit.
        /** Routing rechnet länger als eine Abfahrtsliste. */
        val routingClient: OkHttpClient = defaultClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

        private fun iso(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(t.truncatedTo(ChronoUnit.SECONDS))
    }
}
