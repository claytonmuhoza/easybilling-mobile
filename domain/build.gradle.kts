// Business rules, use cases and ports. Depends on nothing: no Android, no infrastructure library.
plugins {
    id("easybilling.kotlin.library")
}

dependencies {
    // Coroutines are part of the public API of the domain (suspending ports, observable state).
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
