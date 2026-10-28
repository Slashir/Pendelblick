package app.pendelwecker.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pendelwecker.domain.CommuteProfile
import app.pendelwecker.ui.AppIcons
import app.pendelwecker.ui.MapPreviewCard

/** Variante A: TRAVIC lässt sich nicht als kleine Vorschau einbetten, deshalb nur ein Bild als Einstieg. */
@Composable
fun MapPreview(profiles: List<CommuteProfile>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    MapPreviewCard(
        lines = profiles.filter { it.isComplete }.flatMap { it.lines }.map { it.trim() }.distinct(),
        moving = 0,
        onOpen = onOpen,
        modifier = modifier,
    ) {
        Box(
            Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
    }
}
