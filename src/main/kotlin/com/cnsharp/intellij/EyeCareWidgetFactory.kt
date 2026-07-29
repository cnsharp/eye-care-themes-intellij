package com.cnsharp.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Registers the eye-care switcher as a status-bar widget via the platform's
 * StatusBarWidgetFactory. The platform instantiates the widget and calls
 * install(statusBar) with the real status bar, which is cross-product safe
 * (e.g. Rider, where WindowManager.getStatusBar(project) isn't reliable at
 * startup). isEnabledByDefault()=true so the chip shows without manual enable.
 */
class EyeCareWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = EyeCareStatusBarWidget.ID
    override fun getDisplayName(): String = "Eye Care Theme Switcher"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget {
        // The LaF-change listener is installed once by EyeCareStartupActivity
        // (parented to its Disposable so it is torn down on plugin unload). Here we
        // just re-apply any persisted eye-care choice after a restart. The custom
        // color/preset tint is persisted (see LafThemeHelper), so this restores the
        // choice instead of leaving the IDE on a dark fallback theme. Wrapped so a
        // failure can never prevent the status-bar chip from appearing.
        runCatching { LafThemeHelper.applyPersistedCustomTheme() }
        runCatching { LafThemeHelper.reapplyPresetEditorTint() }
        return EyeCareStatusBarWidget(project)
    }
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
    // Must be true so the chip appears without the user enabling it manually.
    override fun isEnabledByDefault(): Boolean = true
}