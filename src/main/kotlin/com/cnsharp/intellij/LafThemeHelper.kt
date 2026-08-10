package com.cnsharp.intellij

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.awt.Color
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin reflection wrapper around LafManager. The theme-switching API changed
 * between IntelliJ Platform 2022.2 (getLookAndFeelReference / findLaf(LafReference)
 * / setCurrentLookAndFeel) and 2026.2 (getCurrentUIThemeLookAndFeel / findLaf(String)
 * / setCurrentUIThemeLookAndFeel). Resolving methods reflectively at runtime lets
 * one binary work on both, instead of binding to a compile-time signature that
 * may be absent on the user's actual IDE.
 */
object LafThemeHelper {

    fun currentThemeId(): String? {
        if (lafInstance == null) return null
        // 2026.2+
        try {
            val info = lafClass.getMethod("getCurrentUIThemeLookAndFeel").invoke(lafInstance)
            if (info != null) {
                val id = info.javaClass.getMethod("getId").invoke(info) as? String
                if (!id.isNullOrEmpty()) return id
            }
        } catch (_: Throwable) {
        }
        // 2022.2
        try {
            val ref = lafClass.getMethod("getLookAndFeelReference").invoke(lafInstance)
            if (ref != null) {
                val id = ref.javaClass.getMethod("getThemeId").invoke(ref) as? String
                if (!id.isNullOrEmpty()) return id
            }
        } catch (_: Throwable) {
        }
        return null
    }

    fun setTheme(themeId: String): Boolean {
        if (lafInstance == null) return false
        // A non-custom choice clears any remembered custom color so a later
        // restart doesn't re-apply the old custom theme over the chosen preset.
        if (themeId != EyeCareCustomTheme.CUSTOM_ID) {
            EyeCareCustomTheme.clearPersistedColor()
        }
        // 2026.2+: findLaf(String) -> info; setCurrentUIThemeLookAndFeel(info)
        try {
            val findLaf = lafClass.getMethod("findLaf", String::class.java)
            val info = findLaf.invoke(lafInstance, themeId)
            if (info != null) {
                val set = lafClass.declaredMethods.firstOrNull {
                    it.name == "setCurrentUIThemeLookAndFeel" && it.parameterCount == 1
                }
                if (set != null) {
                    set.isAccessible = true
                    set.invoke(lafInstance, info)
                    return true
                }
            }
        } catch (_: Throwable) {
        }
        // 2022.2: lafComboBoxModel.items -> ref by themeId; findLaf(ref) -> info; setCurrentLookAndFeel(info)
        try {
            val model = lafClass.getMethod("getLafComboBoxModel").invoke(lafInstance) ?: return false
            val items = model.javaClass.getMethod("getItems").invoke(model) as? List<*> ?: return false
            val ref = items.firstOrNull { item ->
                item?.javaClass?.getMethod("getThemeId")?.invoke(item) as? String == themeId
            } ?: return false
            val info = lafClass.getMethod("findLaf", ref.javaClass).invoke(lafInstance, ref) ?: return false
            val set = lafClass.declaredMethods.firstOrNull {
                it.name == "setCurrentLookAndFeel" && it.parameterCount == 1
            }
            if (set != null) {
                set.isAccessible = true
                set.invoke(lafInstance, info)
                return true
            }
        } catch (_: Throwable) {
        }
        LOG.warn("EyeCare: could not apply theme '$themeId' (unsupported LafManager API)")
        return false
    }

    internal const val DEFAULT_APPLIED_KEY = "eyecare.default.applied"

