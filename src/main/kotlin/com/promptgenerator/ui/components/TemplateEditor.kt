package com.promptgenerator.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.promptgenerator.domain.model.Template
import com.promptgenerator.domain.repository.ValidationResult
import com.promptgenerator.ui.icons.AppIcons
import java.util.UUID
import java.util.regex.Pattern

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TemplateEditor(
    value: String,
    onValueChange: (String) -> Unit,
    validation: ValidationResult = ValidationResult(true),
    placeholders: List<String> = emptyList(),
    templates: List<Template> = emptyList(),
    onPlaceholderDetected: (String) -> Unit = {},
    onSaveTemplate: () -> Unit = {},
    onLoadTemplate: (Template) -> Unit = {},
    onNewTemplate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showExpandedDialog by remember { mutableStateOf(false) }
    var showSamplesDialog by remember { mutableStateOf(false) }
    var showTemplatesMenu by remember { mutableStateOf(false) }

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
                    text = "Template",
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
                                    "How Templates Work:",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "• Use {{variable_name}} for dynamic content",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• Variables will be replaced with values you provide",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• Click 'See Examples' for template ideas",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.inversePrimary
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

                    Box {
                        OutlinedButton(onClick = { showTemplatesMenu = true }) {
                            Icon(
                                imageVector = AppIcons.Menu,
                                contentDescription = "Template Menu",
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Templates")
                        }

                        DropdownMenu(
                            expanded = showTemplatesMenu,
                            onDismissRequest = { showTemplatesMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New Template") },
                                onClick = {
                                    showTemplatesMenu = false
                                    onNewTemplate()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = AppIcons.Add,
                                        contentDescription = "New Template"
                                    )
                                }
                            )

                            if (templates.isNotEmpty()) {
                                HorizontalDivider()

                                Text(
                                    text = "Saved Templates",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )

                                templates.forEach { template ->
                                    DropdownMenuItem(
                                        text = { Text(template.name) },
                                        onClick = {
                                            showTemplatesMenu = false
                                            onLoadTemplate(template)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    TextButton(onClick = { showSamplesDialog = true }) {
                        Text("See Examples")
                    }

                    Button(onClick = onSaveTemplate) {
                        Icon(
                            imageVector = AppIcons.Save,
                            contentDescription = "Save Template",
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("Save")
                    }

                    IconButton(onClick = { showExpandedDialog = true }) {
                        Icon(
                            imageVector = AppIcons.ExpandMore,
                            contentDescription = "Expand Editor",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Text(
                text = "Enter your template with placeholders in {{variable_name}} format:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = value,
                onValueChange = {
                    onValueChange(it)
                    val pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}")
                    val matcher = pattern.matcher(it)
                    while (matcher.find()) {
                        val placeholder = matcher.group(1)
                        if (placeholder != null && !placeholders.contains(placeholder)) {
                            onPlaceholderDetected(placeholder)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                placeholder = { Text("Enter your template here...") },
                visualTransformation = PlaceholderHighlightTransformation(
                    knownPlaceholders = placeholders,
                    knownPlaceholderColor = MaterialTheme.colorScheme.primary,
                    newPlaceholderColor = androidx.compose.ui.graphics.Color(0xFF9C27B0)
                ),
                isError = !validation.isValid,
                supportingText = {
                    if (!validation.isValid) {
                        Text(
                            validation.errors.firstOrNull() ?: "Invalid template",
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text("Use {{variable_name}} syntax for dynamic content")
                    }
                }
            )
        }
    }

    if (showExpandedDialog) {
        Dialog(
            onDismissRequest = { showExpandedDialog = false }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Edit Template",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        IconButton(onClick = { showExpandedDialog = false }) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "Close Expanded View"
                            )
                        }
                    }

                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            onValueChange(it)
                            val pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}")
                            val matcher = pattern.matcher(it)
                            while (matcher.find()) {
                                val placeholder = matcher.group(1)
                                if (placeholder != null && !placeholders.contains(placeholder)) {
                                    onPlaceholderDetected(placeholder)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        placeholder = { Text("Enter your template here...") },
                        visualTransformation = PlaceholderHighlightTransformation(
                            knownPlaceholders = placeholders,
                            knownPlaceholderColor = MaterialTheme.colorScheme.primary,
                            newPlaceholderColor = androidx.compose.ui.graphics.Color(0xFF9C27B0)
                        ),
                        isError = !validation.isValid,
                        supportingText = {
                            if (!validation.isValid) {
                                Text(
                                    validation.errors.firstOrNull() ?: "Invalid template",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showExpandedDialog = false }) {
                            Text("Done")
                        }
                    }
                }
            }
        }
    }

    if (showSamplesDialog) {
        Dialog(
            onDismissRequest = { showSamplesDialog = false }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxSize()
                ) {
                    Text(
                        text = "Template Examples",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TemplateExample(
                            title = "Marketing Email",
                            template = """
                                Subject: {{offer_type}} offer for you, {{customer_name}}!
                                
                                Dear {{customer_name}},
                                
                                We noticed you recently purchased {{recent_purchase}}. We think you might also like our {{recommended_product}} which is now {{discount}}% off!
                                
                                Click here to view this exclusive offer.
                                
                                Best regards,
                                The {{company_name}} Team
                            """.trimIndent(),
                            onUseTemplate = { content ->
                                showSamplesDialog = false
                                val exampleTemplate = Template(
                                    id = "example_" + UUID.randomUUID().toString(),
                                    name = "Marketing Email Example",
                                    content = content
                                )
                                onLoadTemplate(exampleTemplate)
                            }
                        )

                        HorizontalDivider()

                        TemplateExample(
                            title = "Product Description",
                            template = """
                                # {{product_name}}
                                
                                **Price:** {{price}}
                                
                                ## Description
                                
                                The {{product_name}} is a premium {{product_category}} designed for {{target_audience}}. It features {{feature1}}, {{feature2}}, and {{feature3}}.
                                
                                ## Benefits
                                
                                - {{benefit1}}
                                - {{benefit2}}
                                - Perfect for {{use_case}}
                                
                                Available in {{color}} and {{size}} options.
                            """.trimIndent(),
                            onUseTemplate = { content ->
                                showSamplesDialog = false
                                val exampleTemplate = Template(
                                    id = "example_" + UUID.randomUUID().toString(),
                                    name = "Product Description Example",
                                    content = content
                                )
                                onLoadTemplate(exampleTemplate)
                            }
                        )

                        HorizontalDivider()

                        TemplateExample(
                            title = "Social Media Post",
                            template = """
                                Introducing our new {{product_type}}: {{product_name}}! 
                                
                                ✨ {{key_benefit}}
                                🔥 Perfect for {{use_case}}
                                💯 {{unique_feature}}
                                
                                Available now at {{price}}! Use code {{promo_code}} for {{discount}}% off your first purchase. 
                                
                                #{{hashtag1}} #{{hashtag2}} #{{brand_name}}
                            """.trimIndent(),
                            onUseTemplate = { content ->
                                showSamplesDialog = false
                                val exampleTemplate = Template(
                                    id = "example_" + UUID.randomUUID().toString(),
                                    name = "Social Media Post Example",
                                    content = content
                                )
                                onLoadTemplate(exampleTemplate)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { showSamplesDialog = false }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateExample(
    title: String,
    template: String,
    onUseTemplate: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = template,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = { onUseTemplate(template) }
            ) {
                Icon(
                    imageVector = AppIcons.Copy,
                    contentDescription = "Use Template",
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Use This Template")
            }
        }
    }
}

private class PlaceholderHighlightTransformation(
    private val knownPlaceholders: List<String> = emptyList(),
    private val knownPlaceholderColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Blue,
    private val newPlaceholderColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0xFF9C27B0)
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlightedText = buildAnnotatedString {
            val pattern = Pattern.compile("\\{\\{([^}]+)\\}\\}")
            val matcher = pattern.matcher(text.text)
            var lastMatchEnd = 0

            while (matcher.find()) {
                append(text.text.substring(lastMatchEnd, matcher.start()))

                val placeholderName = matcher.group(1) ?: ""
                val isKnown = knownPlaceholders.contains(placeholderName)

                append("{{")

                withStyle(
                    style = SpanStyle(
                        color = if (isKnown) knownPlaceholderColor else newPlaceholderColor,
                        fontWeight = FontWeight.Bold
                    )
                ) {
                    append(placeholderName)
                }

                append("}}")

                lastMatchEnd = matcher.end()
            }

            if (lastMatchEnd < text.text.length) {
                append(text.text.substring(lastMatchEnd))
            }
        }

        return TransformedText(highlightedText, OffsetMapping.Identity)
    }
}