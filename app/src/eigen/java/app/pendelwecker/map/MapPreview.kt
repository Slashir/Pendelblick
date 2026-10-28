package app.pendelwecker.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.ui.MapPreviewCard
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Live-Vorschau für die Startseite: die eigenen Linien mit ihren Fahrzeugen in Echtzeit,
 * ohne Gesten. Ein Tipp öffnet die ganze Karte.
 */
@Composable
fun MapPreview(profiles: List<CommuteProfile>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // Neue Karte, wenn sich die Strecken ändern; die alte wird mit ihren Abfragen beendet.
    key(profiles) {
        val scope = rememberCoroutineScope()
        val mapView = remember {
            MapLibre.getInstance(context)
            // TextureView statt SurfaceView: lässt sich in der Liste runden und scrollen.
            MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
        }
        val live = remember { LiveMap(context, scope, owner.lifecycle, mapView, profiles, preview = true).also { it.start() } }

        DisposableEffect(owner, mapView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose {
                owner.lifecycle.removeObserver(observer)
                val state = owner.lifecycle.currentState
                if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }

        MapPreviewCard(
            lines = live.myLineNames,
            moving = live.ownMoving,
            onOpen = onOpen,
            modifier = modifier,
        ) {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                // Die Vorschau reagiert nicht auf Gesten; jeder Tipp öffnet die Karte.
                Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
            }
        }
    }
}
