// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ModelBadge(val label: String, val bg: Color, val fg: Color) {
    DEFAULT("Default", Color(0xFF374151), Color(0xFFE5E7EB)),
    FREE("Free", Color(0xFF065F46), Color(0xFFD1FAE5)),
    CHEAP("Cheap", Color(0xFF14532D), Color(0xFFDCFCE7)),
    MEDIUM("Medium", Color(0xFF713F12), Color(0xFFFEF3C7)),
    EXPENSIVE("Expensive", Color(0xFF7F1D1D), Color(0xFFFEE2E2)),
    ZDR("ZDR", Color(0xFF1E3A8A), Color(0xFFDBEAFE)),
    CACHE("Cache", Color(0xFF581C87), Color(0xFFEDE9FE)),
}

@Composable
fun Pill(badge: ModelBadge, modifier: Modifier = Modifier) {
    Text(
        text = badge.label,
        color = badge.fg,
        fontSize = 10.sp,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(badge.bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun PillRow(badges: List<ModelBadge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        badges.forEach { Pill(it) }
    }
}