    /**
     * On first activation, default the IDE to the green eye-care theme so a fresh
     * install shows green immediately. Once applied — or if the user has already
     * made an explicit choice (a preset via the switcher, or a custom color) — the
     * [DEFAULT_APPLIED_KEY] flag is set and this becomes a no-op, so the user's
     * later choice is always respected.
     *
     * Crucially, we do NOT bail out just because an eye-care theme is already active
     * (older logic did, and that let the platform's own first-run theme selection —
     * which can land on Lavender, the alphabetically-first preset in the theme
     * picker — persist as the "default" instead of green). On a first run we always
     * push green unless a custom color is persisted (in which case that wins).
     */
    fun ensureDefaultGreenApplied() {
        val pc = PropertiesComponent.getInstance()
        if (pc.getBoolean(DEFAULT_APPLIED_KEY)) return
        // A persisted custom color beats the green default, but we still mark the
        // default as applied so we never re-apply green over the user's color.
        if (EyeCareCustomTheme.customColor() != null) {
            pc.setValue(DEFAULT_APPLIED_KEY, true)
            return
        }
        if (applyEyeCareTheme(GREEN_ID)) {
            LOG.warn("EyeCare: defaulted to green theme on first run")
        }
    }

    /**
     * Register a LafManagerListener (via the message bus TOPIC) that re-applies
     * our color overrides on EVERY look-and-feel change. This is the
     * ordering-proof part of the fix:
     *
     *  - The IDE restores/applies its own startup theme AFTER our app-init hook,
     *    which would otherwise wipe the UIManager.put calls we made. The listener
     *    re-pushes them the moment that theme change fires.
     *  - setCurrentUIThemeLookAndFeel (which we call ourselves) also fires it, so
     *    our overrides are guaranteed present afterwards.
     *
     * Installed once; safe to call repeatedly.
     */
    private val listenerInstalled = AtomicBoolean(false)

