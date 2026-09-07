package app.lia.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Lia's purple orb and cyan accent, the same palette as the desktop. */
private val Purple = Color(0xFF7C4DFF)
private val PurpleLight = Color(0xFFB388FF)
private val Cyan = Color(0xFF00E5FF)
private val Ink = Color(0xFF12121A)

private val DarkColors = darkColorScheme(
    primary = PurpleLight,
    secondary = Cyan,
    background = Ink,
    surface = Color(0xFF1B1B26),
)

private val LightColors = lightColorScheme(
    primary = Purple,
    secondary = Color(0xFF00838F),
    background = Color(0xFFFBFAFF),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun LiaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
