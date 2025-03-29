package com.promptgenerator.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.ui.components.AppDialog
import com.promptgenerator.ui.components.CustomTooltip
import com.promptgenerator.ui.icons.AppIcons
import com.promptgenerator.ui.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ITEMS_PER_PAGE = 10

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onLoadTemplate: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState = viewModel.uiState
    val snackbarHostState = remember { SnackbarHostState() }
    val lazyListState = rememberLazyListState()
    var currentPage by remember { mutableStateOf(0) }

    val paginatedResults by remember(uiState.results, currentPage) {
        derivedStateOf {
            uiState.results
                .drop(currentPage * ITEMS_PER_PAGE)
                .take(ITEMS_PER_PAGE)
        }
    }

    val totalPages = (uiState.results.size + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE

    LaunchedEffect(uiState.results.size) {
        currentPage = 0
    }

    LaunchedEffect(currentPage) {
        lazyListState.scrollToItem(0)
    }

    LaunchedEffect(uiState.errorMessage, uiState.successMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else if (uiState.results.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "No generation history found",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Generate some prompts to see them here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Generation History",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
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
                                    contentDescription = "Previous Page"
                                )
                            }

                            Text(
                                text = "Page ${currentPage + 1}/$totalPages",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            IconButton(
                                onClick = { if (currentPage < totalPages - 1) currentPage++ },
                                enabled = currentPage < totalPages - 1
                            ) {
                                Icon(
                                    imageVector = AppIcons.ExpandMore,
                                    contentDescription = "Next Page"
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(paginatedResults, key = { it.id }) { result ->
                        HistoryItem(
                            result = result,
                            onViewDetails = { viewModel.viewResultDetails(result.id) },
                            onLoadTemplate = { onLoadTemplate(result.templateId) }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    if (uiState.showDetailsDialog) {
        ResultDetailsDialog(
            result = uiState.currentResult,
            isLoading = uiState.isLoadingDetails,
            onDismiss = { viewModel.closeDetailsDialog() },
            onExport = { viewModel.exportResult(it) },
            onDelete = { viewModel.deleteResult(it.id) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryItem(
    result: GenerationResult,
    onViewDetails: () -> Unit,
    onLoadTemplate: () -> Unit
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetails),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = result.templateName.ifBlank { "Untitled Template" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    Text(
                        text = dateFormat.format(Date(result.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                var showMenu by remember { mutableStateOf(false) }

                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = AppIcons.MoreVert,
                            contentDescription = "Options"
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("View Details") },
                            onClick = {
                                showMenu = false
                                onViewDetails()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = AppIcons.Info,
                                    contentDescription = null
                                )
                            }
                        )

                        DropdownMenuItem(
                            text = { Text("Load Template") },
                            onClick = {
                                showMenu = false
                                onLoadTemplate()
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = AppIcons.Edit,
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }

            Divider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Placeholders: ${result.placeholders.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Text(
                        text = "Results: ${result.responses.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (!result.isComplete) {
                    CustomTooltip(
                        tooltip = {
                            Text("This generation was cancelled or encountered an error",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    ) {
                        Icon(
                            imageVector = AppIcons.Warning,
                            contentDescription = "Incomplete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultDetailsDialog(
    result: GenerationResult?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onExport: (GenerationResult) -> Unit,
    onDelete: (GenerationResult) -> Unit
) {
    AppDialog(
        title = result?.templateName?.ifBlank { "Untitled Template" } ?: "Loading...",
        onDismiss = onDismiss,
        confirmButton = "Close",
        onConfirm = onDismiss,
        showCancelButton = false
    ) {
        if (isLoading || result == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Generation info
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                Text(
                    text = "Generated on: ${dateFormat.format(Date(result.timestamp))}",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!result.isComplete) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )

                        Text(
                            text = "This generation was incomplete",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Divider()

                // Placeholders used
                Text(
                    text = "Placeholders Used:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    result.placeholders.entries.take(10).forEach { (name, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$name:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(0.3f)
                            )

                            Text(
                                text = value.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(0.7f)
                            )
                        }
                    }

                    if (result.placeholders.size > 10) {
                        Text(
                            text = "...and ${result.placeholders.size - 10} more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Divider()

                // Results summary
                Text(
                    text = "Results (${result.responses.size}):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                val errors = result.responses.count { it.value.error != null }
                if (errors > 0) {
                    Text(
                        text = "$errors errors occurred during generation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onDelete(result) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = AppIcons.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }

                    Button(
                        onClick = { onExport(result) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = AppIcons.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export")
                    }
                }
            }
        }
    }
}