package com.cmux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

// cmux dark theme colors
object CmuxColors {
    val background = Color(0xFF1A1B26)       // Main background (Tokyo Night)
    val surface = Color(0xFF16161E)          // Sidebar / panels
    val surfaceVariant = Color(0xFF1F2028)   // Active tab / hover
    val primary = Color(0xFF7AA2F7)          // Accent blue
    val secondary = Color(0xFF9ECE6A)        // Green accent
    val error = Color(0xFFF7768E)            // Red/error
    val warning = Color(0xFFE0AF68)          // Yellow/warning
    val onBackground = Color(0xFFA9B1D6)     // Primary text
    val onSurface = Color(0xFF565F89)        // Dimmed text
    val onSurfaceVariant = Color(0xFF787C99) // Secondary text
    val border = Color(0xFF292E42)           // Borders
    val terminalBg = Color(0xFF1A1B26)       // Terminal background
    val terminalFg = Color(0xFFC0CAF5)       // Terminal foreground

    // Notification ring colors
    val notifInfo = Color(0xFF7AA2F7)
    val notifSuccess = Color(0xFF9ECE6A)
    val notifWarning = Color(0xFFE0AF68)
    val notifError = Color(0xFFF7768E)
}

private val DarkColorScheme = darkColorScheme(
    primary = CmuxColors.primary,
    secondary = CmuxColors.secondary,
    background = CmuxColors.background,
    surface = CmuxColors.surface,
    surfaceVariant = CmuxColors.surfaceVariant,
    error = CmuxColors.error,
    onBackground = CmuxColors.onBackground,
    onSurface = CmuxColors.onSurface,
    onSurfaceVariant = CmuxColors.onSurfaceVariant,
)

@Composable
fun CmuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

object CmuxTypography {
    val monospace: FontFamily = FontFamily.Monospace
    val terminalFontSize = 14.sp
    val sidebarFontSize = 12.sp
    val titleFontSize = 11.sp
}
