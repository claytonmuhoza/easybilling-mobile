// Encrypted local database, repositories and transactions.
plugins {
    id("easybilling.android.library")
}

android {
    namespace = "com.claytonmuhoza.easybilling.data.local"
}

dependencies {
    implementation(project(":domain"))
}
