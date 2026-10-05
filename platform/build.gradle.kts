// Device and system services: Keystore, Bluetooth printing, PDF, background work, scanning, Drive.
plugins {
    id("easybilling.android.library")
    id("easybilling.android.screenshot")
}

android {
    namespace = "com.claytonmuhoza.easybilling.platform"
}

dependencies {
    implementation(project(":domain"))
    // QR code of the identifier of an invoice, on the PDF and on the ticket.
    implementation(libs.zxing.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
