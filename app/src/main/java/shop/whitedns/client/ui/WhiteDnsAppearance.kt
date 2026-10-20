package shop.whitedns.client.ui

import androidx.compose.ui.graphics.Color

/** Shared brand roles for scanner, tunnel UI, navigation and system bars. */
internal fun whiteDnsBrandPalette(dark: Boolean, accent: String): WhiteDnsPaletteColors {
    val base = if (dark) WhiteDnsPaletteDark else WhiteDnsPaletteLight
    val primary = when (accent) {
        "teal" -> if (dark) Color(0xFF75D9C4) else Color(0xFF006B5D)
        "milk" -> if (dark) Color(0xFFE5CDAF) else Color(0xFF775732)
        else -> if (dark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
    }
    val pressed = when (accent) {
        "teal" -> if (dark) Color(0xFF5EC4AF) else Color(0xFF005345)
        "milk" -> if (dark) Color(0xFFD0B795) else Color(0xFF573E22)
        else -> if (dark) Color(0xFFB69DF8) else Color(0xFF4F378B)
    }
    val container = when (accent) {
        "teal" -> if (dark) Color(0xFF103D37) else Color(0xFFD4F1EA)
        "milk" -> if (dark) Color(0xFF3E2F20) else Color(0xFFF1E4D4)
        else -> if (dark) Color(0xFF2B203E) else Color(0xFFEDE4FA)
    }
    return object : WhiteDnsPaletteColors by base {
        override val Accent = primary
        override val AccentText = primary
        override val AccentPressed = pressed
        override val AccentDim = pressed
        override val AccentSurface = container
        override val OnAccent = if (dark) Color(0xFF20192E) else Color.White
        override val Error = if (dark) base.Error else Color(0xFFB3261E)
        override val Success = if (dark) base.Success else Color(0xFF007D55)
        override val Warning = if (dark) base.Warning else Color(0xFF885000)
        override val WarningText = if (dark) base.WarningText else Color(0xFF885000)
        override val Placeholder = if (dark) Color(0xFF9FA6C0) else Color(0xFF6B7280)
    }
}
