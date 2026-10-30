plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}

// scripts/dev.sh puts the build output in a Docker volume (fast) instead of next to the sources.
System.getenv("POCKETTS_BUILD_ROOT")?.takeIf { it.isNotEmpty() }?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${if (this == rootProject) "_root" else name}"))
    }
}
