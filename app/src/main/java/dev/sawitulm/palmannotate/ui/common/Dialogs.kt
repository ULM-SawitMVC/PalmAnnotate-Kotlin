package dev.sawitulm.palmannotate.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sawitulm.palmannotate.data.storage.InputCache
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases.MismatchCluster

/**
 * Dialog for starting a new SESSION (a capture run locked to variety+block).
 * Trees are added later from the session detail. Mirrors the JS Start-Session view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (variety: String, block: String, sideCount: Int, autoId: Boolean) -> Unit,
    inputCache: InputCache? = null,
) {
    var variety by remember { mutableStateOf(inputCache?.lastVariety ?: "DAMIMAS") }
    var block by remember { mutableStateOf(inputCache?.lastBlock ?: "") }
    var sideCount by remember { mutableIntStateOf(inputCache?.lastSideCount ?: 4) }
    var autoId by remember { mutableStateOf(inputCache?.lastAutoId ?: true) }
    var varietyError by remember { mutableStateOf(false) }
    var blockError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Variety and Block are locked for every tree in this session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = variety,
                    onValueChange = { variety = it; varietyError = false },
                    label = { Text("Tree Variety *") },
                    placeholder = { Text("e.g. DAMIMAS") },
                    isError = varietyError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = block,
                    onValueChange = { block = it; blockError = false },
                    label = { Text("Block *") },
                    placeholder = { Text("e.g. A21B") },
                    isError = blockError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Photos per Tree", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in listOf(4, 8)) {
                        FilterChip(
                            selected = sideCount == n,
                            onClick = { sideCount = n },
                            label = { Text("$n") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto ID Mode", style = MaterialTheme.typography.bodyMedium)
                        Text("Tree ID increments (0001, 0002, …)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoId, onCheckedChange = { autoId = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    varietyError = variety.isBlank()
                    blockError = block.isBlank()
                    if (!varietyError && !blockError) {
                        inputCache?.let { cache ->
                            cache.lastVariety = variety.trim()
                            cache.lastBlock = block.trim()
                            cache.lastSideCount = sideCount
                            cache.lastAutoId = autoId
                        }
                        onCreate(variety.trim(), block.trim(), sideCount, autoId)
                    }
                },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirm delete dialog.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Mismatch resolve modal — shows clusters with inconsistent classes.
 * Lets the user pick which class to use for each mismatched bunch.
 * Port of JS #modal-mismatch from index.html.
 */
@Composable
fun MismatchResolveModal(
    mismatches: List<MismatchCluster>,
    onResolveAll: (choices: Map<String, Int>) -> Unit,
    onCancel: () -> Unit,
) {
    // Track user's class choice per mismatch (rootKey → classId)
    val picks = remember(mismatches) {
        mutableStateMapOf<String, Int>().apply {
            mismatches.forEach { put(it.rootKey, it.majorityClassId) }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Bunch Class Resolution") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${mismatches.size} bunch${if (mismatches.size > 1) "es" else ""} have inconsistent classes across sides. Choose the correct class for each bunch.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for ((i, m) in mismatches.withIndex()) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Bunch #${i + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            // Show each member's current class
                            for (member in m.members) {
                                val cls = AnnotationClass.fromId(member.third.classId)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "Side ${member.first + 1}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        cls.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (member.third.classId != (picks[m.rootKey] ?: m.majorityClassId))
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            // Class choice buttons (B1/B2/B3/B4)
                            Text("Choose final class:", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (cls in AnnotationClass.assignableEntries) {
                                    val isSelected = picks[m.rootKey] == cls.id
                                    val isObserved = cls.id in m.observedClassIds
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { picks[m.rootKey] = cls.id },
                                        label = { Text(cls.displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = cls.composeColor.copy(alpha = 0.3f),
                                            selectedLabelColor = cls.composeColor,
                                        ),
                                        border = if (isObserved && !isSelected) FilterChipDefaults.filterChipBorder(
                                            borderColor = cls.composeColor.copy(alpha = 0.5f),
                                            borderWidth = 1.dp,
                                            enabled = true,
                                            selected = false,
                                        ) else FilterChipDefaults.filterChipBorder(
                                            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                            borderWidth = 1.dp,
                                            enabled = true,
                                            selected = false,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResolveAll(picks.toMap()) }) { Text("Apply & Continue") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

/**
 * Quality gate modal — shown before export when QA has issues.
 * Port of JS _confirmQualityBeforeExport.
 */
@Composable
fun QualityGateModal(
    issues: List<String>,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Quality Check") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The following issues were found:", style = MaterialTheme.typography.bodyMedium)
                for (issue in issues) {
                    Text(
                        "· $issue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("Export anyway?", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("Export anyway") }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text("Back to fix") }
        },
    )
}
