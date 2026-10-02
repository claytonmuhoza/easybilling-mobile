// Device and system services: Keystore, Bluetooth printing, PDF, background work, scanning, Drive.
plugins {
    id("easybilling.android.library")
}

android {
    namespace = "com.claytonmuhoza.easybilling.platform"
}

dependencies {
    implementation(project(":domain"))
}
