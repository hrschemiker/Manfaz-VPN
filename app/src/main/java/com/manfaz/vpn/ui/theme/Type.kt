package com.manfaz.vpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.manfaz.vpn.R

val IranSans = FontFamily(
    Font(R.font.iransans_light, FontWeight.Light),
    Font(R.font.iransans_regular, FontWeight.Normal),
    Font(R.font.iransans_medium, FontWeight.Medium),
    Font(R.font.iransans_bold, FontWeight.Bold),
    Font(R.font.iransans_black, FontWeight.Black),
)

private fun styled(base: TextStyle) = base.copy(fontFamily = IranSans)

val AppTypography = Typography().run {
    Typography(
        displayLarge = styled(displayLarge),
        displayMedium = styled(displayMedium),
        displaySmall = styled(displaySmall),
        headlineLarge = styled(headlineLarge),
        headlineMedium = styled(headlineMedium),
        headlineSmall = styled(headlineSmall),
        titleLarge = styled(titleLarge),
        titleMedium = styled(titleMedium),
        titleSmall = styled(titleSmall),
        bodyLarge = styled(bodyLarge),
        bodyMedium = styled(bodyMedium),
        bodySmall = styled(bodySmall),
        labelLarge = styled(labelLarge),
        labelMedium = styled(labelMedium),
        labelSmall = styled(labelSmall),
    )
}
