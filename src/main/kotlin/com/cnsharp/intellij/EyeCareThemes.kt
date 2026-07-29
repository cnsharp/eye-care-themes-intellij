package com.cnsharp.intellij

import java.awt.Color

// --- Shared theme model (presets). The status-bar widget, the standalone
//     action and LafThemeHelper all build from this single model. ---

internal const val GREEN_ID = "8b7f9bbc-547d-4f14-8bb7-5d54db73737a"

internal data class EyeCareTheme(val id: String, val name: String, val color: Color)

internal val THEMES = listOf(
    EyeCareTheme(GREEN_ID, EyeCareBundle.message("theme.green"), Color(0xC7EDCC)),
    EyeCareTheme("cnsharp.eyecare.yellow", EyeCareBundle.message("theme.yellow"), Color(0xFAF9DE)),
    EyeCareTheme("cnsharp.eyecare.pink", EyeCareBundle.message("theme.pink"), Color(0xFDE6E0)),
    EyeCareTheme("cnsharp.eyecare.blue", EyeCareBundle.message("theme.blue"), Color(0xDCEAF5)),
    EyeCareTheme("cnsharp.eyecare.lavender", EyeCareBundle.message("theme.lavender"), Color(0xE6E6FA)),
)

/** All eye-care theme ids (presets + the runtime custom id), for "is this an
 *  eye-care theme already?" checks. */
internal val EYECARE_THEME_IDS = THEMES.map { it.id }.toSet() + EyeCareCustomTheme.CUSTOM_ID

/** Currently active eye-care color, resolved from the live LafManager. */
internal fun currentColor(): Color {
    val id = LafThemeHelper.currentThemeId()
    if (id == EyeCareCustomTheme.CUSTOM_ID) {
        return EyeCareCustomTheme.customColor()?.let { runCatching { Color.decode(it) }.getOrNull() }
            ?: THEMES.first().color
    }
    return THEMES.firstOrNull { it.id == id }?.color ?: THEMES.first().color
}
