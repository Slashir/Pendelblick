package app.pendelwecker.map

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle

import androidx.lifecycle.repeatOnLifecycle
import app.pendelwecker.BuildConfig
import app.pendelwecker.alarm.CommuteRunner
import app.pendelwecker.data.Leg
import app.pendelwecker.data.Place
import app.pendelwecker.data.Store
import app.pendelwecker.data.TransitousApi
import app.pendelwecker.domain.CommuteChecker
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.LatLon
import app.pendelwecker.domain.Polyline
import app.pendelwecker.domain.TrainPosition
import app.pendelwecker.domain.TrainPositions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Live-Karte nach dem TRAVIC-Prinzip, für den Vollbild-Bildschirm wie für die Vorschau auf der Startseite.
 *
 *  - einmal pro Minute (und nach dem Verschieben): Transitous map/trips für den Ausschnitt und
 *    ein Zeitfenster (jetzt − 2 min bis jetzt + 15 min) holen
 *  - jede Sekunde: Positionen auf dem Gerät aus Fahrplan + Verspätung interpolieren (kein GPS)
 *  - Laufwege: map/trips liefert nur, was in den nächsten Minuten befahren wird. Den ganzen Weg
 *    holt die Karte einmal je eingerichteter Fahrt und für jedes angetippte Fahrzeug (Endpunkt trip).
 *  - Abfragen laufen nur, solange die Karte sichtbar ist
 *
 * Kartenhintergrund: OpenFreeMap (ohne API-Key), Attribution setzt MapLibre automatisch.
 */
