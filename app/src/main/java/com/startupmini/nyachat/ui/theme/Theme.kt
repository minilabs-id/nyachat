package com.startupmini.nyachat.ui.theme

import android.app.Activity
import androidx.compose.animation.core.LocalMotionDurationScale
import androidx.compose.animation.core.MotionDurationScale
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    // Brand Nyachat: primary = hijau #007A49, secondary = biru #0066FF (nuansa
    // logo dua-bubble, tanpa merombak struktur tonal tema).
    primary = Color(0xFF007A49),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F0C8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF0066FF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E3FF),
    onSecondaryContainer = Color(0xFF001948),
    tertiary = Color(0xFFAD7000),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE0B6),
    onTertiaryContainer = Color(0xFF382200),
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDBE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBEC9C4),
    scrim = Color(0xFF000000)
)

private val DarkColorScheme = darkColorScheme(
    // Brand Nyachat dark: primary tone terang dari hijau #007A49 (dipisahkan dari
    // IncomeGreenDark supaya warna income tidak menyatu dengan primary), secondary
    // = tone terang biru #0066FF.
    primary = Color(0xFF6FE5AA),
    onPrimary = Color(0xFF00381F),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA7F2C4),   // Lebih terang untuk kontras
    secondary = Color(0xFFAAC7FF),
    onSecondary = Color(0xFF002E6B),
    secondaryContainer = Color(0xFF0047B3),
    onSecondaryContainer = Color(0xFFD6E3FF),
    tertiary = Color(0xFFE4BE7C),
    onTertiary = Color(0xFF3F2D00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFE0B6),
    background = Color(0xFF101414),           // Paling gelap — dasar layar
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF1C2120),              // Sedikit lebih terang — card, bottom sheet
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF2C3331),       // Input, segmented control bg
    onSurfaceVariant = Color(0xFFBEC9C4),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF88938E),
    outlineVariant = Color(0xFF3F4945),
    scrim = Color(0xFF000000)
)

/**
 * D-M1 — Material You / dynamic color sengaja DIMATIKAN di semua API level,
 * termasuk Android 12+ (API 31). Nyachat memakai palet brand hijau `#007A49` /
 * biru `#0066FF` sebagai identitas visual; mengikuti wallpaper akan merusak
 * kontras token semantik (income/expense/AI) yang sudah di-audit WCAG.
 *
 * Cabang `dynamic*ColorScheme` dan import terkait SUDAH DIHAPUS — tidak ada
 * jalur setengah jalan. Parameter [dynamicColor] tetap dipertahankan untuk
 * kompatibilitas sumber (nilai default `false`) tetapi sengaja tidak dibaca:
 * satu-satunya sumber kebenaran warna adalah [LightColorScheme] /
 * [DarkColorScheme] + [LocalSemanticColors]. Jangan mengaktifkan dynamic color
 * tanpa mengaudit ulang seluruh token kontras.
 */
const val USE_DYNAMIC_COLOR = false

@Composable
fun CoupleFinanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = USE_DYNAMIC_COLOR, // lihat D-M1 — brand-first, dinonaktifkan secara eksplisit
    content: @Composable () -> Unit
) {
    // D-M1: brand scheme dipilih langsung; tanpa dynamic color tidak ada percabangan
    // API-level yang tak pernah tercapai.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        @Suppress("DEPRECATION") // statusBarColor/navigationBarColor transparan utk edge-to-edge lintas API
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = {
            // Token warna semantik (income/expense/AI/dsb.) disediakan sekali di
            // sini — layar tidak perlu lagi mendeteksi mode gelap via luminance.
            CompositionLocalProvider(
                LocalSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
                // Audit R2-H8: scale 0 membuat SELURUH animasi Compose/M3
                // (sheet, dialog, dropdown, tooltip) snap saat "Hapus animasi"
                // aktif — tidak hanya animasi yang memakai Motion.reducedMotion.
                LocalMotionDurationScale provides object : MotionDurationScale {
                    override val scaleFactor: Float =
                        if (Motion.reducedMotion) 0f else 1f
                }
            ) {
                content()
            }
        }
    )
}
