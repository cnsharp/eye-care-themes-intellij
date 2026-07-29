package com.cnsharp.intellij

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.IconLoader
import java.awt.Component

/**
 * Standalone action so the switcher is reachable even when the status-bar chip
 * is hidden: it shows in the View menu and is findable via Find Action
 * (Ctrl/Cmd+Shift+A) by typing "Eye Care".
 */
class EyeCareSwitcherAction : AnAction() {
    init {
        templatePresentation.text = EyeCareBundle.message("eye.care.popup.title")
        templatePresentation.description = EyeCareBundle.message("eye.care.tooltip")
        templatePresentation.icon =
            IconLoader.getIcon("/META-INF/pluginIcon.svg", EyeCareSwitcherAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val popup: JBPopup = buildEyeCarePopup(project) {}
        val component: Component? = e.inputEvent?.component
        if (component != null) popup.showUnderneathOf(component) else popup.showInFocusCenter()
    }
}
