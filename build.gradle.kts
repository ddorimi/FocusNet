// build.gradle.kts (Project: RecordScreen)
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false // ✅ Required for Compose
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
