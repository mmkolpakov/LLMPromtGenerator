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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import kotlin.math.min

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

        if (template.content.isBlank()) {
            return listOf(Request(UUID.randomUUID().toString(), "", systemInstruction))
        }

        val placeholders = extractPlaceholders(template.content)

        if (placeholders.isEmpty()) {
            logger.debug("No placeholders found")
            return listOf(Request(UUID.randomUUID().toString(), template.content, systemInstruction))
        }

        val relevantData = data.filterKeys { placeholders.contains(it) }

        if (relevantData.isEmpty()) {
            logger.warn("No data provided for any placeholders")
            return listOf(Request(UUID.randomUUID().toString(), template.content, systemInstruction))
        }

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

        val missingPlaceholders = placeholders.filter { !placeholderValues.containsKey(it) }
        for (placeholder in missingPlaceholders) {
            logger.warn("No data provided for placeholder: $placeholder")
        }

        val lazyGenerator = CombinationGenerator(placeholderValues, maxCombinations)

        return lazyGenerator.generate().map { combination ->
            var content = template.content
            for ((placeholder, value) in combination) {
                content = content.replace("{{$placeholder}}", value.toString())
            }

            val remainingPlaceholders = extractPlaceholders(content)
            if (remainingPlaceholders.isNotEmpty()) {
                logger.warn("Template still contains un-replaced placeholders: $remainingPlaceholders")
            }

            Request(UUID.randomUUID().toString(), content, systemInstruction)
        }
    }

    private fun processPlaceholderValues(
        placeholders: Set<String>,
        data: Map<String, Any>
    ): Map<String, List<Any>> {
        return placeholders
            .mapNotNull { placeholder ->
                data[placeholder]?.let { value ->
                    val processedValue = when (value) {
                        is List<*> -> value.filterNotNull().ifEmpty { listOf("") }
                        is String -> parseStringValue(value).ifEmpty { listOf("") }
                        else -> listOf(value)
                    }
                    placeholder to processedValue
                }
            }
            .toMap()
    }

    private fun parseStringValue(value: String): List<Any> {
        if (value.isBlank()) return listOf("")

        return if (value.trim().startsWith("[") && value.trim().endsWith("]")) {
            val listContent = value.trim().removeSurrounding("[", "]")
            parseCommaDelimitedList(listContent).ifEmpty { listOf("") }
        } else if (value.contains(",")) {
            parseCommaDelimitedList(value).ifEmpty { listOf("") }
        } else {
            listOf(value)
        }
    }

    private fun parseCommaDelimitedList(input: String): List<String> {
        if (input.isBlank()) return listOf("")

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

        val lastValue = currentValue.toString().trim()
        if (lastValue.isNotEmpty() || input.endsWith(",")) {
            values.add(lastValue)
        }

        return values.map { value ->
            if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
                value.substring(1, value.length - 1)
            } else {
                value
            }
        }
    }

    private class CombinationGenerator(
        private val placeholderValues: Map<String, List<*>>,
        private val maxCombinations: Int,
    ) {
        private val logger = LoggerFactory.getLogger(this::class.java)
        private val totalPossibleCombinations: Long

        init {
            totalPossibleCombinations = placeholderValues.values.fold(1L) { acc, values ->
                acc * values.size
            }

            if (totalPossibleCombinations > maxCombinations) {
                logger.warn("Total possible combinations ($totalPossibleCombinations) exceeds max allowed ($maxCombinations)")
            }
        }

        fun generate(): List<Map<String, Any>> {
            if (placeholderValues.isEmpty()) {
                return listOf(emptyMap())
            }

            val placeholderKeys = placeholderValues.keys.toList()
            val indices = IntArray(placeholderKeys.size) { 0 }
            val result = mutableListOf<Map<String, Any>>()
            val maxResults = min(maxCombinations, Integer.MAX_VALUE).toInt()

            val counter = AtomicInteger(0)

            while (counter.get() < maxResults) {
                val combination = placeholderKeys.withIndex().associate { (i, key) ->
                    val values = placeholderValues[key] ?: emptyList<Any>()
                    if (values.isEmpty()) {
                        key to ""
                    } else {
                        val value = values[indices[i]]
                        key to (value ?: "")
                    }
                }

                result.add(combination)
                counter.incrementAndGet()

                var incrementIndex = placeholderKeys.size - 1
                while (incrementIndex >= 0) {
                    val valueList = placeholderValues[placeholderKeys[incrementIndex]] ?: emptyList<Any>()
                    indices[incrementIndex] = (indices[incrementIndex] + 1) % valueList.size.coerceAtLeast(1)

                    if (indices[incrementIndex] != 0) {
                        break
                    }

                    incrementIndex--
                }

                if (incrementIndex < 0) {
                    break
                }
            }

            return result
        }
    }

    override suspend fun saveTemplate(template: Template): Result<Template> =
        withContext(Dispatchers.IO) {
            try {
                if (!validateTemplate(template.content).isValid) {
                    return@withContext Result.failure(IllegalArgumentException("Invalid template"))
                }

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

    private val nestedPattern = Pattern.compile("\\{\\{[^}]*\\{\\{")
    private val unbalancedPatternOpen = Pattern.compile("\\{\\{[^}]*$")
    private val unbalancedPatternClose = Pattern.compile("^[^{]*\\}\\}")

    override fun validateTemplate(templateContent: String): ValidationResult {
        val errors = mutableListOf<String>()

        if (templateContent.isBlank()) {
            errors.add("Template content cannot be empty")
            return ValidationResult(false, errors)
        }

        val openBraceCount = templateContent.count { it == '{' }
        val closeBraceCount = templateContent.count { it == '}' }

        if (openBraceCount != closeBraceCount) {
            errors.add("Unbalanced placeholder braces: found $openBraceCount '{' and $closeBraceCount '}'")
        }

        val placeholders = mutableSetOf<String>()
        val matcher = placeholderPattern.matcher(templateContent)

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

        val nestedMatcher = nestedPattern.matcher(templateContent)
        if (nestedMatcher.find()) {
            errors.add("Nested placeholders are not supported: ${nestedMatcher.group(0)}...")
        }

        val unbalancedOpenMatcher = unbalancedPatternOpen.matcher(templateContent)
        if (unbalancedOpenMatcher.find()) {
            errors.add("Unclosed placeholder: ${unbalancedOpenMatcher.group(0)}")
        }

        val unbalancedCloseMatcher = unbalancedPatternClose.matcher(templateContent)
        if (unbalancedCloseMatcher.find()) {
            errors.add("Unopened placeholder closing: ${unbalancedCloseMatcher.group(0)}")
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