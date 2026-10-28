package app.pendelwecker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.pendelwecker.MainActivity
import app.pendelwecker.alarm.CheckWorker
import app.pendelwecker.data.Store
import app.pendelwecker.domain.Status
import app.pendelwecker.domain.StatusSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Startbildschirm-Widget: Ampel und Kurztext je Pendelprofil. */
class CommuteWidget : GlanceAppWidget() {

    // Ein Zähler im Glance-Zustand sorgt dafür, dass das Widget nach jeder Prüfung neu zeichnet,
    // auch während eine Glance-Sitzung noch läuft (siehe [refresh]).
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version = currentState<Preferences>()[VERSION] ?: 0L
            val snapshots = remember(version) { load(context) }
            Content(snapshots)
        }
    }

    private fun load(context: Context): List<StatusSnapshot> {
        val store = Store(context)
        val active = store.profiles().filter { it.active && it.isComplete }.map { it.id }.toSet()
        return store.snapshots().filter { it.profileId in active }.sortedBy { it.profileId }
    }

    @Composable
    private fun Content(snapshots: List<StatusSnapshot>) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(SURFACE)
                .cornerRadius(18.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            // Kopf in Navy mit Wortmarke wie in der App.
            Row(
                modifier = GlanceModifier.fillMaxWidth().background(NAVY).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Pendel", style = TextStyle(color = WHITE, fontWeight = FontWeight.Bold, fontSize = 15.sp))
                Text(
                    "blick",
                    style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                    modifier = GlanceModifier.defaultWeight(),
                )
                Text(
                    "↻",
                    style = TextStyle(color = WHITE, fontSize = 18.sp),
                    modifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()).padding(horizontal = 4.dp),
                )
            }
            Spacer(GlanceModifier.fillMaxWidth().height(2.dp).background(GOLD))
            Column(modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (snapshots.isEmpty()) {
                    Text(
                        "Noch keine Prüfung. Strecke in der App einrichten.",
                        style = TextStyle(color = MUTED, fontSize = 12.sp),
                    )
                }
                snapshots.forEach { s ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("●", style = TextStyle(color = statusColor(s.status), fontSize = 14.sp))
                        Spacer(GlanceModifier.width(8.dp))
                        Column(modifier = GlanceModifier.defaultWeight()) {
                            Text(
                                headline(s),
                                style = TextStyle(color = statusColor(s.status), fontWeight = FontWeight.Bold, fontSize = 14.sp),
                            )
                            Text(
                                detail(s),
                                style = TextStyle(color = TEXT, fontSize = 12.sp),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun headline(s: StatusSnapshot): String {
        val t = s.target
        return when (s.status) {
            Status.PUENKTLICH -> if (t?.realtime == false) "${s.label}: fährt laut Plan" else "${s.label}: pünktlich"
            Status.VERSPAETET -> "${s.label}: +${t?.let { maxOf(it.delayDepMin, it.delayArrMin) } ?: "?"} min"
            Status.AUSFALL -> "${s.label}: fällt aus"
            Status.ANSCHLUSS -> "${s.label}: Anschluss gefährdet"
            Status.UNBEKANNT -> "${s.label}: nicht gefunden"
            Status.FEHLER -> "${s.label}: Prüfung fehlgeschlagen"
        }
    }

    private fun detail(s: StatusSnapshot): String {
        val t = s.target ?: return "geprüft ${time(s.checkedAtEpochSec)}"
        val alt = s.alternative
        return when {
            (s.status == Status.AUSFALL || s.status == Status.ANSCHLUSS) && alt != null ->
                "Stattdessen ${alt.line} ${time(alt.realDep)}, an ${time(alt.realArr)}"
            s.transferProblem != null -> s.transferProblem
            s.status == Status.VERSPAETET && alt != null -> "Schneller: ${alt.line} ${time(alt.realDep)}, an ${time(alt.realArr)}"
            else -> "${t.line} ${time(t.realDep)} → ${time(t.realArr)} · geprüft ${time(s.checkedAtEpochSec)}"
        }
    }

    private fun statusColor(s: Status) = when (s) {
        Status.PUENKTLICH -> ColorProvider(day = Color(0xFF15803D), night = Color(0xFF7DDB98))
        Status.VERSPAETET -> ColorProvider(day = Color(0xFF8A6500), night = Color(0xFFF0C766))
        Status.AUSFALL, Status.ANSCHLUSS -> ColorProvider(day = Color(0xFFA51D24), night = Color(0xFFF2A7A9))
        Status.UNBEKANNT, Status.FEHLER -> ColorProvider(day = Color(0xFF6B7280), night = Color(0xFFAEB3BB))
    }

    private fun time(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

    companion object {
        private val VERSION = longPreferencesKey("version")

        // Farben wie in der App (Design-System), unabhängig von den Systemfarben.
        private val NAVY = ColorProvider(day = Color(0xFF1A1A2E), night = Color(0xFF1A1A2E))
        private val GOLD = ColorProvider(day = Color(0xFFC9AC6B), night = Color(0xFFC9AC6B))
        private val WHITE = ColorProvider(day = Color.White, night = Color.White)
        private val SURFACE = ColorProvider(day = Color.White, night = Color(0xFF1B1E29))
        private val TEXT = ColorProvider(day = Color(0xFF1A1A2E), night = Color(0xFFE3E6EC))
        private val MUTED = ColorProvider(day = Color(0xFF545B66), night = Color(0xFFAEB3BB))

        /** Nach jeder Prüfung aufrufen: Zustand hochzählen und alle Widgets neu zeichnen. */
        suspend fun refresh(context: Context) {
            val widget = CommuteWidget()
            GlanceAppWidgetManager(context).getGlanceIds(CommuteWidget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { prefs -> prefs[VERSION] = System.currentTimeMillis() }
                widget.update(context, id)
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CheckWorker.enqueueRefresh(context)
    }
}

class CommuteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CommuteWidget()
}
