package com.promptgenerator.data.repository

import com.promptgenerator.domain.model.Request
import com.promptgenerator.domain.model.Template
import com.promptgenerator.domain.repository.TemplateRepository
import com.promptgenerator.domain.repository.ValidationResult
import com.promptgenerator.data.source.local.TemplateLocalDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.LinkedList
import java.util.UUID
import java.util.regex.Pattern

class TemplateRepositoryImpl(
    private val localDataSource: TemplateLocalDataSource
) : TemplateRepository {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val placeholderPattern = Pattern.compile("\\{\\{([^}]+)\\}\\}")

    override fun processTemplate(
        template: Template,
        data: Map<String, Any>,
        maxCombinations: Int,
        systemInstruction: String?
    ): List<Request> {
        logger.debug("Processing template: '${template.name}'")

        // Find all placeholders in the template
        val placeholders = extractPlaceholders(template.content)

        // If no placeholders, return content as is
        if (placeholders.isEmpty()) {
            logger.debug("No placeholders found")
            return listOf(Request(UUID.randomUUID().toString(), template.content, systemInstruction))
        }

        // Filter data to only include known placeholders
        val relevantData = data.filterKeys { placeholders.contains(it) }

        // Process placeholder values safely
        val placeholderValues = try {
            processPlaceholderValues(placeholders, relevantData)
        } catch (e: Exception) {
            logger.error("Error processing placeholder values", e)
            return listOf(Request(
                UUID.randomUUID().toString(),
                "Error processing template: ${e.message}",
                systemInstruction
            ))
        }

        // Log placeholders without data
        placeholders.filter { !placeholderValues.containsKey(it) }.forEach { placeholder ->
            logger.warn("No data provided for placeholder: $placeholder")
        }

        // Generate combinations lazily, limiting to maxCombinations
        val lazyGenerator = CombinationGenerator(placeholderValues, maxCombinations)

        // Generate requests for each combination
        return lazyGenerator.generate().map { combination ->
            var content = template.content
            combination.forEach { (placeholder, value) ->
                content = content.replace("{{$placeholder}}", value.toString())
            }
            Request(UUID.randomUUID().toString(), content, systemInstruction)
        }
    }

    private fun processPlaceholderValues(
        placeholders: Set<String>,
        data: Map<String, Any>
    ): Map<String, List<Any>> {
        return placeholders
            .filter { data.containsKey(it) }
            .mapNotNull { placeholder ->
                data[placeholder]?.let { value ->
                    placeholder to when (value) {
                        is List<*> -> value.filterNotNull()
                        is String -> parseStringValue(value)
                        else -> listOf(value)
                    }
                }
            }
            .toMap()
    }

    private fun parseStringValue(value: String): List<Any> {
        if (value.isBlank()) return emptyList()

        // Support explicit list format [value1, value2] or comma-separated
        return if (value.trim().startsWith("[") && value.trim().endsWith("]")) {
            val listContent = value.trim().removeSurrounding("[", "]")
            parseCommaDelimitedList(listContent)
        } else if (value.contains(",")) {
            parseCommaDelimitedList(value)
        } else {
            listOf(value)
        }
    }

    private fun parseCommaDelimitedList(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val values = mutableListOf<String>()
        var currentValue = StringBuilder()
        var inQuotes = false
        var escaped = false

        for (char in input) {
            when {
                escaped -> {
                    currentValue.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '"' && !inQuotes -> inQuotes = true
                char == '"' && inQuotes -> inQuotes = false
                char == ',' && !inQuotes -> {
                    values.add(currentValue.toString().trim())
                    currentValue = StringBuilder()
                }
                else -> currentValue.append(char)
            }
        }

        // Add the last value
        if (currentValue.isNotEmpty() || input.endsWith(",")) {
            values.add(currentValue.toString().trim())
        }

        // Handle quoted values
        return values.map { value ->
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value.substring(1, value.length - 1)
            } else {
                value
            }
        }
    }

    // Lazy combination generator
    private class CombinationGenerator(
        private val placeholderValues: Map<String, List<*>>,
        private val maxCombinations: Int
    ) {
        fun generate(): List<Map<String, Any>> {
            if (placeholderValues.isEmpty()) {
                return listOf(emptyMap())
            }

            val result = mutableListOf<Map<String, Any>>()
            val queue = LinkedList<Pair<Int, Map<String, Any>>>()
            val placeholderKeys = placeholderValues.keys.toList()

            // Start with empty combination at index 0
            queue.add(0 to emptyMap())

            while (queue.isNotEmpty() && result.size < maxCombinations) {
                val (index, currentCombination) = queue.poll()

                // If we've processed all placeholders, add the combination to results
                if (index >= placeholderKeys.size) {
                    result.add(currentCombination)
                    continue
                }

                // Get current placeholder and its values
                val currentPlaceholder = placeholderKeys[index]
                val values = placeholderValues[currentPlaceholder] ?: continue

                // Add a new combination for each value of the current placeholder
                for (value in values) {
                    if (value == null) continue

                    val newCombination = currentCombination + (currentPlaceholder to value)
                    queue.add((index + 1) to newCombination)

                    // Check if we've reached maximum combinations
                    if (result.size + queue.size >= maxCombinations) {
                        break
                    }
                }
            }

            return result.take(maxCombinations)
        }
    }

    override suspend fun saveTemplate(template: Template): Result<Template> =
        withContext(Dispatchers.IO) {
            try {
                val savedTemplate = localDataSource.saveTemplate(template)
                Result.success(savedTemplate)
            } catch (e: Exception) {
                logger.error("Error saving template", e)
                Result.failure(e)
            }
        }

    override suspend fun getTemplate(id: String): Result<Template> =
        withContext(Dispatchers.IO) {
            try {
                val template = localDataSource.getTemplate(id)
                if (template != null) {
                    Result.success(template)
                } else {
                    Result.failure(NoSuchElementException("Template not found: $id"))
                }
            } catch (e: Exception) {
                logger.error("Error getting template", e)
                Result.failure(e)
            }
        }

    override fun getAllTemplates(): Flow<List<Template>> = localDataSource.getAllTemplates()

    override suspend fun deleteTemplate(id: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val result = localDataSource.deleteTemplate(id)
                Result.success(result)
            } catch (e: Exception) {
                logger.error("Error deleting template", e)
                Result.failure(e)
            }
        }

    // Compiled patterns to avoid repeated regex compilation
    private val nestedPattern = Pattern.compile("\\{\\{[^}]*\\{\\{")

    override fun validateTemplate(templateContent: String): ValidationResult {
        val errors = mutableListOf<String>()

        // Check for empty content
        if (templateContent.isBlank()) {
            errors.add("Template content cannot be empty")
            return ValidationResult(false, errors)
        }

        // Count opening and closing braces
        val openBraceCount = templateContent.count { it == '{' }
        val closeBraceCount = templateContent.count { it == '}' }

        if (openBraceCount != closeBraceCount) {
            errors.add("Unbalanced placeholder braces: found $openBraceCount '{' and $closeBraceCount '}'")
        }

        // Extract and validate placeholder names
        val placeholders = mutableSetOf<String>()
        var matcher = placeholderPattern.matcher(templateContent)

        while (matcher.find()) {
            val placeholder = matcher.group(1)

            if (placeholder.isNullOrBlank()) {
                errors.add("Empty placeholder found: {{}}")
            } else if (!placeholder.matches(Regex("[a-zA-Z0-9_]+"))) {
                errors.add("Invalid placeholder name: '$placeholder'. Use only letters, numbers, and underscores.")
            } else {
                placeholders.add(placeholder)
            }
        }

        // Check for nested or invalid placeholders
        matcher = nestedPattern.matcher(templateContent)
        if (matcher.find()) {
            errors.add("Nested placeholders are not supported: ${matcher.group(0)}...")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            placeholders = placeholders
        )
    }

    override fun extractPlaceholders(templateContent: String): Set<String> {
        val placeholders = mutableSetOf<String>()
        val matcher = placeholderPattern.matcher(templateContent)

        while (matcher.find()) {
            val placeholder = matcher.group(1)
            if (!placeholder.isNullOrBlank()) {
                placeholders.add(placeholder)
            }
        }

        return placeholders
    }
}