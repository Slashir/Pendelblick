package app.pendelwecker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pendelwecker.map.MapActivity
import app.pendelwecker.ui.EditScreen
import app.pendelwecker.ui.HomeScreen
import app.pendelwecker.ui.PendelModel
import app.pendelwecker.ui.PendelTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val model: PendelModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Das Kopfband ist in beiden Schemata Navy: helle Symbole in der Statusleiste.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent {
            PendelTheme {
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    model.reload()
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !model.notificationsAllowed) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // -1 = Startseite, sonst die Id des bearbeiteten Profils.
                var editing by rememberSaveable { mutableStateOf(-1) }
                BackHandler(enabled = editing >= 0) { editing = -1 }

                AnimatedContent(
                    targetState = editing,
                    transitionSpec = {
                        val forward = targetState >= 0
                        (slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(260)))
                            .togetherWith(slideOutHorizontally(tween(200)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
                    },
                    label = "screen",
                ) { id ->
                    val profile = model.profile(id)
                    if (id >= 0 && profile != null) {
                        EditScreen(
                            initial = profile,
                            other = model.profiles.firstOrNull { it.id != id },
                            onBack = { editing = -1 },
                            onSave = { model.save(it); editing = -1 },
                        )
                    } else {
                        HomeScreen(
                            model = model,
                            onEdit = { editing = it },
                            onMap = { openMap(it) },
                            onRequestExact = ::requestExactAlarms,
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                                ) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openNotificationSettings()
                                }
                            },
                        )
                    }
                }
            }
        }

        // Stand regelmäßig neu einlesen, solange die App sichtbar ist (Alarme prüfen im Hintergrund).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    model.reload()
                    delay(5_000)
                }
            }
        }
    }

    private fun openMap(profileId: Int) {
        startActivity(Intent(this, MapActivity::class.java).putExtra(MapActivity.EXTRA_PROFILE, profileId))
    }

    private fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private fun openNotificationSettings() {
        startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** Manche Hersteller-Androids haben die Einstellungsseite nicht: dann die App-Infos öffnen. */
    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
    }
}
