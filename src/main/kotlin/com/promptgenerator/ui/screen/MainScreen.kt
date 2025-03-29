package com.promptgenerator.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promptgenerator.ui.components.CustomTooltip
import com.promptgenerator.ui.icons.AppIcons
import com.promptgenerator.ui.viewmodel.GeneratorViewModel
import com.promptgenerator.ui.viewmodel.HistoryViewModel
import com.promptgenerator.ui.viewmodel.MainViewModel
import com.promptgenerator.ui.viewmodel.Screen
import com.promptgenerator.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Main screen that contains app scaffold and navigation
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    generatorViewModel: GeneratorViewModel,
    historyViewModel: HistoryViewModel,
    settingsViewModel: SettingsViewModel
) {
    val uiState = mainViewModel.uiState
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Box(
                    modifier = Modifier
                        .padding(16.dp)
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "Prompt Generator",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Navigation items
                NavigationItem(
                    icon = AppIcons.Generate,
                    label = "Generator",
                    selected = uiState.currentScreen == Screen.Generator,
                    onClick = {
                        mainViewModel.navigateTo(Screen.Generator)
                        scope.launch { drawerState.close() }
                    }
                )

                NavigationItem(
                    icon = AppIcons.History,
                    label = "History",
                    selected = uiState.currentScreen == Screen.History,
                    onClick = {
                        mainViewModel.navigateTo(Screen.History)
                        scope.launch { drawerState.close() }
                    }
                )

                NavigationItem(
                    icon = AppIcons.Settings,
                    label = "Settings",
                    selected = uiState.currentScreen == Screen.Settings,
                    onClick = {
                        mainViewModel.navigateTo(Screen.Settings)
                        scope.launch { drawerState.close() }
                    }
                )

                NavigationItem(
                    icon = AppIcons.Info,
                    label = "About",
                    selected = uiState.currentScreen == Screen.About,
                    onClick = {
                        mainViewModel.navigateTo(Screen.About)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = when (uiState.currentScreen) {
                                Screen.Generator -> "Prompt Generator"
                                Screen.History -> "Generation History"
                                Screen.Settings -> "Settings"
                                Screen.About -> "About"
                            }
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    navigationIcon = {
                        CustomTooltip(
                            tooltip = {
                                Text(
                                    "Open menu",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        ) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } }
                            ) {
                                Icon(
                                    imageVector = AppIcons.Menu,
                                    contentDescription = "Menu"
                                )
                            }
                        }
                    },
                    actions = {
                        // Dark/Light mode toggle with tooltip
                        CustomTooltip(
                            tooltip = {
                                Text(
                                    text = if (uiState.isDarkTheme) "Switch to light theme" else "Switch to dark theme",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        ) {
                            IconButton(onClick = { mainViewModel.setDarkTheme(!uiState.isDarkTheme) }) {
                                Icon(
                                    imageVector = if (uiState.isDarkTheme) {
                                        AppIcons.LightMode
                                    } else {
                                        AppIcons.DarkMode
                                    },
                                    contentDescription = "Toggle Theme"
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                color = MaterialTheme.colorScheme.background
            ) {
                // Display current screen with animation
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = uiState.currentScreen == Screen.Generator,
                        enter = fadeIn() + slideInHorizontally(),
                        exit = fadeOut() + slideOutHorizontally()
                    ) {
                        GeneratorScreen(
                            viewModel = generatorViewModel
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.currentScreen == Screen.History,
                        enter = fadeIn() + slideInHorizontally(),
                        exit = fadeOut() + slideOutHorizontally()
                    ) {
                        HistoryScreen(
                            viewModel = historyViewModel,
                            onLoadTemplate = { templateId ->
                                generatorViewModel.loadTemplate(templateId)
                                mainViewModel.navigateTo(Screen.Generator)
                            }
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.currentScreen == Screen.Settings,
                        enter = fadeIn() + slideInHorizontally(),
                        exit = fadeOut() + slideOutHorizontally()
                    ) {
                        SettingsScreen(
                            viewModel = settingsViewModel
                        )
                    }

                    AnimatedVisibility(
                        visible = uiState.currentScreen == Screen.About,
                        enter = fadeIn() + slideInHorizontally(),
                        exit = fadeOut() + slideOutHorizontally()
                    ) {
                        AboutScreen()
                    }
                }
            }
        }
    }
}

/**
 * Navigation drawer item
 */
@Composable
private fun NavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}
