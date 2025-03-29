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
        logger.info("Processing template with data: $data")

        if (template.content.isBlank()) {
            logger.warn("Template content is blank, returning empty request")
            return listOf(Request(UUID.randomUUID().toString(), "", systemInstruction))
        }

        val placeholders = extractPlaceholders(template.content)
        logger.info("Found ${placeholders.size} placeholders in template: $placeholders")

        if (placeholders.isEmpty()) {
            logger.debug("No placeholders found, returning template as is")
            return listOf(Request(UUID.randomUUID().toString(), template.content, systemInstruction))
        }

        val relevantData = data.filterKeys { placeholders.contains(it) }
        logger.info("Relevant data for placeholders: $relevantData")

        if (relevantData.isEmpty()) {
            logger.warn("No data provided for any placeholders, using template with empty placeholders")
            val content = placeholders.fold(template.content) { acc, placeholder ->
                acc.replace("{{$placeholder}}", "")
            }
            return listOf(Request(UUID.randomUUID().toString(), content, systemInstruction))
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
            logger.warn("No data provided for placeholder: $placeholder - will use empty string")
            placeholderValues[placeholder] = listOf("")
        }

        logger.info("Generating combinations with placeholderValues: $placeholderValues")

        val requests = generateCombinations(template, placeholderValues, maxCombinations, systemInstruction)
        logger.info("Generated ${requests.size} requests from template")

        return requests
    }

    private fun generateCombinations(
        template: Template,
        placeholderValues: Map<String, List<Any>>,
        maxCombinations: Int,
        systemInstruction: String?
    ): List<Request> {
        if (placeholderValues.isEmpty()) {
            return listOf(Request(UUID.randomUUID().toString(), template.content, systemInstruction))
        }

        val placeholderKeys = placeholderValues.keys.toList()
        val maxSizes = placeholderKeys.map { placeholderValues[it]?.size ?: 1 }

        var totalCombinations = 1L
        for (size in maxSizes) {
            if (size > 0) {
                val newTotal = totalCombinations * size
                if (newTotal / size != totalCombinations) {
                    totalCombinations = Long.MAX_VALUE
                    break
                }
                totalCombinations = newTotal
            }
        }

        val limitedCombinations = min(totalCombinations, maxCombinations.toLong())
        logger.info("Generating $limitedCombinations combinations (out of $totalCombinations possible)")

        val result = mutableListOf<Request>()
        val indices = IntArray(placeholderKeys.size) { 0 }
        var combinationCount = 0L

        while (combinationCount < limitedCombinations) {
            val combination = placeholderKeys.withIndex().associate { (i, key) ->
                val values = placeholderValues[key] ?: emptyList<Any>()
                if (values.isEmpty()) {
                    key to ""
                } else {
                    key to values[indices[i]]
                }
            }

            var content = template.content
            for ((placeholder, value) in combination) {
                content = content.replace("{{$placeholder}}", value.toString())
            }

            result.add(Request(UUID.randomUUID().toString(), content, systemInstruction))
            combinationCount++

            var incrementIndex = placeholderKeys.size - 1
            while (incrementIndex >= 0) {
                val valueList = placeholderValues[placeholderKeys[incrementIndex]] ?: emptyList<Any>()
                val size = valueList.size.coerceAtLeast(1)

                indices[incrementIndex] = (indices[incrementIndex] + 1) % size

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

    private fun processPlaceholderValues(
        placeholders: Set<String>,
        data: Map<String, Any>
    ): MutableMap<String, List<Any>> {
        val result = mutableMapOf<String, List<Any>>()

        for (placeholder in placeholders) {
            if (data.containsKey(placeholder)) {
                val value = data[placeholder]
                when (value) {
                    is List<*> -> result[placeholder] = value.filterNotNull().ifEmpty { listOf("") }
                    is String -> {
                        result[placeholder] = parseStringValue(value).ifEmpty { listOf("") }
                    }
                    else -> result[placeholder] = listOf(value!!)
                }
            }
        }

        return result
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