// Static analysis shared by every module. `check` runs it.
import dev.detekt.gradle.Detekt

plugins {
    id("dev.detekt")
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(layout.settingsDirectory.file("config/detekt/detekt.yml"))
}

// detekt defaults to the JVM running Gradle; analyse against the bytecode level of the project.
tasks.withType<Detekt>().configureEach {
    jvmTarget = ProjectConfig.javaVersion.toString()
}
