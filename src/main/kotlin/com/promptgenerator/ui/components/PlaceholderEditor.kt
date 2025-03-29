package com.promptgenerator.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.promptgenerator.ui.icons.AppIcons

data class PlaceholderEditorState(
    val showAddDialog: Boolean = false,
    val selectedPlaceholder: String? = null,
    val dialogErrorMessage: String = ""
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaceholderEditor(
    placeholders: Map<String, String>,
    onPlaceholderChange: (String, String) -> Unit,
    onAddPlaceholder: (String) -> Unit,
    onRemovePlaceholder: (String) -> Unit,
    onRenamePlaceholder: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editorState by remember { mutableStateOf(PlaceholderEditorState()) }

    Card(
        modifier = modifier.fillMaxWidth(),
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
                    text = "Template Variables",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CustomTooltip(
                        tooltip = {
                            Column(modifier = Modifier.width(300.dp)) {
                                Text(
                                    "How Variables Work:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "• Use {{variable_name}} in your template text",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• For multiple values, separate with commas",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• Example: colors = red, blue, green will generate 3 variants",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    ) {
                        IconButton(onClick = { /* Tooltip handled by CustomTooltip */ }) {
                            Icon(
                                imageVector = AppIcons.InfoOutlined,
                                contentDescription = "Help",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Button(onClick = { editorState = editorState.copy(showAddDialog = true) }) {
                        Icon(
                            imageVector = AppIcons.Add,
                            contentDescription = "Add Variable"
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Add Variable")
                    }
                }
            }

            HorizontalDivider()

            if (placeholders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No variables added yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { editorState = editorState.copy(showAddDialog = true) }) {
                            Icon(
                                imageVector = AppIcons.Add,
                                contentDescription = "Add Variable"
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Add Your First Variable")
                        }
                    }
                }
            } else {
                Text(
                    text = "Add values for each variable (separate multiple values with commas):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    placeholders.forEach { (name, value) ->
                        PlaceholderItem(
                            name = name,
                            value = value,
                            onValueChange = { onPlaceholderChange(name, it) },
                            onEdit = { editorState = editorState.copy(selectedPlaceholder = name) },
                            onRemove = { onRemovePlaceholder(name) }
                        )
                    }
                }
            }
        }
    }

    if (editorState.showAddDialog) {
        PlaceholderDialog(
            initialName = "",
            onDismiss = { editorState = editorState.copy(showAddDialog = false, dialogErrorMessage = "") },
            onConfirm = { name ->
                if (name.isBlank()) {
                    editorState = editorState.copy(dialogErrorMessage = "Name cannot be empty")
                } else if (placeholders.containsKey(name)) {
                    editorState = editorState.copy(dialogErrorMessage = "Variable name already exists")
                } else {
                    onAddPlaceholder(name)
                    editorState = editorState.copy(showAddDialog = false, dialogErrorMessage = "")
                }
            },
            title = "Add New Variable",
            confirmButton = "Add",
            errorMessage = editorState.dialogErrorMessage
        )
    }

    editorState.selectedPlaceholder?.let { name ->
        PlaceholderDialog(
            initialName = name,
            onDismiss = { editorState = editorState.copy(selectedPlaceholder = null, dialogErrorMessage = "") },
            onConfirm = { newName ->
                if (newName.isBlank()) {
                    editorState = editorState.copy(dialogErrorMessage = "Name cannot be empty")
                } else if (newName != name && placeholders.containsKey(newName)) {
                    editorState = editorState.copy(dialogErrorMessage = "Variable name already exists")
                } else {
                    onRenamePlaceholder(name, newName)
                    editorState = editorState.copy(selectedPlaceholder = null, dialogErrorMessage = "")
                }
            },
            title = "Rename Variable",
            confirmButton = "Rename",
            errorMessage = editorState.dialogErrorMessage
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaceholderItem(
    name: String,
    value: String,
    onValueChange: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val formattedName = buildAnnotatedString {
        append("{{")
        withStyle(
            style = SpanStyle(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        ) {
            append(name)
        }
        append("}}")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CustomTooltip(
                    tooltip = {
                        Text(
                            text = "This variable will replace all occurrences of $formattedName in your template",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                ) {
                    Text(
                        text = formattedName,
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Edit,
                            contentDescription = "Edit Variable Name",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = AppIcons.Delete,
                            contentDescription = "Remove Variable",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Values (comma-separated)") },
                placeholder = { Text("e.g. value1, value2, value3") }
            )

            AnimatedVisibility(visible = value.contains(",")) {
                Text(
                    text = "Multiple values detected - this will generate multiple prompts",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PlaceholderDialog(
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String,
    confirmButton: String,
    errorMessage: String = ""
) {
    var name by remember { mutableStateOf(initialName) }

    AppDialog(
        title = title,
        onDismiss = onDismiss,
        confirmButton = confirmButton,
        onConfirm = { onConfirm(name) },
        confirmEnabled = name.isNotBlank()
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.replace(Regex("[^a-zA-Z0-9_]"), "")
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Variable Name") },
            placeholder = { Text("e.g. product_name") },
            isError = errorMessage.isNotEmpty(),
            supportingText = {
                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Use letters, numbers, and underscores only")
                }
            }
        )
    }
}