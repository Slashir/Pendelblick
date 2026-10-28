package app.pendelwecker.map

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.pendelwecker.data.Store
import app.pendelwecker.ui.AppIcons
import app.pendelwecker.ui.BrandBar
import app.pendelwecker.ui.Fmt
import app.pendelwecker.ui.LineBadge
import app.pendelwecker.ui.LocalStatusColors
import app.pendelwecker.ui.PendelTheme
import app.pendelwecker.ui.StatusDot
import app.pendelwecker.ui.Tabular
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/** Variante B: eigene Live-Karte im Vollbild, mit Legende, Filter und Infokarte zum angetippten Fahrzeug. */
class MapActivity : ComponentActivity() {

    private lateinit var mapView: MapView
    private lateinit var live: LiveMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        val profiles = Store(this).profiles()
        val profile = profiles.firstOrNull { it.id == intent.getIntExtra(EXTRA_PROFILE, 0) }

        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        live = LiveMap(this, lifecycleScope, lifecycle, mapView, profiles, preview = false)
        live.start()

        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            PendelTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
                    BrandBar(
                        title = "Live-Karte",
                        subtitle = profile?.takeIf { it.isComplete }?.let { "${it.fromName} → ${it.toName}" },
                        onBack = ::finish,
                    )
                    // Die Karte endet über der Navigationsleiste, damit Logo und Infokarte frei bleiben.
                    Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                        Legend(Modifier.align(Alignment.TopCenter).padding(10.dp))
                        if (live.tooFarOut && live.selected == null) {
                            Surface(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shadowElevation = 3.dp,
                            ) {
                                Text(
                                    "Zum Anzeigen der Fahrzeuge hineinzoomen",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = live.selected != null,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            live.selected?.let { TrainCard(it) { live.select(null) } }
                        }
                    }
                }
            }
        }
    }

    /** Farberklärung, Umschalter "nur meine Linien" und Hinweis, dass die Positionen berechnet sind. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Legend(modifier: Modifier) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    listOf(
                        LiveMap.COLOR_ON_TIME to "pünktlich",
                        LiveMap.COLOR_SLIGHT to "ab 2 min",
                        LiveMap.COLOR_LATE to "ab 5 min",
                        LiveMap.COLOR_PLAN to "ohne Echtzeit",
                    ).forEach { (c, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            StatusDot(Color(android.graphics.Color.parseColor(c)), 9.dp)
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (live.myLineNames.isNotEmpty()) {
                    FilterChip(
                        selected = live.onlyMine,
                        onClick = live::toggleOnlyMine,
                        label = { Text("Nur meine Linien: ${live.myLineNames.joinToString(", ")}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                Text(
                    "Positionen aus Fahrplan und Verspätung berechnet, kein GPS. " +
                        "Tippe auf ein Fahrzeug für Details und seinen ganzen Laufweg.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** Infokarte zum angetippten Fahrzeug. */
    @Composable
    private fun TrainCard(s: LiveMap.Selection, onClose: () -> Unit) {
        val sc = LocalStatusColors.current
        val status = when {
            !s.realtime -> "ohne Echtzeitdaten"
            s.delayMin >= 1 -> "+${s.delayMin} min"
            s.delayMin <= -1 -> "${-s.delayMin} min früher"
            else -> "pünktlich"
        }
        val statusColor = when {
            !s.realtime -> sc.none
            s.delayMin >= 5 -> sc.bad
            s.delayMin >= 2 -> sc.warn
            else -> sc.good
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LineBadge(s.label)
                    Text(
                        s.destination?.let { "Richtung $it" } ?: "Ziel wird geladen …",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = "Schließen") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(statusColor, 9.dp)
                    Text(status, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = statusColor)
                    if (s.own) {
                        Text("· deine Linie", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val time = s.arrival?.epochSecond?.let(Fmt::hm)
                Text(
                    if (s.standing) "Hält in ${s.fromName}" + (time?.let { ", fährt $it weiter" } ?: "")
                    else "Zwischen ${s.fromName} und ${s.toName}" + (time?.let { ", dort an $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    modifier = Modifier.padding(end = 12.dp),
                )
                s.operator?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // MapView braucht die Lebenszyklus-Aufrufe der Activity.
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
