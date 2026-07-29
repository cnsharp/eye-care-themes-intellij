package com.cnsharp.intellij

import com.intellij.DynamicBundle

/**
 * Locale-aware message bundle. English lives in EyeCareBundle.properties;
 * Chinese in EyeCareBundle_zh(_CN).properties. IntelliJ's DynamicBundle
 * picks the right file based on the IDE locale, so the switcher menu is
 * shown in Chinese or English automatically.
 */
internal object EyeCareBundle : DynamicBundle("messages.EyeCareBundle") {
    fun message(key: String, vararg params: Any): String = getMessage(key, *params)
}
