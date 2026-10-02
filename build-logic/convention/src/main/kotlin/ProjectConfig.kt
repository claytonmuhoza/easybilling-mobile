import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Build settings shared by every module of the project. */
object ProjectConfig {
    /** API level the modules are compiled against. */
    const val COMPILE_SDK = 37

    /** Lowest Android version supported: Android 8.0. */
    const val MIN_SDK = 26

    /** API level the application targets. */
    const val TARGET_SDK = 37

    /** Java language level of the produced bytecode. */
    val javaVersion: JavaVersion = JavaVersion.VERSION_17

    /**
     * JVM arguments of the Robolectric unit tests. Robolectric reaches into `jdk.internal.access`
     * to emulate Android shared memory and loads a native graphics library, two things recent JDKs
     * refuse or warn about by default.
     */
    val robolectricJvmArgs = listOf(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
    )
}

/**
 * The `libs` version catalog, for use inside convention plugins.
 *
 * Not named `libs`: that would hide the type-safe `libs` accessor in the module build scripts.
 */
val Project.versionCatalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Looks up a library of the version catalog by its alias.
 *
 * @param alias the alias as written in `libs.versions.toml`, with dots or dashes.
 * @return the dependency provider for that library.
 * @throws NoSuchElementException if the alias is not declared in the catalog.
 */
fun VersionCatalog.library(alias: String) = findLibrary(alias).get()
