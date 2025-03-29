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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.promptgenerator.ui.components.AppDialog
import com.promptgenerator.ui.components.CustomTooltip
import com.promptgenerator.ui.icons.AppIcons
import com.promptgenerator.ui.viewmodel.ProviderSettingsUiState
import com.promptgenerator.ui.viewmodel.SettingsViewModel

data class SettingsFormState(
    val maxCombinations: String = "1000",
    val showPartialResults: Boolean = true,
    val resultsLimit: String = "100",
    val saveResultsPath: String = "generated_prompts",
    val saveTemplatesPath: String = "templates",
    val exportFileFormat: String = "md",
    val hasChanges: Boolean = false
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState = viewModel.uiState
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showProviderEditDialog by remember { mutableStateOf<ProviderSettingsUiState?>(null) }

    // Settings form state
    var formState by remember {
        mutableStateOf(
            SettingsFormState(
                maxCombinations = uiState.maxCombinations.toString(),
                showPartialResults = uiState.showPartialResults,
                resultsLimit = uiState.resultsLimit.toString(),
                saveResultsPath = uiState.saveResultsPath,
                saveTemplatesPath = uiState.saveTemplatesPath,
                exportFileFormat = uiState.exportFileFormat
            )
        )
    }

    // Track if there are unsaved changes
    val hasChanges by remember(formState, uiState) {
        derivedStateOf {
            formState.maxCombinations != uiState.maxCombinations.toString() ||
                    formState.showPartialResults != uiState.showPartialResults ||
                    formState.resultsLimit != uiState.resultsLimit.toString() ||
                    formState.saveResultsPath != uiState.saveResultsPath ||
                    formState.saveTemplatesPath != uiState.saveTemplatesPath ||
                    formState.exportFileFormat != uiState.exportFileFormat
        }
    }

    // Update form state to reflect current UI state
    LaunchedEffect(uiState) {
        formState = SettingsFormState(
            maxCombinations = uiState.maxCombinations.toString(),
            showPartialResults = uiState.showPartialResults,
            resultsLimit = uiState.resultsLimit.toString(),
            saveResultsPath = uiState.saveResultsPath,
            saveTemplatesPath = uiState.saveTemplatesPath,
            exportFileFormat = uiState.exportFileFormat
        )
    }

    // Show error or success message
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            // General settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "General Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Generation settings
                    OutlinedTextField(
                        value = formState.maxCombinations,
                        onValueChange = { value ->
                            // Only allow numeric input
                            if (value.all { it.isDigit() } || value.isEmpty()) {
                                formState = formState.copy(maxCombinations = value)
                            }
                        },
                        label = { Text("Maximum Combinations") },
                        supportingText = { Text("Maximum number of combinations to generate") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Show partial results during generation",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )

                        Switch(
                            checked = formState.showPartialResults,
                            onCheckedChange = {
                                formState = formState.copy(showPartialResults = it)
                            }
                        )
                    }

                    OutlinedTextField(
                        value = formState.resultsLimit,
                        onValueChange = { value ->
                            // Only allow numeric input
                            if (value.all { it.isDigit() } || value.isEmpty()) {
                                formState = formState.copy(resultsLimit = value)
                            }
                        },
                        label = { Text("Results Limit") },
                        supportingText = { Text("Maximum number of results to store") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = formState.saveResultsPath,
                        onValueChange = { formState = formState.copy(saveResultsPath = it) },
                        label = { Text("Results Export Path") },
                        supportingText = { Text("Directory to save exported results") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = formState.saveTemplatesPath,
                        onValueChange = { formState = formState.copy(saveTemplatesPath = it) },
                        label = { Text("Templates Path") },
                        supportingText = { Text("Directory to save templates") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Export File Format section
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Export File Format",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = formState.exportFileFormat == "md",
                                onClick = { formState = formState.copy(exportFileFormat = "md") }
                            )
                            Text(
                                text = "Markdown (.md)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = formState.exportFileFormat == "txt",
                                onClick = { formState = formState.copy(exportFileFormat = "txt") }
                            )
                            Text(
                                text = "Plain Text (.txt)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Text(
                            text = "Note: Markdown format preserves formatting in LLM responses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Save settings button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                viewModel.updateSettings(
                                    maxCombinations = formState.maxCombinations.toIntOrNull() ?: 1000,
                                    showPartialResults = formState.showPartialResults,
                                    resultsLimit = formState.resultsLimit.toIntOrNull() ?: 100,
                                    saveResultsPath = formState.saveResultsPath,
                                    saveTemplatesPath = formState.saveTemplatesPath,
                                    exportFileFormat = formState.exportFileFormat
                                )
                            },
                            enabled = hasChanges &&
                                    formState.maxCombinations.isNotEmpty() &&
                                    formState.resultsLimit.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = AppIcons.Save,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Save Settings")
                        }
                    }
                }
            }

            // Provider settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "LLM Provider Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Default Provider:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Default provider selection
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.providers.forEach { provider ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = provider.name == uiState.defaultProvider,
                                    onClick = { viewModel.setDefaultProvider(provider.name) }
                                )

                                Text(
                                    text = provider.name.capitalize(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                CustomTooltip(
                                    tooltip = {
                                        Text("Edit provider settings")
                                    }
                                ) {
                                    OutlinedButton(
                                        onClick = { showProviderEditDialog = provider },
                                        modifier = Modifier.padding(start = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = AppIcons.Edit,
                                            contentDescription = "Edit",
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        Text("Edit")
                                    }
                                }
                            }

                            if (provider.name == uiState.defaultProvider) {
                                Text(
                                    text = "Current model: ${provider.defaultModel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 36.dp, bottom = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Snackbar for messages
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    // Provider edit dialog
    showProviderEditDialog?.let { provider ->
        ProviderEditDialog(
            provider = provider,
            onDismiss = { showProviderEditDialog = null },
            onSave = { name, baseUrl, apiKey, model ->
                viewModel.updateProvider(name, baseUrl, apiKey, model)
                showProviderEditDialog = null
            }
        )
    }
}

private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

@Composable
private fun ProviderEditDialog(
    provider: ProviderSettingsUiState,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var baseUrl by remember { mutableStateOf(provider.baseUrl) }
    var apiKey by remember { mutableStateOf(provider.apiKey) }
    var defaultModel by remember { mutableStateOf(provider.defaultModel) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    AppDialog(
        title = "Edit Provider: ${provider.name.capitalize()}",
        onDismiss = onDismiss,
        confirmButton = "Save",
        onConfirm = {
            if (defaultModel.isBlank()) {
                hasError = true
                errorMessage = "Model name cannot be empty"
            } else {
                onSave(provider.name, baseUrl, apiKey, defaultModel)
            }
        },
        confirmEnabled = !hasError && defaultModel.isNotBlank()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Divider()

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                supportingText = { Text("API keys are stored locally and used only for API requests") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = defaultModel,
                onValueChange = {
                    defaultModel = it
                    hasError = it.isBlank()
                    if (hasError) errorMessage = "Model name cannot be empty"
                },
                label = { Text("Default Model") },
                modifier = Modifier.fillMaxWidth(),
                isError = hasError,
                supportingText = {
                    if (hasError) {
                        Text(errorMessage, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Model ID used for requests")
                    }
                },
                singleLine = true
            )
        }
    }
}