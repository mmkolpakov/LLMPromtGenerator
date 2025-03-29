package com.promptgenerator.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promptgenerator.domain.model.Template
import com.promptgenerator.ui.components.AppDialog
import com.promptgenerator.ui.components.CustomTooltip
import com.promptgenerator.ui.components.ErrorView
import com.promptgenerator.ui.components.PlaceholderEditor
import com.promptgenerator.ui.components.ResultsView
import com.promptgenerator.ui.components.TemplateEditor
import com.promptgenerator.ui.icons.AppIcons
import com.promptgenerator.ui.viewmodel.GeneratorViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GeneratorScreen(
    viewModel: GeneratorViewModel,
    modifier: Modifier = Modifier
) {
    val uiState = viewModel.uiState
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSystemPromptDialog by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf(uiState.systemPrompt) }
    var dialogError by remember { mutableStateOf("") }

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

    LaunchedEffect(Unit) {
        viewModel.loadAllTemplates()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Text(
                                text = "LLM Role Instructions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            CustomTooltip(
                                tooltip = {
                                    Text(
                                        "Set instructions for how the LLM should respond",
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            ) {
                                TextButton(onClick = {
                                    systemPrompt = uiState.systemPrompt
                                    showSystemPromptDialog = true
                                }) {
                                    Icon(
                                        imageVector = AppIcons.Edit,
                                        contentDescription = "Edit System Prompt",
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    Text("Edit")
                                }
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = uiState.systemPrompt.ifBlank { "No role instructions set. The LLM will use default behavior." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.systemPrompt.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                TemplateEditor(
                    value = uiState.templateContent,
                    onValueChange = { viewModel.updateTemplateContent(it) },
                    validation = uiState.templateValidation,
                    placeholders = uiState.placeholderData.keys.toList(),
                    templates = uiState.templates,
                    onPlaceholderDetected = { viewModel.addDetectedPlaceholder(it) },
                    onSaveTemplate = {
                        templateName = uiState.templateName
                        dialogError = ""
                        showSaveDialog = true
                    },
                    onLoadTemplate = { template ->
                        viewModel.loadTemplateDirectly(template)
                    },
                    onNewTemplate = {
                        viewModel.createNewTemplate()
                    }
                )

                PlaceholderEditor(
                    placeholders = uiState.placeholderData,
                    onPlaceholderChange = { key, value -> viewModel.updatePlaceholderData(key, value) },
                    onAddPlaceholder = { name -> viewModel.addPlaceholder(name) },
                    onRemovePlaceholder = { viewModel.removePlaceholder(it) },
                    onRenamePlaceholder = { oldName, newName -> viewModel.renamePlaceholder(oldName, newName) }
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { viewModel.generatePrompts() },
                        enabled = !uiState.isGenerating &&
                                uiState.templateContent.isNotBlank() &&
                                uiState.templateValidation.isValid,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("Generate Prompts")
                    }

                    if (uiState.templateValidation.placeholders.isNotEmpty() &&
                        uiState.templateValidation.placeholders.size > uiState.placeholderData.count { (_, value) -> value.isNotBlank() }) {
                        Text(
                            text = "Note: Empty placeholders will be replaced with blank values",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                if (uiState.networkError != null) {
                    ErrorView(
                        error = uiState.networkError,
                        onRetry = { viewModel.retryFailedRequests() },
                        onDismiss = { viewModel.clearNetworkError() }
                    )
                }

                ResultsView(
                    results = uiState.results,
                    partialResults = uiState.partialResponses,
                    isGenerating = uiState.isGenerating,
                    generationStatus = uiState.generationStatus,
                    generationProgress = uiState.generationProgress,
                    completedCount = uiState.completedCount,
                    totalCount = uiState.totalCount,
                    showPartialResults = uiState.showPartialResults,
                    exportPath = uiState.exportPath,
                    onClearResults = { viewModel.clearResults() },
                    onCancelGeneration = { viewModel.cancelGeneration() },
                    onSaveResults = { viewModel.exportResults() },
                    onToggleShowPartial = { viewModel.toggleShowPartialResults() },
                    onRetryFailedRequest = { viewModel.retryFailedRequest(it) }
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showSaveDialog) {
        SaveTemplateDialog(
            initialName = templateName,
            errorMessage = dialogError,
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                if (name.isBlank()) {
                    dialogError = "Template name cannot be empty"
                } else {
                    viewModel.saveCurrentTemplate(name)
                    showSaveDialog = false
                    dialogError = ""
                }
            }
        )
    }

    if (showSystemPromptDialog) {
        SystemPromptDialog(
            initialPrompt = systemPrompt,
            onDismiss = { showSystemPromptDialog = false },
            onSave = { prompt ->
                viewModel.updateSystemPrompt(prompt)
                showSystemPromptDialog = false
            }
        )
    }
}

@Composable
private fun SaveTemplateDialog(
    initialName: String,
    errorMessage: String = "",
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AppDialog(
        title = "Save Template",
        onDismiss = onDismiss,
        confirmButton = "Save",
        onConfirm = { onSave(name) },
        confirmEnabled = name.isNotBlank()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Template Name") },
                modifier = Modifier.fillMaxWidth(),
                isError = errorMessage.isNotEmpty(),
                supportingText = {
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    }
                },
                singleLine = true
            )
        }
    }
}

@Composable
private fun SystemPromptDialog(
    initialPrompt: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var prompt by remember { mutableStateOf(initialPrompt) }

    AppDialog(
        title = "LLM Role Instructions",
        onDismiss = onDismiss,
        confirmButton = "Save",
        onConfirm = { onSave(prompt) }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Set instructions that define how the LLM should respond to your prompts. This acts as a 'system prompt' that guides the LLM's behavior.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Role Instructions") },
                placeholder = { Text("e.g., You are a marketing expert generating content based exactly on the template provided.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                supportingText = {
                    Text("Clear instructions help the LLM generate exactly what you need without extra text.")
                }
            )

            TextButton(
                onClick = {
                    prompt = "You are a helpful AI assistant tasked with generating responses based on the provided template. Respond only with the output based on the template and variables provided. Do not add any explanations, introductions, or additional text outside the template structure."
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Use Default")
            }
        }
    }
}