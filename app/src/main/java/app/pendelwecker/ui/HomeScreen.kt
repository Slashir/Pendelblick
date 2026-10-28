package app.pendelwecker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.domain.Status
import app.pendelwecker.domain.StatusSnapshot
import app.pendelwecker.domain.TripTimes
import app.pendelwecker.map.MapPreview

@Composable
fun HomeScreen(
    model: PendelModel,
    onEdit: (Int) -> Unit,
    onMap: (Int) -> Unit,
    onRequestExact: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val upcoming = model.upcoming()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BrandBar(subtitle = "Dein Zug im Blick") {
            if (upcoming != null) {
                BandAction(AppIcons.Map, "Live-Karte") { onMap(upcoming.profile.id) }
            }
        }
        PullToRefreshBox(
            // Der Fortschritt steht am Knopf "Jetzt prüfen"; die Geste löst die Prüfung nur aus.
            isRefreshing = false,
            onRefresh = { upcoming?.let { model.checkNow(it.profile) } ?: model.reload() },
            modifier = Modifier.weight(1f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (upcoming == null) {
                    WelcomeCard(onStart = { onEdit(model.profiles.first().id) })
                } else {
                    StatusHero(
                        upcoming = upcoming,
                        snapshot = model.snapshotFor(upcoming),
                        busy = upcoming.profile.id in model.checking,
                        onCheck = { model.checkNow(upcoming.profile) },
                    )
                    // Die Live-Karte ist ein Kern der App: gleich unter der Antwort, als laufende Vorschau.
                    MapPreview(profiles = model.profiles.toList(), onOpen = { onMap(upcoming.profile.id) })
                }

                if (!model.notificationsAllowed) {
                    NoticeCard(
                        icon = AppIcons.Warning,
                        tint = LocalStatusColors.current.warn,
                        text = "Benachrichtigungen sind aus. Ohne sie erfährst du von Ausfällen nur in der App und im Widget.",
                        action = "Erlauben",
                        onAction = onRequestNotifications,
                    )
                }
                if (!model.exactAllowed) {
                    NoticeCard(
                        icon = AppIcons.Alarm,
                        text = "Für minutengenaue Prüfungen braucht die App „Wecker und Erinnerungen“. " +
                            "Ohne diese Erlaubnis prüft Android in einem Fenster von bis zu 10 Minuten.",
                        action = "Erlauben",
                        onAction = onRequestExact,
                    )
                }

                SectionLabel("Deine Fahrten")
                model.profiles.forEach { p ->
                    if (p.fromStopId.isBlank() && p.toStopId.isBlank()) {
                        DashedAddCard("${p.label} einrichten") { onEdit(p.id) }
                    } else {
                        ProfileRow(
                            profile = p,
                            snapshot = model.snapshots[p.id],
                            onClick = { onEdit(p.id) },
                            onActive = { model.setActive(p, it) },
                        )
                    }
                }

                Footer()
            }
        }
    }
}

@Composable
private fun WelcomeCard(onStart: () -> Unit) {
    AppCard(padding = 20.dp) {
        Icon(AppIcons.Train, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(4.dp))
        Text("Nie wieder umsonst am Bahnsteig", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Trag deine Strecke und deinen Zug ein. Pendelblick prüft vor jeder Fahrt, ob er fährt, " +
                "und meldet sich nur, wenn etwas nicht stimmt – mit der nächsten Alternative. " +
                "Auf der Live-Karte siehst du jederzeit, wo dein Zug gerade ist.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Hinfahrt einrichten", onClick = onStart, modifier = Modifier.fillMaxWidth())
    }
}

