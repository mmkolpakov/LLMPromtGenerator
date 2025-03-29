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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.Closeable

class HistoryViewModel(
    private val manageResultsUseCase: ManageResultsUseCase
) : Closeable {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resultsJob: Job? = null

    var uiState by mutableStateOf(HistoryUiState())
        private set

    init {
        loadResults()
    }

    private fun loadResults() {
        resultsJob?.cancel()
        resultsJob = viewModelScope.launch {
            uiState = uiState.copy(isLoading = true)

            try {
                manageResultsUseCase.getAllResults()
                    .catch { e ->
                        logger.error("Error loading results", e)
                        uiState = uiState.copy(
                            isLoading = false,
                            errorMessage = "Failed to load results: ${e.message}"
                        )
                    }
                    .collectLatest { results ->
                        uiState = uiState.copy(
                            results = results,
                            isLoading = false
                        )
                    }
            } catch (e: Exception) {
                logger.error("Error starting results flow", e)
                uiState = uiState.copy(
                    isLoading = false,
                    errorMessage = "Failed to load results: ${e.message}"
                )
            }
        }
    }

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

    fun closeDetailsDialog() {
        uiState = uiState.copy(
            showDetailsDialog = false,
            currentResultId = "",
            currentResult = null
        )
    }

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

    fun deleteResult(resultId: String) {
        viewModelScope.launch {
            try {
                manageResultsUseCase.deleteResult(resultId)
                    .onSuccess { success ->
                        if (success) {
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

    fun clearMessage() {
        uiState = uiState.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    override fun close() {
        resultsJob?.cancel()
        viewModelScope.cancel()
    }
}

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