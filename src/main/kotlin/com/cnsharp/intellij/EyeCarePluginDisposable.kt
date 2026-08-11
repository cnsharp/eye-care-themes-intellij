package com.cnsharp.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * Application-level light service that serves as the parent [Disposable] for
 * resources that must live for the plugin's entire lifetime: the
 * [LafThemeHelper] message-bus connection ([installOverridesListener]).
 *
 * Why a service instead of the [EyeCareStartupActivity] instance: registering a
 * [com.intellij.openapi.Disposable] as the parent of a message-bus connection
 * adds it to the platform Disposer tree. If that Disposable is *not* itself
 * parented to a node the platform disposes on plugin unload, it becomes a
 * permanent Disposer root and keeps the bus subscription (whose proxy
 * invocation-handler is loaded by this plugin's class loader) alive — which
 * holds the PluginClassLoader and blocks clean dynamic unload.
 *
 * An application-level service is the right parent: the platform registers it
 * under the plugin's own Disposer subtree and disposes it automatically when
 * the plugin is unloaded, which unsubscribes the connection and frees the class
 * loader. Per the JetBrains Disposer guide, never parent such resources to the
 * Application/Project directly — use a dedicated service.
 *
 * See also: [EyeCareProjectDisposable] for the per-project connection.
 */
@Service(Service.Level.APP)
class EyeCarePluginDisposable : Disposable {
    override fun dispose() {
        // Parented bus connections (LafManagerListener) are unsubscribed by the
        // platform when this service is disposed on plugin unload. Nothing else
        // to release.
    }
}
