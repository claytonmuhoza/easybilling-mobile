// Fake OBR server, faithful to the EBMS specification. Used by tests only.
plugins {
    id("easybilling.kotlin.library")
}

dependencies {
    api(libs.okhttp.mockwebserver)
}
