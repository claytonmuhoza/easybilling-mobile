// Screenshot tests on the JVM. Reference images are committed under src/test/screenshots and
// `check` fails when a screen no longer matches them.
// Record new references with: ./gradlew recordRoborazziDebug
plugins {
    id("io.github.takahirom.roborazzi")
}

roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/screenshots"))
}

dependencies {
    "testImplementation"(versionCatalog.library("roborazzi"))
    "testImplementation"(versionCatalog.library("roborazzi-compose"))
}

tasks.named("check") {
    dependsOn("verifyRoborazziDebug")
}