    /**
     * The connection is parented to the plugin's Disposable (the StartupActivity
     * singleton), NOT to the Application. Parenting to the Application would make
     * the listener survive a *dynamic plugin unload* — the Application is never
     * disposed on unload, so the proxy (whose invocation handler is loaded by this
     * plugin's class loader and captures EyeCareCustomTheme) would keep the plugin
     * class loader alive and block clean unloading (the Plugin Verifier's
     * "unloading may be restricted" dynamic-plugin warning).
     *
     * The StartupActivity is a plugin-lifetime singleton (instantiated once and
     * reused across all projects), so parenting here still survives project
     * open/close cycles — the original concern about a per-project Disposable
     * does not apply — and the platform disposes it on plugin unload, which
     * disconnects the bus connection and frees the class loader.
     */
    fun installOverridesListener(parent: Disposable) {
        if (!listenerInstalled.compareAndSet(false, true)) return
        try {
            val app = ApplicationManager.getApplication()
            val listenerClass = Class.forName("com.intellij.ide.ui.LafManagerListener")
            val topic = listenerClass.getField("TOPIC").get(null)
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, _ ->
                if (method != null && method.name == "lookAndFeelChanged") EyeCareCustomTheme.reapplyOverrides()
                null
            }
            val bus = app.messageBus
            val connect = bus.javaClass.getMethod("connect", Disposable::class.java).invoke(bus, parent)
            val topicClass = Class.forName("com.intellij.util.messages.Topic")
            val subscribe = connect.javaClass.getMethod("subscribe", topicClass, Any::class.java)
            subscribe.invoke(connect, topic, listener)
            LOG.warn("EyeCare: LafManagerListener installed")
        } catch (e: Throwable) {
            LOG.warn("EyeCare: installOverridesListener failed", e)
        }
    }

    /**
     * Recolor a tool window the moment it becomes visible. Some tool windows
     * (Database, Notifications, …) build their content lazily, so at theme-switch
     * time their component is not constructed yet and the bulk
     * [EyeCareCustomTheme.forceToolWindowBackground] skips them; when the user
     * later opens them they are built with a stale/cached background. This
     * listener recolors them on show. Only acts while an eye-care theme is active,
     * so non-eye-care themes are left untouched. Subscribed per-project on that
     * project's message bus (parented to the plugin Disposable like the LaF
     * listener, so it is cleaned up on plugin unload).
     *
     * A delayed re-apply (~300 ms after show) catches sub-components that are
     * constructed asynchronously or lazily after the tool window first becomes
     * visible — e.g. notification items, git log rows, commit buttons.
     */
    fun installToolWindowListener(project: Project, parent: Disposable) {
        try {
            val listenerClass = Class.forName("com.intellij.openapi.wm.ToolWindowManagerListener")
            val topic = listenerClass.getField("TOPIC").get(null)
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method != null && method.name == "toolWindowShown") {
                    val color = if (currentThemeId() in EYECARE_THEME_IDS) {
                        currentColor()
                    } else null
                    if (color != null) {
                        val tw = args?.firstOrNull() as? com.intellij.openapi.wm.ToolWindow
                        if (tw != null) {
                            // Immediate paint for the top-level container.
                            EyeCareCustomTheme.forceToolWindowBackground(tw, color)
                            // Delayed re-paint to catch lazy-loaded sub-components
                            // (notification items, git log rows, buttons…) that
                            // are created after the initial show event.
                            javax.swing.Timer(300) {
                                if (currentThemeId() in EYECARE_THEME_IDS) {
                                    EyeCareCustomTheme.forceToolWindowBackground(tw, currentColor())
                                }
                            }.apply { isRepeats = false; start() }
                        }
                    }
                }
                null
            }
            val bus = project.messageBus
            val connect = bus.javaClass.getMethod("connect", Disposable::class.java).invoke(bus, parent)
            val topicClass = Class.forName("com.intellij.util.messages.Topic")
            val subscribe = connect.javaClass.getMethod("subscribe", topicClass, Any::class.java)
            subscribe.invoke(connect, topic, listener)
            LOG.warn("EyeCare: ToolWindowManagerListener installed for ${project.name}")
        } catch (e: Throwable) {
            LOG.warn("EyeCare: installToolWindowListener failed", e)
        }
    }

    /** Resolve an eye-care preset's tint color by theme id (null if unknown). */
    internal fun themeColorById(themeId: String): Color? =
        THEMES.firstOrNull { it.id == themeId }?.color

    /**
     * Apply a preset eye-care theme: switch the UI theme (chrome) AND tint the
     * editor background via the TEXT attribute, so surfaces that read the editor
     * scheme — including the commit-message field — get the eye-care color. The
     * editor tint is dispatched to the EDT because
     * EditorColorsManager.setGlobalScheme must run there.
     */
    fun applyEyeCareTheme(themeId: String): Boolean {
        if (!setTheme(themeId)) return false
        // An explicit preset selection counts as the user's choice: stop the
        // first-run default from ever re-applying green over it on a later start.
        PropertiesComponent.getInstance().setValue(DEFAULT_APPLIED_KEY, true)
        val color = themeColorById(themeId) ?: return true
        val colorHex = EyeCareCustomTheme.toHex(color)
        // Chrome + editor tint + tool-window recolor; re-theme already-open windows
        // so the change is immediate. Must run on the EDT (setCurrentUIThemeLookAndFeel /
        // EditorColorsManager.setGlobalScheme).
        val apply = {
            EyeCareCustomTheme.applyEditorBackground(color)
            // Re-publish the eye-care color onto every surface (UIManager defaults,
            // welcome frame, tool windows, plus a delayed pass for lazy sub-components)
            // before rebuilding the UI tree, so the preset color — not the stale custom
            // one left in UIManager — is what commit buttons, git-log rows and
            // notification items pick up.
            EyeCareCustomTheme.applyColorToSurfaces(colorHex, color, tintWelcome = true)
            EyeCareCustomTheme.repaintAllWindows()
        }
        runOnEdt(apply)
        return true
    }

    /** On startup, re-apply a previously chosen custom color (if any). */
    fun applyPersistedCustomTheme() {
        val color = EyeCareCustomTheme.customColor() ?: return
        EyeCareCustomTheme.setCustomTheme(color)
    }

    /**
     * Re-apply the editor background tint for a preset eye-care theme (green/
     * yellow/...). The preset's editor tint comes from its bundled editorScheme
     * XML, but the commit-message field reads the UI-theme-bound scheme, which may
     * not inherit that tint after a restart — so we re-apply it explicitly here.
     * No-op for custom colors (handled by setCustomTheme) and for non-eye-care
     * themes (where we must NOT tint the editor).
     */
    fun reapplyPresetEditorTint() {
        val id = currentThemeId() ?: return
        if (id !in EYECARE_THEME_IDS) return
        val color = themeColorById(id) ?: return
        runOnEdt { EyeCareCustomTheme.applyEditorBackground(color) }
    }
}
