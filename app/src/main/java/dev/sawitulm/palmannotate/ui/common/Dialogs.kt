package dev.sawitulm.palmannotate.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.data.storage.InputCache
import dev.sawitulm.palmannotate.data.storage.RunSummary
import dev.sawitulm.palmannotate.domain.model.AnnotationClass
import dev.sawitulm.palmannotate.domain.model.CaptureSetPolicy
import dev.sawitulm.palmannotate.domain.model.DatasetType
import dev.sawitulm.palmannotate.domain.quality.QualityCheck
import dev.sawitulm.palmannotate.domain.usecase.SessionUseCases.MismatchCluster
import dev.sawitulm.palmannotate.ui.theme.PalmColors

/**
 * Dialog for starting a new SESSION (a capture run locked to variety+block).
 * Trees are added later from the session detail. Mirrors the JS Start-Session view.
 *
 * [existingRuns] and [groupKeyOf] exist so the name preview can tell the truth. C-01 folds a
 * repeated variety+block into the run that already exists, and that run's naming token and
 * sequence — not this dialog's inputs — decide the filename. Previewing an unconditional
 * `…_TOKEN_0001` while the app was about to write `DAMIMAS_A21B_0043` is worse than showing no
 * preview at all, because the operator would believe the collision protection was applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (
        variety: String,
        block: String,
        sideCount: Int,
        autoId: Boolean,
        operatorName: String,
        useNameToken: Boolean,
    ) -> Unit,
    inputCache: InputCache? = null,
    existingRuns: List<RunSummary> = emptyList(),
    groupKeyOf: (variety: String, block: String) -> String = { _, _ -> "" },
    allowedSideCounts: List<Int> = listOf(4, 8),
    photoCountDescription: String? = null,
    datasetType: DatasetType = DatasetType.MULTISIDE,
) {
    var variety by remember { mutableStateOf(inputCache?.lastVariety ?: "DAMIMAS") }
    var block by remember { mutableStateOf(inputCache?.lastBlock ?: "") }
    val initialSideCount = inputCache?.lastSideCount?.takeIf { it in allowedSideCounts }
        ?: allowedSideCounts.first()
    var sideCount by remember(allowedSideCounts) { mutableIntStateOf(initialSideCount) }
    var autoId by remember { mutableStateOf(inputCache?.lastAutoId ?: true) }
    var operatorName by remember { mutableStateOf(inputCache?.lastOperatorName ?: "") }
    var useNameToken by remember { mutableStateOf(inputCache?.lastUseNameToken ?: false) }
    var varietyError by remember { mutableStateOf(false) }
    var blockError by remember { mutableStateOf(false) }
    // WS-12: the device token has to be visible BEFORE the first photo, because from the first
    // commit onwards it is baked into every filename in the run and cannot be changed.
    val deviceToken = remember(inputCache) {
        runCatching { inputCache?.deviceToken }.getOrNull().orEmpty()
    }
    // The run this Start Session will actually fold into, if it already exists.
    val existingRun = remember(variety, block, existingRuns) {
        val key = groupKeyOf(variety, block)
        if (key.isBlank()) null else existingRuns.firstOrNull { it.groupKey == key }
    }
    // Same rule the repository applies, from CaptureSetPolicy, so the two cannot disagree.
    val tokenLocked = existingRun != null &&
        (existingRun.nameToken.isNotBlank() || existingRun.treeCount > 0)
    val effectiveToken = CaptureSetPolicy.resolveNameToken(
        existingToken = existingRun?.nameToken.orEmpty(),
        runHasStarted = (existingRun?.treeCount ?: 0) > 0,
        requestedToken = if (useNameToken) deviceToken else "",
    )
    val nextSeq = existingRun?.nextId ?: 1
    val namePreview = CaptureSetPolicy.treeName(variety, block, effectiveToken, nextSeq)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (datasetType == DatasetType.MULTISIDE) R.string.dialog_start_session
                    else R.string.weight_dialog_start_session,
                ),
            )
        },
        text = {
            // Scrollable: the dialog is taller than the content area on a landscape tablet once
            // the soft keyboard is up, and a clipped Column would hide the token switch and the
            // name preview with no indication that anything is below the fold.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        if (datasetType == DatasetType.MULTISIDE) R.string.dialog_session_lock_hint
                        else R.string.weight_dialog_session_lock_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = variety,
                    onValueChange = { variety = it; varietyError = false },
                    label = { Text(stringResource(R.string.dialog_variety_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_variety_placeholder)) },
                    isError = varietyError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = block,
                    onValueChange = { block = it; blockError = false },
                    label = { Text(stringResource(R.string.dialog_block_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_block_placeholder)) },
                    isError = blockError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(
                        if (datasetType == DatasetType.MULTISIDE) R.string.dialog_photos_per_tree
                        else R.string.weight_dialog_photos_per_sample,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (photoCountDescription != null) {
                    Text(
                        photoCountDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in allowedSideCounts) {
                            FilterChip(
                                selected = sideCount == n,
                                onClick = { sideCount = n },
                                label = { Text("$n") },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.dialog_auto_id), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(
                                if (datasetType == DatasetType.MULTISIDE) R.string.dialog_auto_id_hint
                                else R.string.weight_dialog_auto_id_hint,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoId, onCheckedChange = { autoId = it })
                }
                // WS-13: the operator field used to be hardcoded empty in every metadata sidecar.
                OutlinedTextField(
                    value = operatorName,
                    onValueChange = { operatorName = it },
                    label = { Text(stringResource(R.string.dialog_operator_label)) },
                    placeholder = { Text(stringResource(R.string.dialog_operator_placeholder)) },
                    supportingText = { Text(stringResource(R.string.dialog_operator_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // WS-12: opt-in, off by default so existing collections keep their exact naming.
                // Disabled once the run has started — the format is fixed by what it has already
                // written, and an enabled switch that changes nothing is a lie the operator would
                // only discover after the first save.
                if (deviceToken.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    if (datasetType == DatasetType.MULTISIDE) R.string.dialog_name_token
                                    else R.string.weight_dialog_name_token,
                                    deviceToken,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(
                                    if (datasetType == DatasetType.BUNCH_WEIGHT) {
                                        if (tokenLocked) R.string.weight_dialog_name_token_locked
                                        else R.string.weight_dialog_name_token_hint
                                    } else if (tokenLocked) R.string.dialog_name_token_locked
                                    else R.string.dialog_name_token_hint,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = if (tokenLocked) effectiveToken.isNotEmpty() else useNameToken,
                            onCheckedChange = { useNameToken = it },
                            enabled = !tokenLocked,
                        )
                    }
                }
                Text(
                    stringResource(
                        if (datasetType == DatasetType.BUNCH_WEIGHT) {
                            if (existingRun != null) R.string.weight_dialog_name_preview_next
                            else R.string.weight_dialog_name_preview
                        } else if (existingRun != null) R.string.dialog_name_preview_next
                        else R.string.dialog_name_preview,
                        namePreview,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
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
                            cache.lastOperatorName = operatorName.trim()
                            cache.lastUseNameToken = useNameToken
                        }
                        onCreate(
                            variety.trim(),
                            block.trim(),
                            sideCount,
                            autoId,
                            operatorName.trim(),
                            useNameToken,
                        )
                    }
                },
            ) { Text(stringResource(R.string.action_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(stringResource(R.string.mismatch_title)) },
        text = {
            // Scrollable so every bunch is reachable — the dialog's text slot has a bounded
            // height and clips (does not auto-scroll), so 4 tall bunch cards lost #3/#4 before.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.mismatch_body, mismatches.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                for ((i, m) in mismatches.withIndex()) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.mismatch_bunch, i + 1),
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
                                        stringResource(R.string.mismatch_side, member.first + 1),
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
                            Text(stringResource(R.string.mismatch_choose), style = MaterialTheme.typography.labelSmall)
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
            TextButton(onClick = { onResolveAll(picks.toMap()) }) { Text(stringResource(R.string.mismatch_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Quality gate modal — shown before export when QA has issues.
 * Port of JS _confirmQualityBeforeExport.
 */
@Composable
fun QualityGateModal(
    issues: List<QualityCheck.Issue>,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.quality_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.quality_issues_header), style = MaterialTheme.typography.bodyMedium)
                for (issue in issues) {
                    // Colour by severity instead of blanket red: a missing-GPS warning shouldn't
                    // look as alarming as a blocking error. Plain message, no raw code.
                    Text(
                        "· ${issue.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (issue.level) {
                            QualityCheck.Level.ERROR -> MaterialTheme.colorScheme.error
                            QualityCheck.Level.WARN -> PalmColors.Warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.quality_export_anyway_q), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.quality_export_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.quality_back_to_fix)) }
        },
    )
}
