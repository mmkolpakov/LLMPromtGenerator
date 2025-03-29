package com.promptgenerator.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promptgenerator.domain.model.GenerationResult
import com.promptgenerator.domain.usecase.ManageResultsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable

class HistoryViewModel(
    private val manageResultsUseCase: ManageResultsUseCase
) : Closeable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // UI state
    var uiState by mutableStateOf(HistoryUiState())
        private set

    init {
        loadResults()
    }

    /**
     * Loads generation results
     */
    private fun loadResults() {
        viewModelScope.launch {
            manageResultsUseCase.getAllResults().collectLatest { results ->
                uiState = uiState.copy(
                    results = results,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Views result details
     */
    fun viewResultDetails(resultId: String) {
        viewModelScope.launch {
            uiState = uiState.copy(
                isLoadingDetails = true,
                currentResultId = resultId,
                showDetailsDialog = true
            )

            manageResultsUseCase.getResult(resultId).onSuccess { result ->
                uiState = uiState.copy(
                    currentResult = result,
                    isLoadingDetails = false
                )
            }.onFailure { error ->
                uiState = uiState.copy(
                    isLoadingDetails = false,
                    errorMessage = "Failed to load result details: ${error.message}"
                )
            }
        }
    }

    /**
     * Closes result details dialog
     */
    fun closeDetailsDialog() {
        uiState = uiState.copy(
            showDetailsDialog = false,
            currentResultId = "",
            currentResult = null
        )
    }

    /**
     * Exports result to files
     */
    fun exportResult(result: GenerationResult, directory: String = "generated_prompts") {
        viewModelScope.launch {
            try {
                manageResultsUseCase.exportResults(result, directory)
                    .onSuccess { files ->
                        uiState = uiState.copy(
                            successMessage = "Exported ${files.size} files to $directory"
                        )
                    }
                    .onFailure { error ->
                        uiState = uiState.copy(
                            errorMessage = "Failed to export result: ${error.message}"
                        )
                    }
            } catch (e: Exception) {
                logger.error("Error exporting result", e)
                uiState = uiState.copy(
                    errorMessage = "Error exporting result: ${e.message}"
                )
            }
        }
    }

    /**
     * Deletes a result
     */
    fun deleteResult(resultId: String) {
        viewModelScope.launch {
            try {
                manageResultsUseCase.deleteResult(resultId)
                    .onSuccess { success ->
                        if (success) {
                            // Result will be removed from the flow and UI updated automatically
                            uiState = uiState.copy(
                                successMessage = "Result deleted successfully",
                                showDetailsDialog = false,
                                currentResultId = "",
                                currentResult = null
                            )
                        } else {
                            uiState = uiState.copy(
                                errorMessage = "Failed to delete result"
                            )
                        }
                    }
                    .onFailure { error ->
                        uiState = uiState.copy(
                            errorMessage = "Failed to delete result: ${error.message}"
                        )
                    }
            } catch (e: Exception) {
                logger.error("Error deleting result", e)
                uiState = uiState.copy(
                    errorMessage = "Error deleting result: ${e.message}"
                )
            }
        }
    }

    /**
     * Clears error or success message
     */
    fun clearMessage() {
        uiState = uiState.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    /**
     * Releases resources
     */
    override fun close() {
        // No resources to release
    }
}

/**
 * UI state for history screen
 */
data class HistoryUiState(
    val results: List<GenerationResult> = emptyList(),
    val isLoading: Boolean = true,

    val showDetailsDialog: Boolean = false,
    val currentResultId: String = "",
    val currentResult: GenerationResult? = null,
    val isLoadingDetails: Boolean = false,

    val errorMessage: String? = null,
    val successMessage: String? = null
)