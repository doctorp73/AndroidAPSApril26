package app.aaps.pump.omnipod.common.bledriver.comm.pair

import java.util.Base64
import java.util.Random
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * PKI registration material for one Omnipod 5 controller identity: a secondary P-256
 * signing keypair plus the certificate chain issued for it, all keyed by controllerId.
 *
 * This is deliberately just a data holder + in-memory registry — this open-source project
 * doesn't ship real Insulet-issued PKI material. An optional separate module (not part of
 * this repository) can supply real registration data at app startup, either by calling
 * [O5RegistrationData.install] directly, or by providing an [Installer] implementation
 * discovered via [ServiceLoader] (see the private loader below).
 *
 * Ported from OmnipodKit's O5RegistrationData.swift (loopandlearn/OmnipodKit). The Swift
 * original loads optional data via a `dlsym`-based dynamic symbol lookup, which has no
 * portable JVM equivalent; [ServiceLoader] is the idiomatic JVM analogue of "let an optional
 * classpath module register itself, without this module needing a compile-time dependency
 * on it."
 */
data class O5RegistrationData(
    /** The 32-bit controller id this registration applies to (kept as Long to avoid
     *  unsigned-overflow pitfalls; valid values fit in the low 32 bits). */
    val controllerId: Long,
    val privateKeyHex: String,
    val publicKeyHex: String,
    val intermediateCABase64: String,
    val tlsCertificateBase64: String
) {

    val privateKey: ByteArray get() = hexToBytes(privateKeyHex)
    val publicKey: ByteArray get() = hexToBytes(publicKeyHex)

    val intermediateCA: ByteArray? get() = decodeBase64OrNull(intermediateCABase64)
    val tlsCertificate: ByteArray? get() = decodeBase64OrNull(tlsCertificateBase64)

    /** The controllerId as its 4-byte big-endian representation. */
    val controllerIdData: ByteArray
        get() {
            val id = controllerId and 0xFFFFFFFFL
            return byteArrayOf(
                ((id shr 24) and 0xFF).toByte(),
                ((id shr 16) and 0xFF).toByte(),
                ((id shr 8) and 0xFF).toByte(),
                (id and 0xFF).toByte()
            )
        }

    /**
     * Implement and register via `META-INF/services` (or call [O5RegistrationData.install]
     * directly at app startup) to supply real O5 PKI data from an optional module that
     * isn't part of this open-source repository.
     */
    interface Installer {
        fun install()
    }

    companion object {

        private val registry = ConcurrentHashMap<Long, O5RegistrationData>()
        private val random = Random()

        @Volatile
        private var optionalDataLoaded = false

        fun install(value: O5RegistrationData) {
            registry[value.controllerId] = value
        }

        fun get(controllerId: Long): O5RegistrationData? {
            loadOptionalRegistrationDataOnce()
            return registry[controllerId]
        }

        fun getRandom(): O5RegistrationData? {
            loadOptionalRegistrationDataOnce()
            val values = registry.values.toList()
            return if (values.isEmpty()) null else values[random.nextInt(values.size)]
        }

        val allValues: List<O5RegistrationData>
            get() {
                loadOptionalRegistrationDataOnce()
                return registry.values.toList()
            }

        val isEmpty: Boolean
            get() {
                loadOptionalRegistrationDataOnce()
                return registry.isEmpty()
            }

        fun contains(controllerId: Long): Boolean = get(controllerId) != null

        /** Randomly picks an available O5 controllerId, or 0 if none is available. */
        val pickControllerId: Long
            get() = getRandom()?.controllerId ?: 0L

        /**
         * Visible for testing: forces the ServiceLoader-based optional-data lookup to run
         * again on the next registry access, e.g. after registering a fake Installer.
         */
        internal fun resetOptionalDataLoadedForTesting() {
            optionalDataLoaded = false
        }

        private fun loadOptionalRegistrationDataOnce() {
            if (optionalDataLoaded) return
            synchronized(this) {
                if (optionalDataLoaded) return
                try {
                    ServiceLoader.load(Installer::class.java).forEach { it.install() }
                } catch (_: Throwable) {
                    // No optional registration-data module present on the classpath; that's
                    // fine, O5 pairing simply won't have any controller identities available.
                }
                optionalDataLoaded = true
            }
        }

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            require(clean.length % 2 == 0) { "Hex string must have an even length" }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private fun decodeBase64OrNull(value: String): ByteArray? =
            try {
                Base64.getDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
