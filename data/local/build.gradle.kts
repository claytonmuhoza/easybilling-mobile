// Encrypted local database, repositories and transactions.
plugins {
    id("easybilling.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claytonmuhoza.easybilling.data.local"
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)

    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
