package app.pendelwecker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pendelwecker.BuildConfig
import java.time.Year

/** Urheber, wie er in der App steht. */
const val COPYRIGHT_HOLDER = "Slashir"

fun copyrightLine(): String = "© ${Year.now().value} $COPYRIGHT_HOLDER"

private val BandText = Color.White
private val BandMuted = Color(0xFFB9BFCB)

/**
 * Kopfband in Navy (hell wie dunkel) mit Goldlinie und Wortmarke "Pendel**wecker**",
 * das Familienmerkmal der übrigen Oberflächen. Mit [onBack] zeigt es statt der Marke einen Titel.
 */
@Composable
fun BrandBar(
    title: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.background(Navy)) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = if (onBack == null) 20.dp else 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = BandText)) {
                    Icon(AppIcons.Back, contentDescription = "Zurück")
                }
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (title == null) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = BandText)) { append("Pendel") }
                            withStyle(SpanStyle(color = Gold)) { append("blick") }
                        },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            letterSpacing = 0.4.sp,
                        ),
                    )
                } else {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = BandText, maxLines = 1)
                }
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BandMuted, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides BandText) { actions() }
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(Gold))
    }
}

/** Symbolknopf für das Kopfband. */
@Composable
fun BandAction(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = BandText, disabledContentColor = BandMuted),
    ) { Icon(icon, contentDescription = description) }
}

/** Karte im App-Stil: flach, große Rundung. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = MaterialTheme.colorScheme.onSurface)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors, content = inner)
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = colors, content = inner)
    }
}

/** Gestrichelte Karte zum Einrichten von etwas Neuem. */
@Composable
fun DashedAddCard(text: String, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AppIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Linienbezeichnung als Plakette ("S8", "RE 1"). */
@Composable
fun LineBadge(line: String, modifier: Modifier = Modifier) {
    // Im Dunkeln hell mit Navy-Schrift, sonst verschwindet das Schild auf den dunklen Karten.
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (dark) Color(0xFFE3E6EC) else Navy,
        contentColor = if (dark) Navy else Color.White,
    ) {
        Text(
            line.ifBlank { "jede Linie" },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Linien einer Verbindung mit Umstieg als Kette: [Bus 21] › [S8] › [Tram 7]. */
@Composable
fun LineChain(lines: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEachIndexed { i, l ->
            if (i > 0) Icon(AppIcons.Chevron, contentDescription = "dann", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            LineBadge(l)
        }
    }
}

/** Kleiner Farbpunkt für den Zustand einer Fahrt. */
@Composable
fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Überschrift über einer Gruppe von Karten. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** Hinweis mit Symbol und optionaler Aktion (Berechtigungen, Fehler). */
@Composable
fun NoticeCard(
    icon: ImageVector,
    text: String,
    action: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onAction: () -> Unit = {},
) {
    AppCard(containerColor = MaterialTheme.colorScheme.surfaceContainer, padding = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).padding(top = 2.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
                }
            }
        }
    }
}

/** Die eine Hauptaktion eines Bildschirms: Gold mit Navy-Schrift. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondary,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/** Zweitrangige Aktion neben der Hauptaktion. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

/** Quellenhinweis (Pflicht laut Transitous-Richtlinie), Urheber und freiwillige Unterstützung. */
@Composable
fun Footer(modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Fahrplan- und Echtzeitdaten: Transitous, Datengrundlage u. a. DELFI e. V. " +
                "Karten: © OpenStreetMap-Mitwirkende, OpenFreeMap.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        // Pflicht laut Transitous: sichtbarer Link auf die Liste aller Datenquellen.
        TextButton(onClick = { uri.openUri("https://transitous.org/sources/") }) { Text("Alle Datenquellen") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${copyrightLine()} · Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (BuildConfig.DONATE_URL.isNotBlank()) {
                TextButton(onClick = { uri.openUri(BuildConfig.DONATE_URL) }) { Text("Auf Ko-fi unterstützen") }
            }
        }
    }
}

/**
 * Einstieg in die Live-Karte auf der Startseite: die Karte selbst (je Variante) mit "Live"-Plakette,
 * darunter, was gerade unterwegs ist. Ein Tipp öffnet die ganze Karte.
 */
@Composable
fun MapPreviewCard(
    lines: List<String>,
    moving: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    map: @Composable () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Box {
            map()
            LivePill(Modifier.align(Alignment.TopStart).padding(12.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(AppIcons.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Live-Karte: Wo ist dein Zug?", style = MaterialTheme.typography.titleMedium)
                val names = lines.joinToString(", ")
                Text(
                    when {
                        moving > 0 && lines.isNotEmpty() -> "Gerade $moving × $names unterwegs. Antippen für alle Züge und Busse."
                        lines.isNotEmpty() -> "$names und alle anderen Züge und Busse, jede Sekunde aktuell."
                        else -> "Alle Züge und Busse in deiner Nähe, jede Sekunde aktuell."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(AppIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Kleine Plakette "LIVE" mit pulsierendem Punkt. */
@Composable
private fun LivePill(modifier: Modifier = Modifier) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable<Float>(
            androidx.compose.animation.core.tween<Float>(900),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "punkt",
    )
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = Navy, contentColor = Color.White, shadowElevation = 2.dp) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4ADE80).copy(alpha = alpha)))
            Text("LIVE", style = MaterialTheme.typography.labelMedium)
        }
    }
}
