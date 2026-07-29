package com.cnsharp.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import com.intellij.util.IconUtil
import java.awt.Color
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * The button itself: a color chip showing the currently active eye-care theme.
 * Clicking it opens a popup (shared via [buildEyeCarePopup]) listing all
 * eye-care color blocks plus a custom color picker.
 */
class EyeCareStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.IconPresentation {

    companion object {
        const val ID = "cnsharp.eyecare.switcher.widget"
    }

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getTooltipText(): String = EyeCareBundle.message("eye.care.tooltip")

    // Status-bar icons should be 13x13 (the standard slot size); the 512x512
    // SVG source is scaled down here so it never renders oversized at 16x16.
    override fun getIcon(): Icon {
        val base = eyeIcon(darken(currentColor(), 0.5f))
        return IconUtil.scale(base, null, 13.0f / base.iconWidth)
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { e ->
        buildEyeCarePopup(project) { statusBar?.updateWidget(ID()) }.showUnderneathOf(e.component)
    }

    /** Loads the minimalist eye SVG and recolors it to [color]. */
    private fun eyeIcon(color: Color): Icon =
        IconUtil.colorize(
            IconLoader.getIcon("/icons/eyecare.svg", EyeCareStatusBarWidget::class.java),
            color
        )

    /** Scales an AWT color toward black by [factor] (1 = unchanged, 0 = black). */
    private fun darken(c: Color, factor: Float): Color {
        val f = factor.coerceIn(0f, 1f)
        return Color(
            (c.red * f).toInt().coerceIn(0, 255),
            (c.green * f).toInt().coerceIn(0, 255),
            (c.blue * f).toInt().coerceIn(0, 255),
        )
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }
}
