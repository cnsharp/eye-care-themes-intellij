package com.cnsharp.intellij

import com.intellij.ide.util.PropertiesComponent
import java.awt.Color
import java.nio.charset.StandardCharsets
import kotlin.Function1

/**
 * Applies and persists a user-chosen custom eye-care color.
 *
 * Preset themes (green/yellow/...) are real installed UI themes switched via
 * LafManager. A custom color has no bundled theme, so instead we build a
 * transient UITheme at runtime for the chrome, tint the editor background
 * directly, and push named colors into UIManager so the welcome screen and
 * every UI surface pick up the tint. The chosen color is persisted and
 * re-applied on every IDE start (see LafThemeHelper.applyPersistedCustomTheme).
 *
 * Shares the file-scope [LOG], [lafClass] and [lafInstance] declared in
 * [EyeCareThemeSwitcher], since both this class and the preset switcher
 * (LafThemeHelper) drive LafManager reflectively.
 */
internal object EyeCareCustomTheme {
    const val CUSTOM_ID = "cnsharp.eyecare.custom"
    private const val CUSTOM_COLOR_KEY = "eyecare.custom.color"

    /** Last custom color the user picked, persisted across restarts. */
    fun customColor(): String? = PropertiesComponent.getInstance().getValue(CUSTOM_COLOR_KEY)

    /** Forget the persisted custom color (e.g. when the user picks a preset). */
    fun clearPersistedColor() = PropertiesComponent.getInstance().unsetValue(CUSTOM_COLOR_KEY)

