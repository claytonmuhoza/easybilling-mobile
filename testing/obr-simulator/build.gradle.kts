// Fake OBR server, faithful to the EBMS specification. Used by tests only.
plugins {
    id("easybilling.kotlin.library")
}

dependencies {
    api(libs.okhttp.mockwebserver)
    // The invoices the server accepted are read by the tests as JSON: part of the API.
    api(libs.kotlinx.serialization.json)
}