class LiveMap(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lifecycle: Lifecycle,
    private val mapView: MapView,
    private val profiles: List<CommuteProfile>,
    /** Vorschau: keine Gesten, nur die eigenen Linien, keine Auswahl. */
    private val preview: Boolean,
) {
    private val store = Store(context)
    private val api = TransitousApi(version = store.apiVersion)
    private var map: MapLibreMap? = null
    private var prepared: List<TrainPositions.Prepared> = emptyList()
    private var selectionJob: Job? = null

    /** Ausschnitt der letzten Abfrage: Süd, West, Nord, Ost. */
    private var fetchedBox: DoubleArray? = null
    private var lastFetch = 0L

    /** Linien der eingerichteten Fahrten, normalisiert (ohne Leerzeichen, klein). */
    val myLineNames: List<String> = profiles.filter { it.isComplete }.flatMap { it.lines }.map { it.trim() }.distinct()
    val myLines: Set<String> = myLineNames.map { norm(it) }.toSet()

    var onlyMine by mutableStateOf(!preview && store.mapOnlyMine && myLines.isNotEmpty())
        private set
    var selected by mutableStateOf<Selection?>(null)
        private set
    /** Wie viele Fahrzeuge der eigenen Linien gerade unterwegs sind (für die Vorschau). */
    var ownMoving by mutableStateOf(0)
        private set
    /** Zu weit herausgezoomt: Fahrzeuge werden nicht geladen (Hinweis in der Oberfläche). */
    var tooFarOut by mutableStateOf(false)
        private set

    /** Angetipptes Fahrzeug für die Infokarte. */
    data class Selection(
        val tripId: String,
        val label: String,
        val delayMin: Int,
        val realtime: Boolean,
        val standing: Boolean,
        val fromName: String,
        val toName: String,
        val arrival: Instant?,
        val own: Boolean,
        val destination: String? = null,
        val operator: String? = null,
    )

    fun start() {
        mapView.getMapAsync { m ->
            map = m
            // Drehen braucht eine Pendelkarte nicht; so bleibt auch der Kompass weg.
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            if (preview) m.uiSettings.setAllGesturesEnabled(false)
            m.setStyle(BuildConfig.MAP_STYLE_URL) { style ->
                addLayers(style)
                applyFilters()
                // Erst rahmen, wenn die Karte ihre Größe kennt (in der Liste kann das später sein).
                mapView.doOnLayout { frame(m) }
                if (!preview) {
                    m.addOnMapClickListener { point -> onMapTap(m, point) }
                    m.addOnCameraIdleListener { refetchIfMovedOut(m) }
                }
                startUpdates()
                scope.launch {
                    safely("Laufweg laden") { loadOwnRoutes() }
                }
            }
        }
    }

    fun toggleOnlyMine() {
        onlyMine = !onlyMine
        store.mapOnlyMine = onlyMine
        applyFilters()
        if (onlyMine && selected?.own == false) select(null)
    }

    // ------------------------------------------------------------------ Ebenen

    private fun addLayers(style: Style) {
        // Laufwege: eigene Linien in Navy, das angetippte Fahrzeug in Gold, unter allem anderen.
        style.addSource(GeoJsonSource(SRC_ROUTE, EMPTY))
        style.addSource(GeoJsonSource(SRC_ROUTE_STOPS, EMPTY))
        style.addSource(GeoJsonSource(SRC_SEL_ROUTE, EMPTY))
        style.addSource(GeoJsonSource(SRC_SEL_STOPS, EMPTY))
        style.addLayer(routeLine(LAYER_ROUTE, SRC_ROUTE, "#1A1A2E", 4f))
        style.addLayer(routeLine(LAYER_SEL_ROUTE, SRC_SEL_ROUTE, "#C9AC6B", 5f))
        style.addLayer(stopCircles(LAYER_ROUTE_STOPS, SRC_ROUTE_STOPS, "#1A1A2E"))
        style.addLayer(stopCircles(LAYER_SEL_STOPS, SRC_SEL_STOPS, "#A68843"))
        if (!preview) {
            style.addLayer(stopNames(LAYER_SEL_NAMES, SRC_SEL_STOPS, 9f))
            style.addLayer(stopNames(LAYER_ROUTE_NAMES, SRC_ROUTE_STOPS, 10f))
        }

        style.addSource(GeoJsonSource(SRC_LINES, EMPTY))
        style.addSource(GeoJsonSource(SRC_TRAINS, EMPTY))
        style.addLayer(
            LineLayer(LAYER_LINES, SRC_LINES).withProperties(
                PropertyFactory.lineColor(byRank("#B7C2CC", "#35556F", "#1A1A2E")),
                PropertyFactory.lineWidth(byRank(1.2f, 2.5f, 4f)),
                PropertyFactory.lineSortKey(Expression.get("rank")),
            )
        )
        // Ring um das angetippte Fahrzeug.
        style.addLayer(
            CircleLayer(LAYER_SELECTED, SRC_TRAINS).withProperties(
                PropertyFactory.circleRadius(byRank(11f, 13f, 16f)),
                PropertyFactory.circleColor("#C9AC6B"),
                PropertyFactory.circleOpacity(0.9f),
            ).withFilter(Expression.eq(Expression.get("trip"), Expression.literal("")))
        )
        style.addLayer(
            CircleLayer(LAYER_TRAINS, SRC_TRAINS).withProperties(
                PropertyFactory.circleRadius(byRank(5f, 7f, 9.5f)),
                PropertyFactory.circleSortKey(Expression.get("rank")),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(byRank(1.5f, 2f, 2.5f)),
            )
        )
        // Beschriftung: Bus und Tram erst beim Hineinzoomen, andere Züge wo Platz ist, eigene Linien immer.
        if (!preview) {
            style.addLayer(labelLayer(LAYER_LABELS_LOCAL).apply { minZoom = 11.5f })
            style.addLayer(labelLayer(LAYER_LABELS))
        }
        style.addLayer(
            SymbolLayer(LAYER_LABELS_OWN, SRC_TRAINS).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(13.5f),
                PropertyFactory.textOffset(arrayOf(0f, 1.35f)),
                PropertyFactory.textColor("#1A1A2E"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ).withFilter(Expression.eq(Expression.get("rank"), Expression.literal(RANK_OWN)))
        )
    }

    private fun routeLine(id: String, source: String, color: String, width: Float): Layer =
        LineLayer(id, source).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap("round"),
            PropertyFactory.lineJoin("round"),
        )

    private fun stopCircles(id: String, source: String, stroke: String): Layer =
        CircleLayer(id, source).withProperties(
            PropertyFactory.circleRadius(4f),
            PropertyFactory.circleColor("#FFFFFF"),
            PropertyFactory.circleStrokeColor(stroke),
            PropertyFactory.circleStrokeWidth(2f),
        )

    // Schriften aus dem OpenFreeMap-Stil; eine fehlende Schrift blockiert sonst die ganze Ebene.
    private fun stopNames(id: String, source: String, minZoom: Float): Layer =
        SymbolLayer(id, source).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textAnchor("left"),
            PropertyFactory.textOffset(arrayOf(0.8f, 0f)),
            PropertyFactory.textColor("#35556F"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(false),
        ).apply { this.minZoom = minZoom }

    private fun labelLayer(id: String): SymbolLayer =
        SymbolLayer(id, SRC_TRAINS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(byRank(10.5f, 12f, 12f)),
            PropertyFactory.textOffset(arrayOf(0f, 1.25f)),
            PropertyFactory.textColor("#1A1A2E"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(false),
        )

    /** "Nur meine Linien" auf Strecken, Punkte und Beschriftung anwenden; die Vorschau zeigt nur sie. */
    private fun applyFilters() {
        val style = map?.style ?: return
        val minRank = when {
            preview && myLines.isNotEmpty() -> RANK_OWN
            preview -> RANK_RAIL
            onlyMine -> RANK_OWN
            else -> RANK_LOCAL
        }
        val visible = Expression.gte(Expression.get("rank"), Expression.literal(minRank))
        (style.getLayer(LAYER_LINES) as? LineLayer)?.setFilter(visible)
        (style.getLayer(LAYER_TRAINS) as? CircleLayer)?.setFilter(visible)
        (style.getLayer(LAYER_LABELS) as? SymbolLayer)?.setFilter(
            Expression.all(visible, Expression.eq(Expression.get("rank"), Expression.literal(RANK_RAIL)))
        )
        (style.getLayer(LAYER_LABELS_LOCAL) as? SymbolLayer)?.setFilter(
            Expression.all(visible, Expression.eq(Expression.get("rank"), Expression.literal(RANK_LOCAL)))
        )
    }

    /** Eigene Linien groß und immer beschriftet, andere Züge mittel, Busse und Straßenbahnen klein. */
    private fun rank(mode: String, label: String): Int = when {
        norm(cleanLabel(label)) in myLines -> RANK_OWN
        mode in LOCAL_MODES -> RANK_LOCAL
        else -> RANK_RAIL
    }

    private fun byRank(local: Float, rail: Float, own: Float): Expression = Expression.step(
        Expression.get("rank"), Expression.literal(local),
        Expression.stop(RANK_RAIL, rail), Expression.stop(RANK_OWN, own),
    )

    private fun byRank(local: String, rail: String, own: String): Expression = Expression.step(
        Expression.get("rank"), Expression.color(android.graphics.Color.parseColor(local)),
        Expression.stop(RANK_RAIL, Expression.color(android.graphics.Color.parseColor(rail))),
        Expression.stop(RANK_OWN, Expression.color(android.graphics.Color.parseColor(own))),
    )

    /** Ausschnitt so wählen, dass alle eingerichteten Fahrten zu sehen sind. */
    private fun frame(m: MapLibreMap) {
        val points = profiles.filter { it.isComplete }.flatMap { it.trips }
            .flatMap { listOf(LatLng(it.fromLat, it.fromLon), LatLng(it.toLat, it.toLon)) }
        if (points.size < 2) return
        // Ränder in dp: oben Platz für die Legende (Vollbild) bzw. die Live-Plakette, unten für das Kartenlogo.
        val d = context.resources.displayMetrics.density
        val (side, top, bottom) = if (preview) Triple(40, 48, 44) else Triple(40, 200, 64)
        m.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder().includes(points).build(),
                (side * d).toInt(), (top * d).toInt(), (side * d).toInt(), (bottom * d).toInt(),
            )
        )
    }

    // ------------------------------------------------------------------ Laufwege

    /**
     * Holt für jede eingerichtete Fahrt einmal den ganzen Laufweg ihres Zugs (Geometrie und Halte):
     * den eigenen Zug laut Fahrplan, gleich welche Linie der Nutzer fährt.
     */
    private suspend fun loadOwnRoutes() {
        val key = profiles.filter { it.isComplete }.flatMap { it.trips }.joinToString("|") { "${it.fromStopId}>${it.toStopId}:${it.line}" }
        routeCache[key]?.let { showRoute(it, SRC_ROUTE, SRC_ROUTE_STOPS); return }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val seen = mutableSetOf<String>()
        val legs = mutableListOf<Leg>()
        for (p in profiles.filter { it.isComplete }) {
            val day = CommuteRunner.serviceDate(p, now)
            // Jede Teilfahrt (bei Umstiegen mehrere); Hin- und Rückfahrt auf derselben Linie teilen sich den Laufweg.
            for (leg in p.trips) {
                if (!seen.add(norm(leg.line).ifEmpty { "${leg.fromStopId}>${leg.toStopId}" })) continue
                val time = runCatching { java.time.LocalTime.parse(leg.departure) }.getOrDefault(p.departureTime)
                val from = day.atTime(time).atZone(zone).toInstant().minus(Duration.ofMinutes(30))
                val trip = CommuteRunner.connections(context, leg.fromStopId, leg.toStopId, from).connections
                    .firstOrNull { CommuteChecker.matchesLine(it, leg.line) } ?: continue
                legs += api.trip(trip.tripId).legs
            }
        }
        if (legs.isNotEmpty()) routeCache[key] = legs
        showRoute(legs, SRC_ROUTE, SRC_ROUTE_STOPS)
    }

    private fun showRoute(legs: List<Leg>, lineSource: String, stopSource: String) {
        val style = map?.style ?: return
        val lines = legs.mapNotNull { leg -> leg.legGeometry?.let { Polyline.decode(it.points, it.precision) } }
        val stops = LinkedHashMap<String, Place>()
        legs.forEach { leg -> (listOf(leg.from) + leg.intermediateStops + listOf(leg.to)).forEach { stops.putIfAbsent(it.name, it) } }
        style.getSourceAs<GeoJsonSource>(lineSource)?.setGeoJson(lineFeatures(lines))
        style.getSourceAs<GeoJsonSource>(stopSource)?.setGeoJson(buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                stops.values.forEach { st ->
                    add(buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("geometry") {
                            put("type", "Point")
                            putJsonArray("coordinates") { add(st.lon); add(st.lat) }
                        }
                        putJsonObject("properties") { put("name", st.name) }
                    })
                }
            }
        }.toString())
    }

    private fun lineFeatures(lines: List<List<LatLon>>): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            lines.forEach { pts ->
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        put("coordinates", JsonArray(pts.map { buildJsonArray { add(JsonPrimitive(it.lon)); add(JsonPrimitive(it.lat)) } }))
                    }
                    putJsonObject("properties") {}
                })
            }
        }
    }.toString()

    // ------------------------------------------------------------------ Antippen

    private fun onMapTap(m: MapLibreMap, point: LatLng): Boolean {
        val p = m.projection.toScreenLocation(point)
        val r = 22 * context.resources.displayMetrics.density
        val hit = m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), LAYER_TRAINS)
            .minByOrNull { f ->
                val g = f.geometry() as? org.maplibre.geojson.Point
                if (g == null) Float.MAX_VALUE else {
                    val s = m.projection.toScreenLocation(LatLng(g.latitude(), g.longitude()))
                    (s.x - p.x) * (s.x - p.x) + (s.y - p.y) * (s.y - p.y)
                }
            }
        select(hit?.getStringProperty("trip"))
        return hit != null
    }

    fun select(tripId: String?) {
        selectionJob?.cancel()
        (map?.style?.getLayer(LAYER_SELECTED) as? CircleLayer)
            ?.setFilter(Expression.eq(Expression.get("trip"), Expression.literal(tripId ?: "")))
        showRoute(emptyList(), SRC_SEL_ROUTE, SRC_SEL_STOPS)
        if (tripId == null) {
            selected = null
            return
        }
        selected = describe(tripId) ?: return
        // Ziel, Betreiber und ganzer Laufweg kommen aus der Fahrt selbst: eine Abfrage nur beim Antippen.
        selectionJob = scope.launch {
            try {
                val legs = api.trip(tripId).legs
                val leg = legs.firstOrNull() ?: return@launch
                val dest = leg.headsign?.takeIf { it.isNotBlank() } ?: legs.last().to.name
                selected = selected?.takeIf { it.tripId == tripId }?.copy(destination = dest, operator = leg.agencyName)
                showRoute(legs, SRC_SEL_ROUTE, SRC_SEL_STOPS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Fahrt laden fehlgeschlagen", e)
            }
        }
    }

    /** Aktueller Abschnitt eines Fahrzeugs: fährt gerade zwischen zwei Halten oder steht an einem. */
    private fun describe(tripId: String, now: Instant = Instant.now()): Selection? {
        val segs = prepared.filter { it.tripId == tripId }.sortedBy { it.dep }
        if (segs.isEmpty()) return null
        val running = segs.firstOrNull { !now.isBefore(it.dep) && now.isBefore(it.arr) }
        val seg = running ?: segs.firstOrNull { now.isBefore(it.dep) } ?: segs.last()
        val previous = selected?.takeIf { it.tripId == tripId }
        return Selection(
            tripId = tripId,
            label = cleanLabel(seg.label),
            delayMin = seg.delayMin,
            realtime = seg.segment.realTime,
            standing = running == null,
            fromName = seg.segment.from.name,
            toName = seg.segment.to.name,
            arrival = if (running != null) seg.arr else seg.dep,
            own = norm(cleanLabel(seg.label)) in myLines,
            destination = previous?.destination,
            operator = previous?.operator,
        )
    }

    // ------------------------------------------------------------------ Daten

    private fun startUpdates() {
        // Abruf einmal pro Minute; nach Fehlern länger warten (bis 8 Minuten), um Transitous zu schonen.
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var pause = FETCH_INTERVAL_MS
                while (isActive) {
                    val ok = safely("Karte laden") { fetch() }
                    pause = if (ok) FETCH_INTERVAL_MS else (pause * 2).coerceAtMost(MAX_BACKOFF_MS)
                    delay(pause)
                }
            }
        }
        // Bewegung jede Sekunde, rein lokal; gerechnet abseits des Haupt-Threads.
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val now = Instant.now()
                    val current = prepared
                    val (geoJson, own) = withContext(Dispatchers.Default) { trainsGeoJson(TrainPositions.at(now, current)) }
                    ownMoving = own
                    map?.style?.getSourceAs<GeoJsonSource>(SRC_TRAINS)?.setGeoJson(geoJson)
                    selected?.let { s -> describe(s.tripId, now)?.let { selected = it } }
                    delay(1_000)
                }
            }
        }
    }

    /** Nach dem Verschieben neu laden, wenn der sichtbare Ausschnitt aus dem geladenen herausragt. */
    private fun refetchIfMovedOut(m: MapLibreMap) {
        val b = m.projection.visibleRegion.latLngBounds
        val box = fetchedBox
        val inside = box != null && b.latitudeSouth >= box[0] && b.longitudeWest >= box[1] &&
            b.latitudeNorth <= box[2] && b.longitudeEast <= box[3]
        if (inside || System.currentTimeMillis() - lastFetch < 5_000) return
        scope.launch { safely("Karte laden") { fetch() } }
    }

    private suspend fun fetch() {
        val m = map ?: return
        // Ganz Deutschland jede Minute wäre eine riesige Antwort und unnötige Last: erst ab Zoom 9 laden.
        tooFarOut = m.cameraPosition.zoom < MIN_FETCH_ZOOM
        if (tooFarOut) {
            prepared = emptyList()
            fetchedBox = null
            m.style?.getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(EMPTY)
            return
        }
        val b = m.projection.visibleRegion.latLngBounds
        // Etwas mehr als sichtbar laden, damit kleines Verschieben keine neue Abfrage braucht.
        val padLat = (b.latitudeNorth - b.latitudeSouth) * 0.25
        val padLon = (b.longitudeEast - b.longitudeWest) * 0.25
        val box = doubleArrayOf(
            b.latitudeSouth - padLat, b.longitudeWest - padLon,
            b.latitudeNorth + padLat, b.longitudeEast + padLon,
        )
        lastFetch = System.currentTimeMillis()
        val now = Instant.now()
        val segments = api.mapTrips(
            box[0], box[1], box[2], box[3],
            zoom = m.cameraPosition.zoom.coerceAtLeast(11.0),
            start = now.minus(Duration.ofMinutes(2)),
            end = now.plus(Duration.ofMinutes(15)),
        )
        val ready = withContext(Dispatchers.Default) { TrainPositions.prepare(segments) }
        prepared = ready
        fetchedBox = box
        val lines = withContext(Dispatchers.Default) { segmentsGeoJson(ready) }
        m.style?.getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(lines)
    }

    /** Fahrzeuge als GeoJSON und wie viele davon zu den eigenen Linien gehören. */
    private fun trainsGeoJson(trains: List<TrainPosition>): Pair<String, Int> {
        var own = 0
        val fc = buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                trains.forEach { t ->
                    val label = cleanLabel(t.label)
                    val rank = rank(t.mode, t.label)
                    if (rank == RANK_OWN) own++
                    add(buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("geometry") {
                            put("type", "Point")
                            putJsonArray("coordinates") { add(t.position.lon); add(t.position.lat) }
                        }
                        putJsonObject("properties") {
                            put("trip", t.tripId)
                            put("label", if (t.delayMin > 0) "$label +${t.delayMin}" else label)
                            put("color", colorFor(t))
                            put("rank", rank)
                        }
                    })
                }
            }
        }
        return fc.toString() to own
    }

    private fun segmentsGeoJson(segments: List<TrainPositions.Prepared>): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            segments.forEach { s ->
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        put("coordinates", JsonArray(s.points.map { p ->
                            buildJsonArray { add(JsonPrimitive(p.lon)); add(JsonPrimitive(p.lat)) }
                        }))
                    }
                    putJsonObject("properties") { put("rank", rank(s.segment.mode, s.label)) }
                })
            }
        }
    }.toString()

    /** Netzfehler protokollieren, ohne die Karte zu beenden; Abbrechen (Karte verlassen) durchreichen. */
    /** Netzfehler protokollieren, ohne die Karte zu beenden; Abbrechen (Karte verlassen) durchreichen. */
    private suspend fun safely(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what fehlgeschlagen", e)
        false
    }

    private fun colorFor(t: TrainPosition): String = when {
        !t.realtime -> COLOR_PLAN
        t.delayMin >= 5 -> COLOR_LATE
        t.delayMin >= 2 -> COLOR_SLIGHT
        else -> COLOR_ON_TIME
    }

    companion object {
        private const val TAG = "Pendelblick"
        private const val FETCH_INTERVAL_MS = 60_000L
        private const val MAX_BACKOFF_MS = 8 * 60_000L
        private const val MIN_FETCH_ZOOM = 9.0

        /** Laufwege der eigenen Linien, je Strecken-Schlüssel, solange die App läuft. */
        private val routeCache = java.util.concurrent.ConcurrentHashMap<String, List<Leg>>()
        private const val SRC_LINES = "strecken"
        private const val SRC_TRAINS = "zuege"
        private const val SRC_ROUTE = "laufweg"
        private const val SRC_ROUTE_STOPS = "laufweg-halte"
        private const val SRC_SEL_ROUTE = "auswahl-laufweg"
        private const val SRC_SEL_STOPS = "auswahl-halte"
        private const val LAYER_ROUTE = "laufweg-linie"
        private const val LAYER_ROUTE_STOPS = "laufweg-halt"
        private const val LAYER_ROUTE_NAMES = "laufweg-name"
        private const val LAYER_SEL_ROUTE = "auswahl-linie"
        private const val LAYER_SEL_STOPS = "auswahl-halt"
        private const val LAYER_SEL_NAMES = "auswahl-name"
        private const val LAYER_LINES = "strecken-linie"
        private const val LAYER_SELECTED = "zuege-auswahl"
        private const val LAYER_TRAINS = "zuege-punkt"
        private const val LAYER_LABELS = "zuege-text"
        private const val LAYER_LABELS_OWN = "zuege-text-eigene"
        private const val LAYER_LABELS_LOCAL = "zuege-text-nahverkehr"
        // Gleiche Zustandsfarben wie in der App, auf der hellen Karte gut zu sehen.
        const val COLOR_ON_TIME = "#15803D"
        const val COLOR_SLIGHT = "#C28A00"
        const val COLOR_LATE = "#A51D24"
        const val COLOR_PLAN = "#6B7280"
        private const val RANK_LOCAL = 0
        private const val RANK_RAIL = 1
        private const val RANK_OWN = 2
        private val LOCAL_MODES = setOf("BUS", "TRAM", "COACH", "FERRY", "CABLE_CAR", "FUNICULAR", "AERIAL_LIFT", "ODM")
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        /** "RB60 (76519)" -> "RB60": die Zugnummer in Klammern braucht auf der Karte niemand. */
        fun cleanLabel(label: String): String = label.substringBefore(" (").trim()

        fun norm(s: String): String = s.filterNot(Char::isWhitespace).lowercase()
    }
}
