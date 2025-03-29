package com.promptgenerator.di

import com.promptgenerator.config.ConfigLoader
import com.promptgenerator.config.SettingsManager
import com.promptgenerator.data.repository.RequestRepositoryImpl
import com.promptgenerator.data.repository.ResultRepositoryImpl
import com.promptgenerator.data.repository.TemplateRepositoryImpl
import com.promptgenerator.data.source.local.ResultLocalDataSource
import com.promptgenerator.data.source.local.TemplateLocalDataSource
import com.promptgenerator.data.source.remote.LLMService
import com.promptgenerator.domain.repository.RequestRepository
import com.promptgenerator.domain.repository.ResultRepository
import com.promptgenerator.domain.repository.TemplateRepository
import com.promptgenerator.domain.service.PromptGeneratorService
import com.promptgenerator.domain.usecase.ManageResultsUseCase
import com.promptgenerator.domain.usecase.ManageTemplatesUseCase
import com.promptgenerator.ui.viewmodel.GeneratorViewModel
import com.promptgenerator.ui.viewmodel.HistoryViewModel
import com.promptgenerator.ui.viewmodel.MainViewModel
import com.promptgenerator.ui.viewmodel.SettingsViewModel
import org.koin.dsl.module
import kotlinx.coroutines.Dispatchers

/**
 * Koin DI module for the application
 */
val appModule = module {
    // Configuration
    single { ConfigLoader.loadLLMConfig() }
    single { SettingsManager() }

    // Data sources
    single { TemplateLocalDataSource() }
    single { ResultLocalDataSource() }
    single { LLMService(get()) }

    // Repositories
    single<TemplateRepository> { TemplateRepositoryImpl(get()) }
    single<RequestRepository> { RequestRepositoryImpl(get()) }
    single<ResultRepository> { ResultRepositoryImpl(get(), get()) }

    // Services
    single { PromptGeneratorService(get(), get(), get()) }

    // Use cases
    single { ManageTemplatesUseCase(get()) }
    single { ManageResultsUseCase(get()) }

    single { MainViewModel(get()) }
    single { GeneratorViewModel(get(), get(), get()) }
    single { HistoryViewModel(get()) }
    single { SettingsViewModel(get(), get()) }
}