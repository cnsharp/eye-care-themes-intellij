package com.cnsharp.intellij

import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

// Shared reflection handles for the platform LafManager. Both the preset
// switcher (LafThemeHelper) and the runtime custom-theme builder
// (EyeCareCustomTheme) drive LafManager reflectively, so the handles live at
// file scope.
internal val lafClass = LafManager::class.java
internal val lafInstance: Any? = try {
    lafClass.getMethod("getInstance").invoke(null)
} catch (_: Throwable) {
    null
}

/** Shared logger for the eye-care theme switcher. */
internal val LOG = Logger.getInstance("EyeCareThemeSwitcher")

/** Run [action] on the EDT, or immediately if already on it. */
internal fun runOnEdt(action: () -> Unit) {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) action() else app.invokeLater(action)
}
