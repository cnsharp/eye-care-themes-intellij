# Eye Care Themes

A set of soft, low-glare **light** themes for IntelliJ-based IDEs, plus a
custom color picker. Compared with the default all-white UI, the softer
background reduces glare and feels gentler on the eyes during daytime use.

* Plugin ID: `com.cnsharp.eyecare`
* Vendor: CnSharp Studio

## Features

- **Five preset eye-care themes** — calm, low-contrast light tints.
- **Custom color** — pick any color with the built-in picker; it is applied to
  both the UI chrome and the editor background.
- **Status-bar switcher** — a color-chip widget in the status bar for one-click
  switching (shown by default, no manual enable needed).
- **Welcome screen tinting** — the custom color is pushed into the welcome
  screen and every UI surface, not just the editor.
- **Persistence** — your chosen custom color survives restarts, and a fresh
  install defaults to the green theme automatically.
- **Cross-product** — works on IntelliJ IDEA, Rider, and other IntelliJ-platform
  products.

## Presets

| Theme | Color |
| ----- | ----- |
| Light Green | `#C7EDCC` |
| Almond Yellow | `#FAF9DE` |
| Pink Sand | `#FDE6E0` |
| Sky Blue | `#DCEAF5` |
| Lavender | `#E6E6FA` |

The **Custom…** entry opens the IntelliJ color picker so you can choose any
color.

## Usage

There are three ways to open the switcher:

1. **Status-bar chip** — click the eye-care color chip at the bottom-right of
   the status bar.
2. **View menu** — `View ▸ Eye Care Theme`.
3. **Find Action** — `Ctrl/Cmd+Shift+A` and type `Eye Care`.

Select a preset to switch immediately, or choose **Custom…** to pick your own
color. The choice is applied to the whole IDE (UI + editor) and remembered for
next time.

## Compatibility

- `since-build="191"` (IntelliJ IDEA 2019.1) through `999.*` (all current and
  future builds, including Rider 2026.x).
- Requires only `com.intellij.modules.platform`, so it installs on the full
  IntelliJ family.

## Building from source

The plugin is built with Gradle and the
[IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html).

```bash
./gradlew build
```

The installable plugin ZIP is produced in `build/distributions/`.

> **Note:** the build targets IntelliJ Platform **2022.2** and Java **1.8**
> bytecode so the same artifact runs on both old (2019.1+) and very new
> (2026.x) IDEs. The Gradle `verifyPluginConfiguration` task prints two
> informational warnings about `since-build`/`sourceCompatibility`; these are
> expected and intentional, not errors.

## Project layout

```
src/main/kotlin/com/cnsharp/intellij/
├── EyeCareWidgetFactory.kt     # registers the status-bar chip
├── EyeCareStatusBarWidget.kt   # the color-chip widget
├── EyeCareSwitcherAction.kt    # "Eye Care Theme" action (View menu / Find Action)
├── EyeCareStartupActivity.kt   # startup hook: theme + persistence on boot
├── LafThemeHelper.kt           # preset theme switching (reflective LafManager)
├── EyeCareCustomTheme.kt       # custom-color application (transient UITheme + editor)
├── EyeCareThemes.kt            # preset model + active-color resolver
├── EyeCarePopup.kt             # shared switcher popup + color picker
├── EyeCareBundle.kt            # localized messages
└── EyeCareGlobals.kt           # shared reflection handles + logger
```

## Internationalization

Messages are localized via `DynamicBundle`. Bundles are provided for English
(default), Chinese (`zh`, `zh_CN`, `zh_TW`), French, German, Japanese, and
Korean; the IDE picks the matching file automatically.

## License

See the `LICENSE` file for details.
