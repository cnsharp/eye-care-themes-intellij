package com.cnsharp.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level light service that serves as the parent [Disposable] for the
 * per-project [LafThemeHelper.installToolWindowListener] message-bus connection.
 *
 * The tool-window listener is subscribed on the project's own message bus, so it
 * must be parented to a *project-scoped* Disposable (not the app-level
 * [EyeCarePluginDisposable], which would outlive project close and leak the
 * connection). The platform disposes the project service when the project is
 * closed or the plugin is unloaded, which disconnects the bus connection and
 * releases its references to this plugin's classes.
 */
@Service(Service.Level.PROJECT)
class EyeCareProjectDisposable : Disposable {
    override fun dispose() {
        // Parented ToolWindowManagerListener connection is unsubscribed by the
        // platform when this project service is disposed. Nothing else to release.
    }
}
