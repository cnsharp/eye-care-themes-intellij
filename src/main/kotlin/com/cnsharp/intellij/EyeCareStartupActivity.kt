package com.cnsharp.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Startup hook. `postStartupActivity` fires reliably on 2026.2 — including when
 * only the welcome screen is shown (no project open) — and performs the same work
 * the old `ApplicationInitializedListener` did. It installs the LaF-change listener
 * and re-applies the persisted custom color (or defaults to green on first run),
 * so the welcome screen is tinted before the user opens a project. Registered via
 * the `postStartupActivity` EP in plugin.xml.
 *
 * NOTE: the ApplicationInitializedListener EP is deliberately avoided — on 2026.2
 * that interface requires overriding `execute()` (a suspend fun), which cannot be
 * compiled against the 2022.2 SDK we build with, and the platform throws
 * PluginException("Override execute") at startup.
 *
 * Implements [Disposable] so the LaF listener connection (parented to this
 * instance in [runActivity]) is unsubscribed when the plugin is unloaded —
 * otherwise a dynamic reload would leak the listener and its references to this
 * plugin's classes.
 */
class EyeCareStartupActivity : StartupActivity, Disposable {
    override fun runActivity(project: Project) {
        LOG.warn("EyeCare: StartupActivity.runActivity fired (project=${project.name})")
        LafThemeHelper.installOverridesListener(this)
        LafThemeHelper.installToolWindowListener(project, this)
        runCatching { LafThemeHelper.ensureDefaultGreenApplied() }
        runCatching { LafThemeHelper.applyPersistedCustomTheme() }
        runCatching { LafThemeHelper.reapplyPresetEditorTint() }
    }

    override fun dispose() {
        // The LafManagerListener connection is parented to this disposable, so it
        // is unsubscribed automatically when the plugin is unloaded. Nothing else
        // to release here.
    }
}
