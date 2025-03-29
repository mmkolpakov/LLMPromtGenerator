package com.promptgenerator.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings

/**
 * Collection of icons used throughout the application
 */
object AppIcons {
    // Navigation & Menu icons
    val Menu = Icons.Default.Menu
    val Settings = Icons.Default.Settings
    val SettingsOutlined = Icons.Outlined.Settings
    val Info = Icons.Default.Info
    val InfoOutlined = Icons.Outlined.Info
    val History = Icons.AutoMirrored.Filled.List  // Using List icon as a substitute for History

    // Action icons
    val Add = Icons.Default.Add
    val Edit = Icons.Default.Edit
    val Delete = Icons.Default.Delete
    val Close = Icons.Default.Close
    val Clear = Icons.Default.Clear
    val Save = Icons.Default.Check
    val Share = Icons.Default.Share
    val Copy = Icons.Default.ContentCopy
    val Check = Icons.Default.Check
    val Refresh = Icons.Default.Refresh

    // Toggle icons
    val ExpandMore = Icons.Default.KeyboardArrowDown
    val ExpandLess = Icons.Default.KeyboardArrowUp
    val MoreVert = Icons.Default.MoreVert

    // Themed icons
    val DarkMode = Icons.Default.DarkMode
    val LightMode = Icons.Outlined.LightMode

    // Feature icons
    val Generate = Icons.Default.AutoAwesome
    val Warning = Icons.Default.Warning
}