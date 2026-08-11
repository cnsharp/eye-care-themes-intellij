package com.cnsharp.intellij

import com.intellij.openapi.application.ApplicationManager
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
 * The message-bus connections this installs are parented to the plugin's own
 * light services ([EyeCarePluginDisposable] for the app-level LaF listener,
 * [EyeCareProjectDisposable] for the per-project tool-window listener), NOT to
 * this activity. Those services sit under the plugin's Disposer subtree, so the
 * platform disposes them — and therefore unsubscribes the connections — on
 * plugin unload, which frees the class loader. Parenting to the activity itself
 * would make it a permanent Disposer root and block clean dynamic unload.
 */
class EyeCareStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        LOG.warn("EyeCare: StartupActivity.runActivity fired (project=${project.name})")
        val appParent = ApplicationManager.getApplication().getService(EyeCarePluginDisposable::class.java)
        LafThemeHelper.installOverridesListener(appParent)
        val projectParent = project.getService(EyeCareProjectDisposable::class.java)
        LafThemeHelper.installToolWindowListener(project, projectParent)
        runCatching { LafThemeHelper.ensureDefaultGreenApplied() }
        runCatching { LafThemeHelper.applyPersistedCustomTheme() }
        runCatching { LafThemeHelper.reapplyPresetEditorTint() }
    }
}
