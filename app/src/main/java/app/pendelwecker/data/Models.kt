package app.pendelwecker.data

import kotlinx.serialization.Serializable

// Teilmenge der MOTIS-API (Transitous), abgeglichen mit openapi.yaml von motis-project/motis
// (Stand 26.10.2026). Unbekannte Felder werden beim Einlesen ignoriert.

@Serializable
data class Alert(
    val headerText: String = "",
    val descriptionText: String = "",
)

@Serializable
data class Place(
    val name: String,
    val stopId: String? = null,
    /** Übergeordneter Halt (Bahnhof statt Gleis); Abfragen am Bahnhof finden auch Gleiswechsel. */
    val parentId: String? = null,
    val lat: Double,
    val lon: Double,
    val arrival: String? = null,
    val departure: String? = null,
    val scheduledArrival: String? = null,
    val scheduledDeparture: String? = null,
    val track: String? = null,
    val scheduledTrack: String? = null,
    val cancelled: Boolean = false,
    val alerts: List<Alert> = emptyList(),
)

@Serializable
data class StopTime(
    val place: Place,
    val mode: String = "",
    val realTime: Boolean = false,
    val headsign: String = "",
    val tripId: String,
    val routeShortName: String = "",
    val displayName: String = "",
    val tripShortName: String = "",
    val cancelled: Boolean = false,
    val tripCancelled: Boolean = false,
) {
    /** Linie ohne Zugnummer in Klammern ("RE50 (16522)" -> "RE50"), wie beim Einrichten gewählt. */
    val lineName: String
        get() = displayName.substringBefore(" (").trim().ifBlank { routeShortName.ifBlank { tripShortName.ifBlank { "?" } } }
}

@Serializable
data class StopTimesResponse(
    val stopTimes: List<StopTime> = emptyList(),
    val place: Place? = null,
)

@Serializable
data class Area(
    val name: String,
    val adminLevel: Double = 0.0,
)

@Serializable
data class Match(
    val type: String,
    val name: String,
    val id: String,
    val lat: Double,
    val lon: Double,
    val areas: List<Area> = emptyList(),
    val modes: List<String> = emptyList(),
) {
    /** Kurzbeschreibung für die Vorschlagsliste, z. B. "Dresden · Fernverkehr, S-Bahn, Tram". */
    val description: String
        get() {
            // Gemeinde (Verwaltungsebene 8) statt Ortsteil, sonst die genaueste vorhandene Ebene.
            val place = (areas.filter { it.adminLevel in 1.0..8.0 }.maxByOrNull { it.adminLevel } ?: areas.lastOrNull())
                ?.name?.substringBefore(" - ")
            val kinds = MODE_NAMES.filterKeys { it in modes }.values.distinct()
            return listOfNotNull(place, kinds.takeIf { it.isNotEmpty() }?.joinToString(", ")).joinToString(" · ")
        }
}

// Verkehrsmittel aus der Haltestellensuche, für die Vorschlagsliste.
private val MODE_NAMES = linkedMapOf(
    "HIGHSPEED_RAIL" to "Fernverkehr",
    "LONG_DISTANCE" to "Fernverkehr",
    "REGIONAL_FAST_RAIL" to "Regionalzug",
    "REGIONAL_RAIL" to "Regionalzug",
    "SUBURBAN" to "S-Bahn",
    "SUBWAY" to "U-Bahn",
    "TRAM" to "Tram",
    "BUS" to "Bus",
    "FERRY" to "Fähre",
)

@Serializable
data class EncodedPolyline(
    val points: String,
    val precision: Int,
    val length: Int = 0,
)

@Serializable
data class Leg(
    val mode: String,
    val from: Place,
    val to: Place,
    val startTime: String,
    val endTime: String,
    val scheduledStartTime: String,
    val scheduledEndTime: String,
    val realTime: Boolean = false,
    val tripId: String? = null,
    val displayName: String? = null,
    val headsign: String? = null,
    val agencyName: String? = null,
    val cancelled: Boolean = false,
    val intermediateStops: List<Place> = emptyList(),
    val legGeometry: EncodedPolyline? = null,
)

@Serializable
data class Itinerary(
    val legs: List<Leg> = emptyList(),
    val startTime: String = "",
    val endTime: String = "",
    val transfers: Int = 0,
    val duration: Int = 0,
)

/** Antwort des Routings (Endpunkt plan). */
@Serializable
data class PlanResponse(
    val itineraries: List<Itinerary> = emptyList(),
)

@Serializable
data class TripInfo(
    val tripId: String,
    val displayName: String? = null,
    val routeShortName: String? = null,
)

/** Ein Fahrtabschnitt zwischen zwei Halten für die Kartendarstellung (Endpunkt map/trips). */
@Serializable
data class TripSegment(
    val trips: List<TripInfo> = emptyList(),
    val routeColor: String? = null,
    val mode: String = "",
    val from: Place,
    val to: Place,
    val departure: String,
    val arrival: String,
    val scheduledDeparture: String,
    val scheduledArrival: String,
    val realTime: Boolean = false,
    val polyline: String,
)
