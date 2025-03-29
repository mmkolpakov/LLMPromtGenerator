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
import kotlinx.coroutines.SupervisorJob

val appModule = module {
    single { ConfigLoader.loadLLMConfig() }
    single { SettingsManager() }

    single {
        val settings = get<SettingsManager>().getSettings()
        TemplateLocalDataSource(settings.saveTemplatesPath)
    }

    single {
        val settings = get<SettingsManager>().getSettings()
        ResultLocalDataSource(resultsDir = "results", maxCachedResults = settings.resultsLimit)
    }

    single { LLMService(get()) }

    single<TemplateRepository> { TemplateRepositoryImpl(get()) }
    single<RequestRepository> { RequestRepositoryImpl(get()) }
    single<ResultRepository> { ResultRepositoryImpl(get(), get()) }

    single { PromptGeneratorService(get(), get(), get()) }

    factory { ManageTemplatesUseCase(get()) }
    factory { ManageResultsUseCase(get()) }

    factory { MainViewModel(get()) }
    factory { GeneratorViewModel(get(), get(), get()) }
    factory { HistoryViewModel(get()) }
    factory { SettingsViewModel(get(), get()) }
}