    /**
     * Applies a user-chosen custom color as the eye-care theme. The color is
     * persisted (PropertiesComponent) and re-applied on every IDE start, so the
     * choice survives a restart instead of falling back to a dark theme. The
     * actual work is dispatched to the EDT because
     * LafManager.setCurrentUIThemeLookAndFeel must run there.
     */
    fun setCustomTheme(colorHex: String): Boolean {
        val color = runCatching { Color.decode(colorHex) }.getOrNull() ?: return false
        PropertiesComponent.getInstance().setValue(CUSTOM_COLOR_KEY, colorHex)
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            applyNow(colorHex, color)
        } else {
            app.invokeLater { applyNow(colorHex, color) }
        }
        return true
    }

    /**
     * Two layers, because the IntelliJ 2026.2 API has no single call that tints
     * both chrome and editor:
     *  1. Chrome (window/panel backgrounds): a transient UITheme built from the
     *     color and applied via LafManager.setCurrentUIThemeLookAndFeel.
     *  2. Editor background: set directly on the global EditorColorsScheme's
     *     TEXT attribute, then re-published via EditorColorsManager.
     */
    /**
     * Re-entrancy guard. [applyChrome] fires the LaF listener synchronously on the
     * EDT, which calls [reapplyOverrides]; [applyNow] then re-does the same UI
     * work. While we are inside [applyNow] we suppress [reapplyOverrides] and let
     * [applyNow] perform the work exactly once, avoiding doubled work (and the
     * duplicate listener-registration risk inside updateComponentTreeUI).
     */
    private var applying = false

    private fun applyNow(colorHex: String, color: Color) {
        applying = true
        try {
            val chromeOk = runCatching { applyChrome(colorHex) }
                .onFailure { LOG.warn("EyeCare: chrome apply failed", it) }
                .isSuccess

            val editorOk = runCatching { applyEditorBackground(color) }
                .onFailure { LOG.warn("EyeCare: editor apply failed", it) }
                .isSuccess

            // The welcome screen (WelcomeScreenUIManager.getMainBackground) and other
            // named-color consumers resolve colors through JBColor.namedColor, which
            // reads `UIManager.getColor(name)`. A transient runtime UITheme applied
            // via setCurrentUIThemeLookAndFeel does NOT reliably publish its colors
            // into UIManager, so we push them directly — this is what actually tints
            // the welcome screen (and keeps it from falling back to the dark default).
            applyUiDefaults(colorHex)

            // FlatWelcomeFrame reads WelcomeScreen.background ONCE at construction and
            // stores it as a fixed Color, so a plain repaint() won't re-read UIManager.
            // Force the welcome frame's background here, and again from the LaF-change
            // listener, to guarantee it is tinted even if it was built before our put.
            forceWelcomeFrameBackground(colorHex)

            // Re-theme any already-open windows (incl. the welcome frame) so the new
            // colors take effect immediately, not just on the next restart.
            repaintAllWindows()

            LOG.warn("EyeCare: setCustomTheme color=$colorHex chromeOk=$chromeOk editorOk=$editorOk")
        } finally {
            applying = false
        }
    }

    /**
     * Re-entrancy guard for the LaF-listener path. [repaintWindows] (used below)
     * must NOT call SwingUtilities.updateComponentTreeUI, because rebuilding the
     * component tree of the IDE's main frame re-fires lookAndFeelChanged, which
     * re-enters this method and loops forever (pegging the CPU). This guard stops
     * any synchronous re-entry; the `applying` guard covers the applyNow path.
     */
    private var reapplying = false

    /** Re-apply or clear our overrides in response to a look-and-feel change. */
    fun reapplyOverrides() {
        if (applying || reapplying) return
        reapplying = true
        try {
            val custom = customColor()
            if (custom != null) {
                applyUiDefaults(custom)
                forceWelcomeFrameBackground(custom)
            } else {
                clearUiDefaults()
                // A preset eye-care theme tints the editor via its bundled scheme, but
                // the commit-message field reads the UI-theme-bound scheme and may miss
                // the tint after a restart — re-apply it on every LaF change.
                LafThemeHelper.reapplyPresetEditorTint()
            }
            // Repaint only (no updateComponentTreeUI): running updateComponentTreeUI
            // from inside a LafManagerListener re-triggers lookAndFeelChanged and
            // loops forever. A plain repaint is enough for our UIManager.put /
            // direct-background overrides; the transient/preset theme already
            // rebuilt the component UIs during the LaF change itself.
            repaintWindows()
        } finally {
            reapplying = false
        }
    }

    /** Remove our overrides so the real (non-custom) theme shows normally. */
    private fun clearUiDefaults() {
        try {
            CHROME_KEYS.forEach { javax.swing.UIManager.getDefaults().remove(it) }
        } catch (e: Throwable) {
            LOG.warn("EyeCare: clearUiDefaults failed", e)
        }
    }

    /**
     * Push our colors directly into UIManager. JBColor.namedColor (used by the
     * welcome screen via WelcomeScreenUIManager.getMainBackground) resolves
     * named colors through `UIManager.getColor`, which a transient runtime
     * UITheme applied via setCurrentUIThemeLookAndFeel does NOT reliably
     * publish — so we set them explicitly. This is what actually tints the
     * welcome screen instead of letting it fall back to the dark default.
     */
    /** All UIManager color keys we override so every UI surface gets the eye-care tint. */
    private val CHROME_KEYS = listOf(
        // Welcome screen
        "WelcomeScreen.background",
        "WelcomeScreen.captionBackground",
        "WelcomeScreen.headerBackground",
        "WelcomeScreen.footerBackground",
        "WelcomeScreen.borderColor",
        "WelcomeScreen.groupIconBorderColor",
        // Core containers
        "Panel.background",
        "Window.background",
        // Toolbar / action bar (New UI)
        "ActionToolbar.background",
        "ToolBar.background",
        "Toolbar.background",
        "ActionToolbar.transparentPlaceholderBackground",
        // Text inputs & editors (non-IDE-editor JTextComponents)
        "TextField.background",
        "TextArea.background",
        "EditorPane.background",
        "ComboBox.background",
        // Buttons (New UI uses gradient start/end)
        "Button.background",
        "Button.startBackground",
        "Button.endBackground",
        "Button.default.startBackground",
        "Button.default.endBackground",
        // Data views — only the row/background is tinted on purpose. The
        // *selectionBackground keys must NOT be forced to an opaque color:
        // that makes the selected row indistinguishable from an unselected
        // one (it equals the row background) and is the same class of bug as
        // the per-item menu keys above. Keep the IDE's selection color.
        "Tree.background",
        "Table.background",
        "List.background",
        // Tabs & panels
        "TabbedPane.background",
        "SplitPane.background",
        // Panel headers / title panes (e.g. "Changes", "Amend last commit" bars)
        "PanelHeader.background",
        "TitlePane.background",
        "Component.header.background",
        // Tool window header (the title bar strip of Project / Build / Git panels)
        "ToolWindow.headerBackground",
        "ToolWindow.Header.background",
        // Popups & menus — only the container chrome is tinted on purpose.
        // The per-item *background / selectionBackground / hoverBackground keys
        // must NOT be forced to an opaque color: doing so makes menu items
        // opaque and breaks IntelliJ's New-UI armed/unarmed repaint, leaving
        // the cursor's highlight stuck on every item it sweeps over. Let those
        // keys keep their IDE defaults (selection = blue) and let items inherit
        // the popup/menu background below.
        "PopupMenu.background",
        "Menu.background",
        "MenuBar.background",
        // Scrollbar
        "ScrollBar.background",
        // Tooltips
        "ToolTip.background",
    )

    private fun applyUiDefaults(colorHex: String) {
        try {
            val c = Color.decode(colorHex)
            CHROME_KEYS.forEach { javax.swing.UIManager.put(it, c) }
        } catch (e: Throwable) {
            LOG.warn("EyeCare: applyUiDefaults failed", e)
        }
    }

    /**
     * The welcome frame captures WelcomeScreen.background as a fixed Color at
     * construction time, so a repaint() cannot re-read UIManager. Walk every open
     * window, find the welcome frame, and force its (and its children's)
     * background to our color. This is what makes the welcome screen actually
     * change color when it was built before our UIManager.put took effect.
     */
    fun forceWelcomeFrameBackground(colorHex: String) {
        try {
            val c = Color.decode(colorHex)
            for (w in java.awt.Window.getWindows()) {
                if (w.javaClass.name.contains("Welcome", ignoreCase = true) ||
                    w.javaClass.name.contains("FlatWelcome", ignoreCase = true)
                ) {
                    setBackgroundDeep(w, c)
                }
            }
        } catch (e: Throwable) {
            LOG.warn("EyeCare: forceWelcomeFrameBackground failed", e)
        }
    }

    private fun setBackgroundDeep(comp: java.awt.Component, c: Color) {
        try {
            comp.background = c
        } catch (_: Throwable) {
        }
        if (comp is java.awt.Container) {
            for (child in comp.components) setBackgroundDeep(child, c)
        }
    }

    /** Repaint every open window so a freshly applied theme (incl. the welcome
     *  screen) re-reads UI defaults immediately. Best-effort; never throws.
     *  NOTE: this calls SwingUtilities.updateComponentTreeUI and MUST NOT be used
     *  from the LafManagerListener (reapplyOverrides) — it re-fires
     *  lookAndFeelChanged and loops forever. It is only for the explicit,
     *  one-shot applyNow path, which is guarded by [applying]. */
    fun repaintAllWindows() {
        try {
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            val doRepaint = {
                try {
                    for (w in java.awt.Window.getWindows()) {
                        javax.swing.SwingUtilities.updateComponentTreeUI(w)
                        w.repaint()
                    }
                } catch (e: Throwable) {
                    LOG.warn("EyeCare: repaintAllWindows failed", e)
                }
            }
            if (app.isDispatchThread) doRepaint() else app.invokeLater(doRepaint)
        } catch (e: Throwable) {
            LOG.warn("EyeCare: repaintAllWindows scheduling failed", e)
        }
    }

    /**
     * Repaint every open window WITHOUT rebuilding component UIs. Safe to call
     * from the LaF-change listener: it does not re-fire lookAndFeelChanged, so it
     * cannot loop. Used by [reapplyOverrides].
     */
    private fun repaintWindows() {
        try {
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            val doRepaint = {
                try {
                    for (w in java.awt.Window.getWindows()) w.repaint()
                } catch (e: Throwable) {
                    LOG.warn("EyeCare: repaintWindows failed", e)
                }
            }
            if (app.isDispatchThread) doRepaint() else app.invokeLater(doRepaint)
        } catch (e: Throwable) {
            LOG.warn("EyeCare: repaintWindows scheduling failed", e)
        }
    }

    /** Window/panel tint via a transient UITheme (editor handled separately). */
    private fun applyChrome(colorHex: String) {
        val loader = EyeCareCustomTheme::class.java.classLoader
        val uiThemeClass = Class.forName("com.intellij.ide.ui.UITheme")
        val companion = uiThemeClass.getField("Companion").get(null)
        val load = companion.javaClass.getMethod(
            "loadFromJsonWithParent",
            ByteArray::class.java, String::class.java, ClassLoader::class.java, Function1::class.java,
        )
        // The 4th arg is the parent resolver. On 2026.2 its signature is
        // `Function1<String, String>`: given the JSON "parent" name it must
        // RETURN that parent theme's JSON (as a String) so IntelliJ can merge
        // it in. Returning null (the previous behaviour) meant the parent was
        // never loaded, so every color we don't override — Panel.background,
        // the welcome-screen main panel, text colors, borders — had no inherited
        // value and IntelliJ painted it BLACK. That is what made the welcome
        // page black. So we resolve "IntelliJ Light" to its real JSON here.
        val parentResolver: Function1<String, Any?> = { parentName ->
            try {
                val mgr = Class.forName("com.intellij.ide.ui.laf.UiThemeProviderListManager")
                    .getMethod("getInstance").invoke(null)
                val mgrClass = mgr.javaClass
                val getThemeJson = mgrClass.getMethod("getThemeJson", String::class.java)
                // getThemeJson looks up by theme *id*; "IntelliJ Light" is the
                // display name, so map it through findThemeByName -> getID first.
                var bytes = getThemeJson.invoke(mgr, parentName) as? ByteArray
                if (bytes == null) {
                    val info = mgrClass.getMethod("findThemeByName", String::class.java)
                        .invoke(mgr, parentName)
                        ?: mgrClass.getMethod("findThemeById", String::class.java)
                            .invoke(mgr, parentName)
                    val id = info?.javaClass?.getMethod("getID")?.invoke(info) as? String
                    if (id != null) bytes = getThemeJson.invoke(mgr, id) as? ByteArray
                }
                bytes?.toString(StandardCharsets.UTF_8)
            } catch (e: Throwable) {
                LOG.warn("EyeCare: parent theme '$parentName' resolve failed", e)
                null
            }
        }

        // Build against "IntelliJ Light" so every named color we don't override
        // inherits a sane LIGHT default (Panel.background, the welcome-screen
        // main panel, text colors, borders, ...). Without a resolved parent
        // these fall back to black — that is what made the welcome page black.
        // The explicit WelcomeScreen.* keys keep the welcome-screen chrome in
        // our chosen color even if the parent somehow can't be resolved.
        val json = buildJson(colorHex).toByteArray(StandardCharsets.UTF_8)
        val theme = try {
            load.invoke(companion, json, CUSTOM_ID, loader, parentResolver)
        } catch (e: Throwable) {
            // Parent ("IntelliJ Light") may be unavailable on some IDE builds;
            // retry without a parent. The explicit WelcomeScreen.* keys in the
            // JSON still prevent a black welcome screen.
            LOG.warn("EyeCare: themed load with parent failed, retrying without parent", e)
            val json2 = buildJson(colorHex, withParent = false).toByteArray(StandardCharsets.UTF_8)
            load.invoke(companion, json2, CUSTOM_ID, loader, parentResolver)
        } ?: throw IllegalStateException("loadFromJsonWithParent returned null")
        uiThemeClass.getMethod("setProviderClassLoader", ClassLoader::class.java).invoke(theme, loader)

        val infoClass = Class.forName("com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo")
        val implClass = Class.forName("com.intellij.ide.ui.laf.UIThemeLookAndFeelInfoImpl")
        val info = implClass.getConstructor(uiThemeClass).newInstance(theme)
        lafClass.getMethod("setCurrentUIThemeLookAndFeel", infoClass).invoke(lafInstance, info)
    }

    /**
     * Editor background tint. In 2026.2 the editor background lives on the
     * scheme's TEXT attribute (not a UITheme color), so we set it directly on
     * the global scheme and re-publish it. This is the same attribute the
     * bundled eyecare_*.xml schemes set, just applied programmatically.
     *
     * Why we clone `getSchemeForCurrentUITheme()` instead of the current global
     * scheme: `CommitMessage` only uses the global scheme when panel darkness ==
     * editor darkness; otherwise it falls back to `getSchemeForCurrentUITheme()`
     * (which has NO tint -> white commit box). `EditorColorsManager.isDarkEditor()`
     * reads the scheme's `getDefaultBackground()`. Our earlier code edited the
     * *current* global scheme, whose defaultBackground was still the (often dark)
     * base theme's, so darkness mismatched and the commit box fell back to white.
     * Cloning `getSchemeForCurrentUITheme()` (which follows our light transient/
     * UI theme, hence a LIGHT defaultBackground) makes isDarkEditor() == false,
     * matching the light panel, so the commit editor uses the (tinted) global
     * scheme. Cloning also guarantees setGlobalScheme is treated as a real change.
     *
     * Shared by both the custom theme and the preset themes (so surfaces that
     * read the editor scheme — including the commit-message field — get the
     * eye-care color). Must run on the EDT.
     */
    fun applyEditorBackground(color: Color) {
        try {
            val ecmClass = Class.forName("com.intellij.openapi.editor.colors.EditorColorsManager")
            val ecm = ecmClass.getMethod("getInstance").invoke(null)

            val schemeClass = Class.forName("com.intellij.openapi.editor.colors.EditorColorsScheme")
            val takClass = Class.forName("com.intellij.openapi.editor.colors.TextAttributesKey")
            val taClass = Class.forName("com.intellij.openapi.editor.markup.TextAttributes")
            val textKey = Class.forName("com.intellij.openapi.editor.HighlighterColors").getField("TEXT").get(null)

            // Idempotency: skip setGlobalScheme when the global scheme's TEXT background
            // is already the target color. setGlobalScheme fires EditorColorsListener;
            // LafManagerImpl can react by scheduling an async lookAndFeelChanged, which
            // arrives after the reapplying finally-block resets the flag and re-enters
            // reapplyOverrides indefinitely. Returning early when nothing changed breaks
            // that async feedback loop at zero cost.
            runCatching {
                val global = ecmClass.getMethod("getGlobalScheme").invoke(ecm)
                val attrs = schemeClass.getMethod("getAttributes", takClass).invoke(global, textKey)
                if (attrs != null && taClass.getMethod("getBackgroundColor").invoke(attrs) == color) return
            }

            // A light base to clone: prefer the scheme bound to the current UI theme
            // (light for us); fall back to the global scheme, then to IntelliJ Light.
            val baseScheme: Any? = runCatching {
                ecmClass.getMethod("getSchemeForCurrentUITheme").invoke(ecm)
            }.getOrNull() ?: runCatching {
                ecmClass.getMethod("getGlobalScheme").invoke(ecm)
            }.getOrNull() ?: runCatching {
                ecmClass.getMethod("getScheme", String::class.java).invoke(ecm, "IntelliJ Light")
            }.getOrNull()

            val source = baseScheme ?: return
            // Object.clone() is protected, so source.javaClass.getMethod("clone")
            // can't find it and would silently fall back to the original scheme —
            // making setGlobalScheme a no-op (no change-event dispatch). Walk the
            // public-method chain (declared + inherited) to locate the scheme's
            // public clone() override instead.
            val cloneMethod = generateSequence<Class<*>>(source.javaClass) { it.superclass }
                .flatMap { it.methods.asSequence() }
                .firstOrNull { it.name == "clone" && it.parameterCount == 0 }
                ?: throw IllegalStateException("EditorColorsScheme has no public clone()")
            cloneMethod.isAccessible = true
            val cloned = schemeClass.cast(cloneMethod.invoke(source))

            val cur = schemeClass.getMethod("getAttributes", takClass).invoke(cloned, textKey)
            val attrs = taClass.getConstructor().newInstance()
            taClass.getMethod("setBackgroundColor", Color::class.java).invoke(attrs, color)
            if (cur != null) {
                taClass.getMethod("setForegroundColor", Color::class.java)
                    .invoke(attrs, taClass.getMethod("getForegroundColor").invoke(cur))
                taClass.getMethod("setFontType", Int::class.java)
                    .invoke(attrs, taClass.getMethod("getFontType").invoke(cur))
            }
            schemeClass.getMethod("setAttributes", takClass, taClass).invoke(cloned, textKey, attrs)
            // Pass the INTERFACE class (EditorColorsScheme), not the impl class — getMethod
            // matches declared parameter types exactly, so the impl class would never match.
            ecmClass.getMethod("setGlobalScheme", schemeClass).invoke(ecm, cloned)

            val bg = schemeClass.getMethod("getDefaultBackground").invoke(cloned)
            LOG.warn("EyeCare: editor background set to $color (defaultBackground now $bg)")
        } catch (e: Throwable) {
            LOG.warn("EyeCare: applyEditorBackground failed", e)
        }
    }

    private fun buildJson(colorHex: String, withParent: Boolean = true): String {
        // Normalize/validate before interpolation: an unescaped quote or backslash
        // from a tampered PropertiesComponent value would produce invalid JSON and
        // silently break theme loading. Fall back to the default green on bad input.
        val hex = colorHex.trim().let { raw ->
            if (raw.matches(Regex("^#?[0-9A-Fa-f]{6}$")) || raw.matches(Regex("^#?[0-9A-Fa-f]{3}$"))) {
                if (raw.startsWith("#")) raw else "#$raw"
            } else {
                "#C7EDCC"
            }
        }
        // Parent the transient theme on "IntelliJ Light" so every named color
        // we don't override inherits a sane LIGHT default (this is what keeps
        // the welcome page and other chrome from falling back to black). The
        // withParent=false form is only used as a load fallback.
        val parentLine = if (withParent) "\"parent\": \"IntelliJ Light\",\n" else ""
        // No "id" in the JSON: IntelliJ derives the theme id from the `name`
        // argument passed to loadFromJsonWithParent (here CUSTOM_ID).
        // Chrome only — the editor background is set via applyEditorBackground.
        // Derive the UI color map from CHROME_KEYS so the transient UITheme JSON
        // and the direct UIManager.put layer can never drift apart.
        //
        // IMPORTANT: we must NOT use a "*" wildcard here, and we must NOT set a
        // background on MenuItem/ActionMenuItem/CheckboxMenuItem. Forcing any
        // background onto menu items breaks IntelliJ's New-UI menu repaint: an
        // opaque item leaves the hover/armed highlight stuck ("杂色"), while a
        // transparent one smears a trailing ghost ("拖影") as the cursor moves.
        // So instead of "*" we explicitly map every CHROME_KEY (container chrome
        // only — never the menu *items*) to basicBackground. The full background
        // coverage that "*" used to provide for the custom color is carried by
        // the UIManager.put layer in applyUiDefaults, which uses the very same
        // CHROME_KEYS list and also deliberately excludes menu items. Keys we
        // don't map here simply inherit the light "IntelliJ Light" parent
        // defaults (never black), so nothing else regresses.
        val uiLines = CHROME_KEYS.joinToString(",\n") { "    \"${jsonString(it)}\": \"basicBackground\"" }
        return """
        {
          "name": "${jsonString(EyeCareBundle.message("custom.theme.name"))}",
          "dark": false,
          "author": "cnsharp",
          $parentLine
          "colors": {
            "basicBackground": "$hex"
          },
          "ui": {
            $uiLines
          }
        }
        """.trimIndent()
    }

    /** Escape a string for safe interpolation into a JSON string literal. */
    private fun jsonString(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
