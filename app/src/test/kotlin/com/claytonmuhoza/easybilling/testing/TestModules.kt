package com.claytonmuhoza.easybilling.testing

import android.content.Context
import com.claytonmuhoza.easybilling.data.local.LocalStorage
import com.claytonmuhoza.easybilling.data.obr.ObrHttp
import com.claytonmuhoza.easybilling.di.BackgroundModule
import com.claytonmuhoza.easybilling.di.ObrHttpModule
import com.claytonmuhoza.easybilling.di.PlatformModule
import com.claytonmuhoza.easybilling.di.StorageModule
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCode
import com.claytonmuhoza.easybilling.domain.identity.RecoveryCodeGenerator
import com.claytonmuhoza.easybilling.domain.identity.SecretHasher
import com.claytonmuhoza.easybilling.domain.shared.SecretStore
import com.claytonmuhoza.easybilling.domain.shared.TimeProvider
import com.claytonmuhoza.easybilling.domain.transmission.NetworkMonitor
import com.claytonmuhoza.easybilling.domain.transmission.TransmissionScheduler
import com.claytonmuhoza.easybilling.platform.security.Pbkdf2SecretHasher
import com.claytonmuhoza.easybilling.testing.obr.ObrSimulator
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Clock of the tests: it only moves when a test moves it. */
@Singleton
class TestClock @Inject constructor() : TimeProvider {
    private var instant: Instant = Instant.parse("2026-10-02T08:00:00Z")

    override fun now(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}

/** Replaces the device services by controllable ones: test clock, known recovery codes, fast hashing. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PlatformModule::class])
object TestPlatformModule {
    /** The recovery codes handed out, in order, as the user sees them. */
    val RECOVERY_CODES = listOf("ABCD-EFGH-JKLM", "NPQR-STUV-WXYZ", "2345-6789-ABCD")

    @Provides
    fun timeProvider(clock: TestClock): TimeProvider = clock

    @Provides
    @Singleton
    fun secretHasher(): SecretHasher = Pbkdf2SecretHasher(iterations = 1_000)

    @Provides
    @Singleton
    fun recoveryCodes(): RecoveryCodeGenerator {
        var issued = 0
        return RecoveryCodeGenerator {
            RecoveryCode(RecoveryCode.normalize(RECOVERY_CODES[issued++ % RECOVERY_CODES.size]))
        }
    }
}

/** Replaces the encrypted database, whose native library cannot be loaded on the JVM. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [StorageModule::class])
object TestStorageModule {
    @Provides
    @Singleton
    fun localStorage(@ApplicationContext context: Context): LocalStorage = LocalStorage.inMemory(context)

    @Provides
    @Singleton
    fun secretStore(): SecretStore = InMemorySecretStore()
}

/** Secrets kept in memory: the Android Keystore does not exist on the JVM. */
class InMemorySecretStore : SecretStore {
    private val secrets = ConcurrentHashMap<String, ByteArray>()

    override fun read(name: String): ByteArray? = secrets[name]

    override fun write(name: String, value: ByteArray) {
        secrets[name] = value
    }
}

/** Network of the tests: there until a test takes it away. */
@Singleton
class TestNetworkMonitor @Inject constructor() : NetworkMonitor {
    private val state = MutableStateFlow(true)

    override val online: StateFlow<Boolean> = state

    /** Takes the network away, or brings it back. */
    fun set(online: Boolean) {
        state.value = online
    }
}

/** Remembers the passes the application planned, instead of handing them to WorkManager. */
@Singleton
class RecordingTransmissionScheduler @Inject constructor() : TransmissionScheduler {
    /** The wait of each planned pass, oldest first; null for a cancellation. */
    val plans: MutableList<Duration?> = CopyOnWriteArrayList()

    override fun schedule(delay: Duration, afterRunningPass: Boolean) {
        plans += delay
    }

    override fun cancel() {
        plans += null as Duration?
    }
}

/** Replaces the network of the device and WorkManager, which the JVM tests do not have. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [BackgroundModule::class])
object TestBackgroundModule {
    @Provides
    fun networkMonitor(monitor: TestNetworkMonitor): NetworkMonitor = monitor

    @Provides
    fun transmissionScheduler(scheduler: RecordingTransmissionScheduler): TransmissionScheduler = scheduler
}

/** A certificate made for the tests: the simulated OBR server shows it, the test client trusts it. */
object TestCertificates {
    private val held: HeldCertificate = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()

    /** What the simulated server presents. */
    val server: HandshakeCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()

    /** What the test client trusts. */
    val client: HandshakeCertificates = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

    /** @return a simulated OBR server over HTTPS, started. */
    fun startSimulator(): ObrSimulator = ObrSimulator().apply {
        server.useHttps(TestCertificates.server.sslSocketFactory())
        start()
    }
}

/** HTTP client of the tests: it trusts the test certificate and gives up after 3 seconds. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ObrHttpModule::class])
object TestObrHttpModule {
    @Provides
    @Singleton
    fun obrHttpClient(): OkHttpClient = ObrHttp.client(Duration.ofSeconds(3)).newBuilder()
        .sslSocketFactory(TestCertificates.client.sslSocketFactory(), TestCertificates.client.trustManager)
        .build()
}
