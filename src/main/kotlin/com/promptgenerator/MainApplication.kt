package com.promptgenerator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.promptgenerator.config.ConfigLoader
import com.promptgenerator.di.appModule
import com.promptgenerator.ui.icons.AppIcons
import com.promptgenerator.ui.screen.MainScreen
import com.promptgenerator.ui.theme.PromptGeneratorTheme
import com.promptgenerator.ui.viewmodel.GeneratorViewModel
import com.promptgenerator.ui.viewmodel.HistoryViewModel
import com.promptgenerator.ui.viewmodel.MainViewModel
import com.promptgenerator.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    configureLogging()
    ConfigLoader.ensureConfigDirectoryExists()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Prompt Generator",
            state = WindowState(width = 1200.dp, height = 800.dp)
        ) {
            KoinApplication(application = {
                modules(appModule)
            }) {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    var showSplash by remember { mutableStateOf(true) }
    val mainViewModel = koinInject<MainViewModel>()
    val generatorViewModel = koinInject<GeneratorViewModel>()
    val historyViewModel = koinInject<HistoryViewModel>()
    val settingsViewModel = koinInject<SettingsViewModel>()

    PromptGeneratorTheme(darkTheme = mainViewModel.uiState.isDarkTheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !showSplash,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 500,
                        easing = LinearOutSlowInEasing
                    )
                )
            ) {
                MainScreen(
                    mainViewModel = mainViewModel,
                    generatorViewModel = generatorViewModel,
                    historyViewModel = historyViewModel,
                    settingsViewModel = settingsViewModel
                )
            }

            AnimatedVisibility(
                visible = showSplash,
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 500,
                        easing = FastOutSlowInEasing
                    )
                ) + scaleOut(
                    animationSpec = tween(
                        durationMillis = 500,
                        easing = FastOutSlowInEasing
                    ),
                    targetScale = 1.2f
                )
            ) {
                SplashScreen()
            }
        }

        LaunchedEffect(Unit) {
            delay(2000)
            showSplash = false
        }
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = AppIcons.Generate,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(80.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Prompt Generator",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(32.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 4.dp
            )
        }
    }
}

private fun configureLogging() {
    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    rootLogger.level = Level.INFO
}