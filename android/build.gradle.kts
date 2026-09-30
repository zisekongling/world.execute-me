/* The plugin versions are pinned rather than ranged on purpose. This project is
   built against a machine whose Gradle cache already holds exactly these two
   artefacts, so the build works with `--offline` as well as online. */
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}
