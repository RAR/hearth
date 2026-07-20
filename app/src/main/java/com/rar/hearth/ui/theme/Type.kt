@file:OptIn(ExperimentalTextApi::class)

package com.rar.hearth.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.rar.hearth.R

/** Nunito as a single variable font; three weights via the wght axis (API 26+; device is API 30). */
val NunitoFamily = FontFamily(
    Font(R.font.nunito_variable, FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.nunito_variable, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.nunito_variable, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val base = Typography()

/** Material3 typography with every style switched to Nunito. */
val HearthTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = NunitoFamily),
    displayMedium = base.displayMedium.copy(fontFamily = NunitoFamily),
    displaySmall = base.displaySmall.copy(fontFamily = NunitoFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = NunitoFamily),
    headlineMedium = base.headlineMedium.copy(fontFamily = NunitoFamily),
    headlineSmall = base.headlineSmall.copy(fontFamily = NunitoFamily),
    titleLarge = base.titleLarge.copy(fontFamily = NunitoFamily),
    titleMedium = base.titleMedium.copy(fontFamily = NunitoFamily),
    titleSmall = base.titleSmall.copy(fontFamily = NunitoFamily),
    bodyLarge = base.bodyLarge.copy(fontFamily = NunitoFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = NunitoFamily),
    bodySmall = base.bodySmall.copy(fontFamily = NunitoFamily),
    labelLarge = base.labelLarge.copy(fontFamily = NunitoFamily),
    labelMedium = base.labelMedium.copy(fontFamily = NunitoFamily),
    labelSmall = base.labelSmall.copy(fontFamily = NunitoFamily),
)
