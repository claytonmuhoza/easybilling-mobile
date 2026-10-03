package com.claytonmuhoza.easybilling.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/** Type scale of the application: the Material 3 defaults, adjusted here and nowhere else. */
internal val EasyBillingTypography = Typography()

/**
 * @return this style with tabular figures: every digit has the same width, so amounts line up in
 * a column and do not move when they change.
 */
fun TextStyle.tabularNumbers(): TextStyle = copy(fontFeatureSettings = "tnum")
