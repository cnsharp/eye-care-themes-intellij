import org.jetbrains.intellij.tasks.PatchPluginXmlTask

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.cnsharp.intellij"
version = "1.0.2"

repositories {
    mavenCentral()
}

// Resources + Kotlin status-bar widget. Target Java 1.8 bytecode so the
// produced classes still load on the oldest supported IDEs (since-build 191).
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    // Compile Kotlin to Java 1.8 bytecode (no toolchain auto-provisioning).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

// IntelliJ Platform Gradle Plugin configuration
intellij {
    // Built against 2022.2 (build 222). We keep the build target below 2022.3
    // so the IntelliJ Gradle Plugin only requires a Java 11 build JVM (2022.3+
    // would force Java 17). Runtime compatibility is governed by since-build /
    // until-build in plugin.xml and remains the full IntelliJ family (incl.
    // Rider 2026.x).
    version.set("2022.2")
    type.set("IC") // IntelliJ IDEA Community Edition; enough for a theme plugin.
    // Don't let the 2022.2 build version clamp until-build to 222.* — leave the
    // plugin.xml's open upper bound so the plugin installs on current and future
    // IDEs (e.g. 2026.2 / build 262).
    updateSinceUntilBuild.set(false)
    // No custom plugins needed: we only depend on com.intellij.modules.platform.
}

tasks {
    // Keep the original plugin.xml as the source of truth, but enforce the
    // compatibility range so the produced jar stays installable on old + new IDEs.
    patchPluginXml {
        // 191 = IntelliJ IDEA 2019.1: first release with StatusBarWidgetFactory,
        // which the eye-care switcher widget relies on. (Drops only 2018.x.)
        sinceBuild.set("191")
    }

    // The plugin has no searchable options to index.
    buildSearchableOptions {
        enabled = false
    }

    // Bytecode instrumentation (IntelliJ nullability assertions) is unnecessary
    // for a theme plugin and fails on JDK 17 (it probes a non-existent
    // ${java.home}/Packages path). Disable it.
    instrumentCode {
        enabled = false
    }
}
