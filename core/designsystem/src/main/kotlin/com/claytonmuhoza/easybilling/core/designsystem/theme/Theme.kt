package com.claytonmuhoza.easybilling.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Material 3 theme of the application.
 *
 * The device's dynamic colours are deliberately not used: the application looks the same on every
 * phone, tablet and point-of-sale terminal.
 *
 * @param darkTheme whether to use the dark colour scheme; follows the system setting by default.
 * @param content the themed content.
 */
@Composable
fun EasyBillingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = EasyBillingTypography,
            shapes = EasyBillingShapes,
            content = content,
        )
    }
}
