package com.cnsharp.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColorPicker
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.picker.ColorListener
import com.intellij.util.ui.ColorIcon
import java.awt.Color
import java.awt.Component
import java.lang.reflect.Proxy

/**
 * Shared popup contents: the five eye-care themes plus a "Custom…" entry that
 * opens the platform color picker and applies the chosen color as a runtime
 * UITheme (UI chrome + editor background tint). Reachable both from the
 * status-bar chip ([EyeCareStatusBarWidget]) and the standalone
 * [EyeCareSwitcherAction].
 *
 * @param onApply called after any selection so callers can refresh their UI
 *   (e.g. the status-bar chip icon).
 */
internal fun buildEyeCarePopup(project: Project, onApply: () -> Unit): JBPopup {
    val group = DefaultActionGroup().apply {
        THEMES.forEach { theme ->
            add(object : AnAction(theme.name, "=> ${theme.name}", ColorIcon(13, theme.color)) {
                override fun actionPerformed(e: AnActionEvent) {
                    LafThemeHelper.applyEyeCareTheme(theme.id)
                    onApply()
                }
            })
        }
        add(object : AnAction(
            EyeCareBundle.message("custom.name"),
            EyeCareBundle.message("custom.description"),
            ColorIcon(13, currentColor())
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                // Use the captured project from buildEyeCarePopup: the popup is
                // created with DataContext.EMPTY_CONTEXT, so e.project is null
                // here and would silently abort the picker.
                showColorPicker(project, currentColor(), e.inputEvent?.component) { color ->
                    val hex = "#%06X".format(0xFFFFFF and color.rgb)
                    EyeCareCustomTheme.setCustomTheme(hex)
                    onApply()
                }
            }
        })
    }
    return JBPopupFactory.getInstance()
        .createActionGroupPopup(
            EyeCareBundle.message("eye.care.popup.title"),
            group,
            DataContext.EMPTY_CONTEXT,
            ActionSelectionAid.SPEEDSEARCH,
            true,
        )
}

/**
 * Opens the platform color picker and reports the chosen color via [onColor].
 *
 * Primary path: the 5-arg `showColorPickerPopup(Project, Color, ColorListener,
 * RelativePoint, boolean)` is declared in the 2022.2 SDK we build against and is
 * also present on 2026.2, so we call it **directly**. A failure there (e.g. a
 * NoSuchMethodError on some other IDE build whose runtime signature differs)
 * falls back to a reflective call to the same method, and finally to a blocking
 * `showDialog`. Every branch is logged to `EyeCareThemeSwitcher` so a silent
 * miss is diagnosable.
 */
private fun showColorPicker(
    project: Project,
    initial: Color,
    component: Component?,
    onColor: (Color) -> Unit,
) {
    val log = Logger.getInstance("EyeCareThemeSwitcher")

    // 1) Direct 5-arg call (compiles against 2022.2 SDK; present on 2026.2 too).
    try {
        val parent = component ?: WindowManager.getInstance().getFrame(project)
        val rp = if (parent != null) {
            RelativePoint(parent, java.awt.Point(0, 0))
        } else {
            RelativePoint(java.awt.Point(0, 0))
        }
        val listener = ColorListener { color, _ -> onColor(color) }
        ColorPicker.showColorPickerPopup(project, initial, listener, rp, false)
        log.warn("EyeCare: color picker popup shown (5-arg direct)")
        return
    } catch (e: Throwable) {
        log.warn("EyeCare: direct 5-arg showColorPickerPopup failed, trying reflection", e)
    }

    // 2) Reflective 5-arg call, for IDE builds whose runtime signature differs.
    val popupOk = runCatching {
        val cpClass = Class.forName("com.intellij.ui.ColorPicker")
        val listenerClass = Class.forName("com.intellij.ui.picker.ColorListener")
        val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
            if (method != null && method.name == "colorChanged") {
                (args?.firstOrNull() as? Color)?.let(onColor)
            }
            null
        }
        val rpClass = Class.forName("com.intellij.ui.awt.RelativePoint")
        val parent = component ?: WindowManager.getInstance().getFrame(project)
        val rp = if (parent != null) {
            rpClass.getConstructor(java.awt.Component::class.java, java.awt.Point::class.java)
                .newInstance(parent, java.awt.Point(0, 0))
        } else null
        cpClass.getMethod(
            "showColorPickerPopup",
            Project::class.java, Color::class.java, listenerClass, rpClass, Boolean::class.javaPrimitiveType,
        ).invoke(null, project, initial, proxy, rp, false)
        true
    }.getOrDefault(false)
    if (popupOk) {
        log.warn("EyeCare: color picker popup shown (5-arg reflection)")
        return
    }

    // 3) Blocking dialog fallback that returns the chosen Color directly.
    runCatching {
        val cpClass = Class.forName("com.intellij.ui.ColorPicker")
        val dlg = cpClass.getMethod(
            "showDialog",
            java.awt.Component::class.java, String::class.java, Color::class.java,
            Boolean::class.javaPrimitiveType, List::class.java, Boolean::class.javaPrimitiveType,
        )
        val parent = component ?: WindowManager.getInstance().getFrame(project)
        val c = dlg.invoke(null, parent, "Eye Care Custom Color", initial, false, mutableListOf<Color>(), false) as? Color
        if (c != null) onColor(c)
    }.onFailure { log.warn("EyeCare: color picker fallback (showDialog) failed", it) }
}
