package dev.sawitulm.palmannotate.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Global app header — logo + "PalmAnnotate / Oil Palm - Offline" wordmark.
 * Mirrors the JS .header (id #header) at the top of index.html.
 * Hidden on home/detail/start views (they carry their own header).
 */
@Composable
fun AppHeader(
    showHomeButton: Boolean = false,
    onHome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "PalmAnnotate",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Oil Palm - Offline",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showHomeButton) {
                TextButton(onClick = onHome) {
                    Text("Home", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