/** Die Antwort zuerst: fährt mein nächster Zug? */
@Composable
private fun StatusHero(
    upcoming: Upcoming,
    snapshot: StatusSnapshot?,
    busy: Boolean,
    onCheck: () -> Unit,
) {
    val sc = LocalStatusColors.current
    val p = upcoming.profile
    val status = snapshot?.status
    val target = snapshot?.target
    // Ohne Echtzeitdaten ist "pünktlich" nur der Fahrplan: neutral zeigen, nicht grün.
    val shown = status?.takeUnless { it == Status.PUENKTLICH && target?.realtime == false }
    val card = sc.cardOf(shown, MaterialTheme.colorScheme.surfaceContainer)

    AppCard(containerColor = card, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineChain(p.lines.ifEmpty { listOf(p.line) })
            Text(
                if (p.hasTransfers) "${p.trips.size - 1}× umsteigen"
                else "Richtung " + (target?.headsign?.takeIf { it.isNotBlank() } ?: p.toName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${p.label} · ${Fmt.relativeDay(upcoming.departure.toLocalDate())}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            headline(snapshot),
            style = MaterialTheme.typography.headlineMedium,
            color = if (shown == null) MaterialTheme.colorScheme.onSurface else sc.of(shown),
        )

        TimesRow(upcoming, target)
        Row(Modifier.fillMaxWidth()) {
            Text(p.fromName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
            Spacer(Modifier.width(12.dp))
            Text(
                p.toName, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        target?.track?.let {
            Text("Gleis ${trackNumber(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (status == Status.UNBEKANNT || status == Status.FEHLER) {
            Text(snapshot.message, style = MaterialTheme.typography.bodyMedium)
        }
        if (snapshot != null && snapshot.legs.size > 1) TransferList(snapshot)
        snapshot?.alternative?.let { AlternativeBox(it, status == Status.AUSFALL || status == Status.ANSCHLUSS) }
        if (snapshot != null && snapshot.furtherCancellations > 0) {
            Text(
                if (snapshot.furtherCancellations == 1) "Danach fällt noch eine weitere Fahrt aus."
                else "Danach fallen noch ${snapshot.furtherCancellations} weitere Fahrten aus.",
                style = MaterialTheme.typography.bodyMedium,
                color = sc.bad,
            )
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            checkInfo(upcoming, snapshot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            if (busy) "Prüfe …" else "Jetzt prüfen",
            onClick = onCheck,
            busy = busy,
            icon = AppIcons.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TimesRow(upcoming: Upcoming, target: TripTimes?) {
    val sc = LocalStatusColors.current
    val big = MaterialTheme.typography.displaySmall.merge(Tabular)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TimeColumn(
            planned = target?.plannedDep?.let(Fmt::hm) ?: Fmt.hm(upcoming.departure),
            real = target?.takeIf { it.delayDepMin > 0 && !it.cancelled }?.realDep?.let(Fmt::hm),
            cancelled = target?.cancelled == true,
            style = big,
            alignEnd = false,
        )
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .height(2.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(if (target?.cancelled == true) sc.bad else MaterialTheme.colorScheme.outline),
        )
        TimeColumn(
            planned = target?.plannedArr?.let(Fmt::hm) ?: upcoming.profile.arrival.ifBlank { "–" },
            real = target?.takeIf { it.delayArrMin > 0 && !it.cancelled }?.realArr?.let(Fmt::hm),
            cancelled = target?.cancelled == true,
            style = big,
            alignEnd = true,
        )
    }
}

@Composable
private fun TimeColumn(
    planned: String,
    real: String?,
    cancelled: Boolean,
    style: androidx.compose.ui.text.TextStyle,
    alignEnd: Boolean,
) {
    val sc = LocalStatusColors.current
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        if (real != null) {
            Text(real, style = style, color = sc.warn)
            Text(
                planned,
                style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                planned,
                style = style,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Umstiege einer Verbindung: wo, wann an, wann weiter; der wackelnde rot. */
@Composable
private fun TransferList(s: StatusSnapshot) {
    val sc = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1 until s.legs.size) {
            val a = s.legs[i - 1]
            val b = s.legs[i]
            val tight = b.realDep - a.realArr < 60
            Text(
                "Umstieg ${a.toName}: an ${Fmt.hm(a.realArr)}, ${b.line} ab ${Fmt.hm(b.realDep)}",
                style = MaterialTheme.typography.bodySmall.merge(Tabular),
                color = if (tight) sc.bad else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlternativeBox(alt: TripTimes, isReplacement: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            when {
                isReplacement && alt.line.contains("→") -> "Neue Verbindung"
                isReplacement -> "Nimm stattdessen"
                else -> "Schneller am Ziel"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LineBadge(alt.line)
            Text(
                "ab ${Fmt.hm(alt.realDep)}" + if (alt.delayDepMin > 0) " (+${alt.delayDepMin})" else "",
                style = MaterialTheme.typography.titleMedium.merge(Tabular),
            )
            Text(
                "an ${Fmt.hm(alt.realArr)}",
                style = MaterialTheme.typography.titleMedium.merge(Tabular),
                fontWeight = FontWeight.Normal,
            )
        }
        alt.track?.let {
            Text("Gleis ${trackNumber(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun headline(s: StatusSnapshot?): String = when (s?.status) {
    null -> "Noch nicht geprüft"
    Status.PUENKTLICH -> if (s.target?.realtime == false) "Fährt laut Plan" else "Pünktlich"
    Status.VERSPAETET -> s.target?.let { "${maxOf(it.delayDepMin, it.delayArrMin)} min später" } ?: "Verspätet"
    Status.AUSFALL -> "Fällt aus"
    Status.ANSCHLUSS -> "Anschluss gefährdet"
    Status.UNBEKANNT -> "Zug nicht gefunden"
    Status.FEHLER -> "Prüfung fehlgeschlagen"
}

/** "Gl. 1" -> "1": die Daten bringen die Abkürzung teils schon mit. */
private fun trackNumber(t: String): String = t.removePrefix("Gl.").removePrefix("Gleis").trim()

private fun checkInfo(u: Upcoming, s: StatusSnapshot?): String {
    val parts = mutableListOf<String>()
    if (s != null) {
        parts += "Geprüft um ${Fmt.hm(s.checkedAtEpochSec)}"
        s.target?.let { parts += if (it.realtime) "mit Echtzeit" else "nur Fahrplan" }
    }
    when {
        !u.profile.active -> parts += "Automatische Prüfung ist aus"
        u.nextCheck != null -> parts += "nächste Prüfung ${Fmt.relativeDay(u.nextCheck.toLocalDate())} ${Fmt.hm(u.nextCheck)}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ProfileRow(
    profile: CommuteProfile,
    snapshot: StatusSnapshot?,
    onClick: () -> Unit,
    onActive: (Boolean) -> Unit,
) {
    val sc = LocalStatusColors.current
    AppCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.label, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineChain(profile.lines.ifEmpty { listOf(profile.line) })
                    Text(
                        "${profile.departure} · ${Fmt.weekdays(profile.weekdays)}",
                        style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    )
                }
                Text(
                    listOf(profile.fromName, profile.toName).filter { it.isNotBlank() }.joinToString(" → ")
                        .ifBlank { "Strecke fehlt noch" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snapshot != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val plain = snapshot.status == Status.PUENKTLICH && snapshot.target?.realtime == false
                        StatusDot(if (plain) sc.none else sc.of(snapshot.status), 8.dp)
                        Text(
                            "${headline(snapshot)} · ${Fmt.hm(snapshot.checkedAtEpochSec)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!profile.isComplete) {
                    Text("Noch nicht fertig eingerichtet", style = MaterialTheme.typography.bodySmall, color = sc.warn)
                }
            }
            Switch(
                checked = profile.active,
                onCheckedChange = onActive,
                enabled = profile.isComplete,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
            Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
