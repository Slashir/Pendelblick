package app.pendelwecker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pendelwecker.R
import app.pendelwecker.domain.Status

/*
 * Farben aus dem Design-System (Navy/Blau/Gold/Bordeaux), festes Schema ohne dynamische Systemfarben.
 * Jede Farbe hat eine Rolle: Navy = Kopfband, Blau = Text und Auswahl, Gold = die eine Hauptaktion
 * eines Bildschirms, Grün/Gelb/Bordeaux = ausschließlich der Zustand einer Fahrt.
 */

val Navy = Color(0xFF1A1A2E)
val Gold = Color(0xFFC9AC6B)
private val Blue = Color(0xFF35556F)
private val BlueLight = Color(0xFF9DBAD3)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6EF),
    onPrimaryContainer = Color(0xFF102536),
    secondary = Gold,
    onSecondary = Navy,
    secondaryContainer = Color(0xFFF0F2F6),
    onSecondaryContainer = Navy,
    background = Color(0xFFF0F2F6),
    onBackground = Navy,
    surface = Color(0xFFF0F2F6),
    onSurface = Navy,
    surfaceVariant = Color(0xFFE4E4E4),
    onSurfaceVariant = Color(0xFF545B66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAEDF2),
    surfaceContainerHighest = Color(0xFFE2E6EC),
    outline = Color(0xFFAEB3BB),
    outlineVariant = Color(0xFFE4E4E4),
    error = Color(0xFFA51D24),
    onError = Color.White,
    errorContainer = Color(0xFFFDE2E1),
    onErrorContainer = Color(0xFF5A0A0E),
)

private val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = Color(0xFF0E2333),
    primaryContainer = Color(0xFF294256),
    onPrimaryContainer = Color(0xFFDCE6EF),
    secondary = Gold,
    onSecondary = Navy,
    secondaryContainer = Color(0xFF232736),
    onSecondaryContainer = Color(0xFFE3E6EC),
    background = Color(0xFF14161F),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF14161F),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF2A2E3D),
    onSurfaceVariant = Color(0xFFAEB3BB),
    surfaceContainerLowest = Color(0xFF0F1118),
    surfaceContainerLow = Color(0xFF171A24),
    surfaceContainer = Color(0xFF1B1E29),
    surfaceContainerHigh = Color(0xFF232736),
    surfaceContainerHighest = Color(0xFF2B3040),
    outline = Color(0xFF575E6E),
    outlineVariant = Color(0xFF2B3040),
    error = Color(0xFFF2A7A9),
    onError = Color(0xFF4A070B),
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFDE2E1),
)

/** Zustandsfarben einer Fahrt, getrennt vom Material-Schema, damit sie überall gleich aussehen. */
@Immutable
data class StatusColors(
    val good: Color,
    val warn: Color,
    val bad: Color,
    val none: Color,
    /** Leicht getönte Kartenflächen; Text darauf bleibt neutral, farbig ist nur die Aussage. */
    val goodCard: Color,
    val warnCard: Color,
    val badCard: Color,
) {
    fun of(s: Status?): Color = when (s) {
        Status.PUENKTLICH -> good
        Status.VERSPAETET -> warn
        Status.AUSFALL, Status.ANSCHLUSS -> bad
        else -> none
    }

    fun cardOf(s: Status?, fallback: Color): Color = when (s) {
        Status.PUENKTLICH -> goodCard
        Status.VERSPAETET -> warnCard
        Status.AUSFALL, Status.ANSCHLUSS -> badCard
        else -> fallback
    }
}

private val LightStatus = StatusColors(
    good = Color(0xFF15803D),
    warn = Color(0xFF8A6500),
    bad = Color(0xFFA51D24),
    none = Color(0xFF6B7280),
    goodCard = Color(0xFFE6F4EA),
    warnCard = Color(0xFFFFF6DC),
    badCard = Color(0xFFFCE9E8),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF7DDB98),
    warn = Color(0xFFF0C766),
    bad = Color(0xFFF2A7A9),
    none = Color(0xFFAEB3BB),
    goodCard = Color(0xFF17251D),
    warnCard = Color(0xFF2A2415),
    badCard = Color(0xFF2E191B),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/*
 * Schrift: IBM Plex Sans wie in den übrigen Oberflächen (SIL Open Font License,
 * Lizenz unter assets/licenses/IBMPlexSans-OFL.txt).
 */
private val Plex = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_bold, FontWeight.Bold),
)

private val Base = Typography()

private fun TextStyle.plex(weight: FontWeight? = null, tracking: Double? = null) = copy(
    fontFamily = Plex,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
)

private val AppTypography = Typography(
    displayLarge = Base.displayLarge.plex(FontWeight.Bold, -1.0),
    displayMedium = Base.displayMedium.plex(FontWeight.Bold, -0.8),
    displaySmall = Base.displaySmall.plex(FontWeight.Bold, -0.6),
    headlineLarge = Base.headlineLarge.plex(FontWeight.Bold, -0.5),
    headlineMedium = Base.headlineMedium.plex(FontWeight.Bold, -0.4),
    headlineSmall = Base.headlineSmall.plex(FontWeight.Bold, -0.2),
    titleLarge = Base.titleLarge.plex(FontWeight.SemiBold),
    titleMedium = Base.titleMedium.plex(FontWeight.SemiBold),
    titleSmall = Base.titleSmall.plex(FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.plex(),
    bodyMedium = Base.bodyMedium.plex(),
    bodySmall = Base.bodySmall.plex(),
    labelLarge = Base.labelLarge.plex(FontWeight.SemiBold),
    labelMedium = Base.labelMedium.plex(FontWeight.SemiBold, 0.6),
    labelSmall = Base.labelSmall.plex(FontWeight.Medium),
)

/** Uhrzeiten mit gleich breiten Ziffern, damit nichts springt. */
val Tabular: TextStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun PendelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
