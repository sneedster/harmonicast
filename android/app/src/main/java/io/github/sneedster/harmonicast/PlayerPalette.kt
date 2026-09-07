package io.github.sneedster.harmonicast

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** All browse components use these semantic roles, not palette-specific literals. */
enum class PlayerPalette { Nocturne, Aurora, Ember }
fun playerColors(name: String) = when (name) {
    "Aurora" -> darkColorScheme(primary = Color(0xffa5ebd0), onPrimary = Color(0xff0b3227),
        background = Color(0xff080f12), surface = Color(0xff101c20), surfaceVariant = Color(0xff223536),
        secondaryContainer = Color(0xff24453c), onSecondaryContainer = Color(0xffc9f9e7),
        onBackground = Color(0xffeef8f5), onSurface = Color(0xffeef8f5), onSurfaceVariant = Color(0xffa8bcb6))
    "Ember" -> darkColorScheme(primary = Color(0xffffbd9e), onPrimary = Color(0xff48200e),
        background = Color(0xff130c0b), surface = Color(0xff211614), surfaceVariant = Color(0xff3b2924),
        secondaryContainer = Color(0xff53362b), onSecondaryContainer = Color(0xffffdbc9),
        onBackground = Color(0xfffff2eb), onSurface = Color(0xfffff2eb), onSurfaceVariant = Color(0xffc8aea3))
    else -> darkColorScheme(primary = Color(0xffbfa7f5), onPrimary = Color(0xff251442),
        background = Color(0xff090b14), surface = Color(0xff121421), surfaceVariant = Color(0xff25273d),
        secondaryContainer = Color(0xff342a4f), onSecondaryContainer = Color(0xffeaddff),
        onBackground = Color(0xfff1eef8), onSurface = Color(0xfff1eef8), onSurfaceVariant = Color(0xffaaa8bc))
}
