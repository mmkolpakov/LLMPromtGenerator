package com.promptgenerator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promptgenerator.domain.model.Response
import com.promptgenerator.domain.service.GenerationStatus
import com.promptgenerator.ui.icons.AppIcons
import java.io.File

const val RESULTS_PER_PAGE = 10

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultsView(
    results: Map<String, Response>,
    partialResults: Map<String, Response> = emptyMap(),
    isGenerating: Boolean = false,
    generationStatus: GenerationStatus = GenerationStatus.COMPLETED,
    generationProgress: Float = 0f,
    completedCount: Int = 0,
    totalCount: Int = 0,
    showPartialResults: Boolean = true,
    exportPath: String = "generated_prompts",
    onClearResults: () -> Unit,
    onCancelGeneration: () -> Unit,
    onSaveResults: () -> Unit,
    onToggleShowPartial: () -> Unit = {},
    onRetryFailedRequest: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isShowingResults = isGenerating || results.isNotEmpty() || partialResults.isNotEmpty()
    var savedToFiles by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) }
    val expandedItems = remember { mutableStateMapOf<String, Boolean>() }
    val copiedItems = remember { mutableStateMapOf<String, Boolean>() }
    val lazyListState = rememberLazyListState()

    val exportPathExists = remember(exportPath) {
        File(exportPath).exists()
    }

    val displayResults by remember(results, partialResults, showPartialResults, isGenerating) {
        derivedStateOf {
            when {
                results.isNotEmpty() -> results
                partialResults.isNotEmpty() && showPartialResults -> partialResults
                else -> emptyMap()
            }
        }
    }

    val totalPages = (displayResults.size + RESULTS_PER_PAGE - 1) / RESULTS_PER_PAGE

    LaunchedEffect(results.isNotEmpty()) {
        currentPage = 0
        expandedItems.clear()
        copiedItems.clear()
    }

    LaunchedEffect(currentPage) {
        lazyListState.scrollToItem(0)
    }

    AnimatedVisibility(
        visible = isShowingResults,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = when {
                                isGenerating -> "Generating..."
                                generationStatus == GenerationStatus.COMPLETED -> "Results"
                                generationStatus == GenerationStatus.CANCELLED -> "Cancelled"
                                generationStatus == GenerationStatus.ERROR -> "Error"
                                else -> generationStatus.name.replace("_", " ")
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (displayResults.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isGenerating) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Show partial results:",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Switch(
                                        checked = showPartialResults,
                                        onCheckedChange = { onToggleShowPartial() }
                                    )
                                }
                            }

                            CustomTooltip(
                                tooltip = {
                                    Text(
                                        text = "Save all results as text files to: $exportPath",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onSaveResults()
                                        savedToFiles = true
                                    },
                                    enabled = displayResults.isNotEmpty() && !savedToFiles
                                ) {
                                    Icon(
                                        imageVector = if (savedToFiles) AppIcons.Check else AppIcons.Save,
                                        contentDescription = "Save Results",
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (savedToFiles) "Saved" else "Save All")
                                }
                            }

                            CustomTooltip(
                                tooltip = {
                                    Text(
                                        text = "Clear all generated results",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onClearResults()
                                        savedToFiles = false
                                        currentPage = 0
                                        expandedItems.clear()
                                        copiedItems.clear()
                                    }
                                ) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                if (isGenerating) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when(generationStatus) {
                                GenerationStatus.PREPARING -> "Preparing..."
                                GenerationStatus.PROCESSING_TEMPLATE -> "Processing template..."
                                GenerationStatus.SENDING_REQUESTS -> "Sending requests..."
                                GenerationStatus.PROCESSING_RESULTS -> "Processing results..."
                                GenerationStatus.COMPLETED -> "Completed"
                                GenerationStatus.CANCELLED -> "Cancelling..."
                                GenerationStatus.ERROR -> "Error"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = when(generationStatus) {
                                GenerationStatus.CANCELLED -> MaterialTheme.colorScheme.error
                                GenerationStatus.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )

                        if (totalCount > 0) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LinearProgressIndicator(
                                    progress = { generationProgress },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )

                                Text(
                                    text = "$completedCount/$totalCount",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(0.8f)
                            )
                        }

                        Button(
                            onClick = onCancelGeneration,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Cancel")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                if (savedToFiles || displayResults.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (savedToFiles)
                                "Results saved to: $exportPath"
                            else
                                "Results will be saved to: $exportPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!exportPathExists && !savedToFiles) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(Directory will be created when saving)",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (displayResults.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${displayResults.size} results${
                                if (isGenerating) " so far"
                                else if (generationStatus == GenerationStatus.CANCELLED) " (partial)"
                                else ""
                            }",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (totalPages > 1) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = { if (currentPage > 0) currentPage-- },
                                    enabled = currentPage > 0
                                ) {
                                    Icon(
                                        imageVector = AppIcons.ExpandLess,
                                        contentDescription = "Previous Page",
                                        modifier = Modifier.padding(4.dp)
                                    )
                                }

                                Text(
                                    text = "Page ${currentPage + 1}/$totalPages",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                IconButton(
                                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                                    enabled = currentPage < totalPages - 1
                                ) {
                                    Icon(
                                        imageVector = AppIcons.ExpandMore,
                                        contentDescription = "Next Page",
                                        modifier = Modifier.padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                } else if (isGenerating) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Text(
                                text = "Generation in progress...",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                if (displayResults.isNotEmpty()) {
                    val paginatedResults = displayResults.entries
                        .drop(currentPage * RESULTS_PER_PAGE)
                        .take(RESULTS_PER_PAGE)
                        .toList()

                    LazyColumn(
                        state = lazyListState,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f, false)
                    ) {
                        itemsIndexed(
                            items = paginatedResults,
                            key = { _, (id, _) -> id }
                        ) { _, (id, response) ->
                            ResultCard(
                                id = id,
                                response = response,
                                isExpanded = expandedItems[id] == true,
                                isCopied = copiedItems[id] == true,
                                onToggleExpand = { expandedItems[id] = !(expandedItems[id] ?: false) },
                                onCopy = { copiedItems[id] = true },
                                onRetry = { if (response.error != null) onRetryFailedRequest(id) },
                                modifier = Modifier.animateItemPlacement()
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(
    id: String,
    response: Response,
    isExpanded: Boolean,
    isCopied: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val hasError = response.error != null

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(
            width = 1.dp,
            color = if (hasError) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .padding(4.dp),
                    color = if (hasError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "ID: ${id.substring(0, minOf(6, id.length))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (hasError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasError) {
                        CustomTooltip(
                            tooltip = {
                                Text(
                                    text = "Retry this request",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        ) {
                            IconButton(onClick = onRetry) {
                                Icon(
                                    imageVector = AppIcons.Refresh,
                                    contentDescription = "Retry Request",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    } else {
                        CustomTooltip(
                            tooltip = {
                                Text(
                                    text = if (isCopied) "Copied to clipboard!" else "Copy to clipboard",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        ) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(response.content))
                                    onCopy()
                                }
                            ) {
                                Icon(
                                    imageVector = AppIcons.Copy,
                                    contentDescription = "Copy to Clipboard",
                                    tint = if (isCopied) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    CustomTooltip(
                        tooltip = {
                            Text(
                                text = if (isExpanded) "Collapse" else "Show full content",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    ) {
                        IconButton(
                            onClick = onToggleExpand
                        ) {
                            Icon(
                                imageVector = if (isExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand"
                            )
                        }
                    }
                }
            }

            if (hasError) {
                val errorDescription = getErrorDescription(response.error)

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = errorDescription.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = errorDescription.second,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = response.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )

                    Button(
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = AppIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("Retry Request")
                    }
                }
            } else {
                val displayText = if (!isExpanded && response.content.length > 200) {
                    response.content.substring(0, 200) + "..."
                } else {
                    response.content
                }

                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (isExpanded) FontFamily.Monospace else FontFamily.Default,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis
                )

                if (!isExpanded && response.content.length > 200) {
                    TextButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Show More")
                    }
                }
            }
        }
    }
}

private fun getErrorDescription(error: String?): Pair<String, String> {
    if (error == null) return Pair("An error occurred", "There was a problem processing this request.")

    return when {
        error.contains("timeout", ignoreCase = true) || error.contains("timed out", ignoreCase = true) ->
            Pair("Request Timeout", "The LLM provider took too long to respond.")

        error.contains("rate limit", ignoreCase = true) || error.contains("too many requests", ignoreCase = true) ->
            Pair("Rate Limit Exceeded", "The LLM provider's rate limit was reached. Try again in a few moments.")

        error.contains("api key", ignoreCase = true) || error.contains("unauthoriz", ignoreCase = true) ||
                error.contains("forbidden", ignoreCase = true) ->
            Pair("Authorization Error", "There's an issue with your API key or permissions.")

        error.contains("connect", ignoreCase = true) || error.contains("network", ignoreCase = true) ->
            Pair("Connection Error", "Failed to establish connection with the LLM provider.")

        else -> Pair("Request Failed", "The LLM provider was unable to process this request.")
    }
}