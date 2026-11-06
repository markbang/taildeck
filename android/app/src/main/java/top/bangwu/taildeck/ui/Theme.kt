package top.bangwu.taildeck.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val OnlineGreen = Color(0xFF2E9E5B)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD3A8),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF1E5133),
    onPrimaryContainer = Color(0xFFAAF0C2),
    background = Color(0xFF101214),
    surface = Color(0xFF101214),
    surfaceContainer = Color(0xFF1A1D20),
    surfaceContainerHigh = Color(0xFF22262A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1E6B41),
    primaryContainer = Color(0xFFAAF0C2),
    onPrimaryContainer = Color(0xFF00210F),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFF8FAF7),
)

@Composable
fun TaildeckTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** Shared motion tokens (Material 3 emphasized easing). */
object Motion {
    private val emphasized = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> slow() = androidx.compose.animation.core.tween<T>(350, easing = emphasized)
    fun <T> fast() = androidx.compose.animation.core.tween<T>(200, easing = emphasized)
}
