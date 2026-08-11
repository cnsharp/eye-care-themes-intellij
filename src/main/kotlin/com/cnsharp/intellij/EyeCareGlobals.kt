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

/**
 * Run [block] on the EDT and return its result synchronously. If already on the
 * EDT, runs inline; otherwise uses [ApplicationManager.getApplication].invokeAndWait
 * so the caller gets the result back. Needed because LaF switches
 * (setCurrentUIThemeLookAndFeel) are EDT-only, but [applyEyeCareTheme] can be
 * reached from a background startup thread (a non-DumbAware postStartupActivity).
 */
internal inline fun <T> runOnEdtSync(crossinline block: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return block()
    var result: T? = null
    var error: Throwable? = null
    app.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            error = t
        }
    }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
