package app.pendelwecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pendelwecker.alarm.CommuteRunner
import app.pendelwecker.data.Itinerary
import app.pendelwecker.data.Match
import app.pendelwecker.data.TransitousApi
import app.pendelwecker.domain.CommuteProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Prüfzeitpunkte zur Auswahl, Minuten vor Abfahrt. */
private val OFFSET_CHOICES = listOf(60, 45, 30, 20, 15, 10, 8, 5)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(
    initial: CommuteProfile,
    other: CommuteProfile?,
    onBack: () -> Unit,
    onSave: (CommuteProfile) -> Unit,
) {
    var edit by remember(initial) { mutableStateOf(initial) }
    var showTime by remember { mutableStateOf(false) }

    fun update(p: CommuteProfile) {
        // Andere Halte: eine gewählte Verbindung (Umstiege, Ankunftszeit) passt nicht mehr.
        val q = if (p.fromStopId != edit.fromStopId || p.toStopId != edit.toStopId) p.copy(arrival = "", legs = emptyList()) else p
        // Wer die Strecke zum ersten Mal vollständig einträgt, will auch geweckt werden.
        edit = if (!edit.isComplete && q.isComplete && !initial.isComplete) q.copy(active = true) else q
    }

    fun pick(it: Itinerary) {
        val legs = CommuteRunner.plannedLegs(it, ZoneId.systemDefault())
        if (legs.isEmpty()) return
        val first = legs.first()
        val last = legs.last()
        val q = edit.copy(
            fromStopId = first.fromStopId, fromName = first.fromName, fromLat = first.fromLat, fromLon = first.fromLon,
            toStopId = last.toStopId, toName = last.toName, toLat = last.toLat, toLon = last.toLon,
            line = first.line,
            departure = first.departure,
            arrival = last.arrival,
            legs = if (legs.size > 1) legs else emptyList(),
        )
        edit = if (!edit.isComplete && q.isComplete && !initial.isComplete) q.copy(active = true) else q
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrandBar(title = initial.label, subtitle = "Strecke, Zug und Warnung", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Strecke")
            AppCard {
                StopField("Von", edit.fromName) { m ->
                    update(edit.copy(fromStopId = m.id, fromName = m.name, fromLat = m.lat, fromLon = m.lon))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    IconButton(onClick = {
                        update(
                            edit.copy(
                                fromStopId = edit.toStopId, fromName = edit.toName, fromLat = edit.toLat, fromLon = edit.toLon,
                                toStopId = edit.fromStopId, toName = edit.fromName, toLat = edit.fromLat, toLon = edit.fromLon,
                            )
                        )
                    }) { Icon(AppIcons.Swap, contentDescription = "Von und Nach tauschen", tint = MaterialTheme.colorScheme.primary) }
                }
                StopField("Nach", edit.toName) { m ->
                    update(edit.copy(toStopId = m.id, toName = m.name, toLat = m.lat, toLon = m.lon))
                }
                if (other != null && other.fromStopId.isNotBlank() && other.toStopId.isNotBlank() &&
                    edit.fromStopId.isBlank() && edit.toStopId.isBlank()
                ) {
                    TextButton(onClick = {
                        update(
                            edit.copy(
                                fromStopId = other.toStopId, fromName = other.toName, fromLat = other.toLat, fromLon = other.toLon,
                                toStopId = other.fromStopId, toName = other.fromName, toLat = other.fromLat, toLon = other.fromLon,
                                line = other.line,
                            )
                        )
                    }) { Text("Umgekehrt zur ${other.label} übernehmen") }
                }
            }

            SectionLabel("Verbindung")
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { showTime = true }
                            .padding(vertical = 4.dp),
                    ) {
                        Text("Abfahrt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(edit.departure, style = MaterialTheme.typography.headlineMedium.merge(Tabular), color = MaterialTheme.colorScheme.primary)
                    }
                    if (!edit.hasTransfers) {
                        OutlinedTextField(
                            value = edit.line,
                            onValueChange = { edit = edit.copy(line = it.take(12), arrival = "", legs = emptyList()) },
                            label = { Text("Linie") },
                            placeholder = { Text("z. B. S8") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (edit.hasTransfers) {
                    // Gewählte Verbindung mit Umstiegen: jede Teilfahrt mit Zeit, Linie und Ziel.
                    edit.legs.forEach { leg ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(leg.departure, style = MaterialTheme.typography.bodyMedium.merge(Tabular))
                            LineBadge(leg.line)
                            Text(
                                "bis ${leg.toName}, an ${leg.arrival}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text(
                        "Geprüft wird jede Teilfahrt und jeder Umstieg, zusätzlich kurz vor jedem Umstieg.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Linie leer lassen, wenn jede Linie zwischen den beiden Halten passt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (edit.fromStopId.isNotBlank() && edit.toStopId.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    ConnectionPicker(edit, onPick = ::pick)
                }
            }

            SectionLabel("Tage")
            AppCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEachIndexed { i, name ->
                        val day = i + 1
                        val on = day in edit.weekdays
                        FilterChip(
                            selected = on,
                            onClick = { edit = edit.copy(weekdays = if (on) edit.weekdays - day else edit.weekdays + day) },
                            label = { Text(name) },
                            colors = chipColors(),
                        )
                    }
                }
            }

            SectionLabel("Warnung")
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatisch prüfen", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (edit.isComplete) "an den gewählten Tagen vor der Abfahrt" else "erst möglich, wenn Strecke und Zeit stehen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = edit.active, onCheckedChange = { edit = edit.copy(active = it) }, enabled = edit.isComplete)
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Warnen ab", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { edit = edit.copy(delayThresholdMin = (edit.delayThresholdMin - 1).coerceAtLeast(1)) },
                        enabled = edit.delayThresholdMin > 1,
                    ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text(
                        "${Fmt.minutes(edit.delayThresholdMin)} Verspätung",
                        style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { edit = edit.copy(delayThresholdMin = (edit.delayThresholdMin + 1).coerceAtMost(30)) },
                        enabled = edit.delayThresholdMin < 30,
                    ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Prüfen, Minuten vor Abfahrt", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OFFSET_CHOICES.forEach { m ->
                        val on = m in edit.checkOffsetsMin
                        FilterChip(
                            selected = on,
                            onClick = {
                                val next = if (on) edit.checkOffsetsMin - m else edit.checkOffsetsMin + m
                                if (next.isNotEmpty()) edit = edit.copy(checkOffsetsMin = next.sortedDescending())
                            },
                            label = { Text("$m") },
                            colors = chipColors(),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auch bei Pünktlichkeit melden", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "leise Bestätigung bei der letzten Prüfung",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = edit.notifyWhenOnTime, onCheckedChange = { edit = edit.copy(notifyWhenOnTime = it) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // Feste Fußleiste mit der einen Hauptaktion.
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val timeValid = runCatching { LocalTime.parse(edit.departure) }.isSuccess
                if (!edit.isComplete) {
                    Text(
                        "Noch offen: " + listOfNotNull(
                            "Von".takeIf { edit.fromStopId.isBlank() },
                            "Nach".takeIf { edit.toStopId.isBlank() },
                            "Abfahrt".takeIf { !timeValid },
                        ).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                PrimaryButton(
                    "Speichern",
                    onClick = { onSave(edit) },
                    enabled = timeValid && edit != initial,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showTime) {
        TimeDialog(edit.departure, onDismiss = { showTime = false }) {
            edit = edit.copy(departure = it, arrival = "", legs = emptyList()); showTime = false
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/** Haltestellensuche mit Vorschlägen beim Tippen. */
@Composable
private fun StopField(label: String, current: String, onPick: (Match) -> Unit) {
    var query by remember(current) { mutableStateOf(current) }
    var results by remember { mutableStateOf<List<Match>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        if (query.trim().length < 3 || query == current) {
            results = emptyList(); error = null; return@LaunchedEffect
        }
        delay(350) // erst suchen, wenn das Tippen kurz pausiert
        loading = true
        try {
            results = TransitousApi().geocodeStops(query.trim()).distinctBy { it.id }.take(6)
            error = if (results.isEmpty()) "Keine Haltestelle gefunden." else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList(); error = "Suche fehlgeschlagen. Ist das Netz da?"
        } finally {
            loading = false
        }
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(label) },
            singleLine = true,
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    query.isNotEmpty() && query != current -> IconButton(onClick = { query = current }) {
                        Icon(AppIcons.Close, contentDescription = "Zurücksetzen")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        if (results.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Column {
                    results.forEach { m ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(m); query = m.name; results = emptyList() }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (m.description.isNotBlank()) {
                                Text(
                                    m.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lädt Verbindungen zwischen den beiden Halten ab kurz vor der gewählten Zeit, direkt oder mit Umstiegen.
 * Ein Tipp übernimmt die Verbindung samt aller Teilfahrten.
 */
@Composable
private fun ConnectionPicker(profile: CommuteProfile, onPick: (Itinerary) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember(profile.fromStopId, profile.toStopId) { mutableStateOf<List<Itinerary>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val time = runCatching { LocalTime.parse(profile.departure) }.getOrDefault(LocalTime.of(6, 0))
                val day = CommuteRunner.serviceDate(profile.copy(departure = time.format(HM)), ZonedDateTime.now(zone))
                val from = day.atTime(time).atZone(zone).toInstant().minusSeconds(10 * 60)
                result = CommuteRunner.itineraries(context, profile.fromStopId, profile.toStopId, from).take(8)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Verbindungen konnten nicht geladen werden."
            } finally {
                loading = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Verbindung aus dem Fahrplan wählen",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else TextButton(onClick = ::load) { Text(if (result == null) "Laden" else "Neu laden") }
        }
        Text(
            "Direkt oder mit Umstiegen, ab kurz vor deiner Abfahrtszeit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        result?.let { list ->
            if (list.isEmpty()) Text("Keine Verbindung gefunden.", style = MaterialTheme.typography.bodyMedium)
            list.forEach { it ->
                val legs = CommuteRunner.plannedLegs(it, zone)
                if (legs.isEmpty()) return@forEach
                val selected = legs.first().departure == profile.departure && legs.map { l -> l.line } == profile.lines
                val changes = legs.size - 1
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.shapes.small,
                        )
                        .clickable { onPick(it) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${legs.first().departure} → ${legs.last().arrival}",
                            style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                        )
                        Text(
                            if (changes == 0) "direkt" else "${changes}× umsteigen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LineChain(legs.map { l -> l.line })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val t = runCatching { LocalTime.parse(current) }.getOrDefault(LocalTime.of(6, 0))
    val state = rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abfahrt") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute).format(HM)) }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
