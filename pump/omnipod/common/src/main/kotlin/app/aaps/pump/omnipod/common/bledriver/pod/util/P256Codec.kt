package app.aaps.pump.omnipod.common.bledriver.pod.util

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * Shared encode/decode helpers between NIST P-256 raw key representations (as used by
 * Apple's CryptoKit, and therefore by Insulet's Omnipod 5 protocol) and the JCE's
 * ECPrivateKey/ECPublicKey types.
 *
 * Used by both [P256KeyGenerator] (ECDH key agreement, for pairing) and O5CertificateStore
 * (ECDSA signing/verification, for pod command authentication) since both operate on raw
 * keys over the same curve and need the same raw-bytes <-> JCE-key-object conversions.
 *
 * Raw encodings match CryptoKit's `rawRepresentation`:
 * - Private key: the 32-byte big-endian scalar (no ASN.1/DER wrapping).
 * - Public key: the 65-byte X9.63 uncompressed point encoding (0x04 || X(32) || Y(32)).
 */
object P256Codec {

    const val CURVE_NAME = "secp256r1"
    const val SCALAR_LENGTH_BYTES = 32
    const val UNCOMPRESSED_POINT_LENGTH_BYTES = 65

    fun ecParameterSpec(): ECParameterSpec {
        val algorithmParameters = AlgorithmParameters.getInstance("EC")
        algorithmParameters.init(ECGenParameterSpec(CURVE_NAME))
        return algorithmParameters.getParameterSpec(ECParameterSpec::class.java)
    }

    fun decodePrivateKey(rawScalar: ByteArray): ECPrivateKey {
        require(rawScalar.size == SCALAR_LENGTH_BYTES) {
            "P-256 private key scalar must be $SCALAR_LENGTH_BYTES bytes, got ${rawScalar.size}"
        }
        val params = ecParameterSpec()
        val s = BigInteger(1, rawScalar)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(ECPrivateKeySpec(s, params)) as ECPrivateKey
    }

    fun decodePublicKey(rawPoint: ByteArray): ECPublicKey {
        require(rawPoint.size == UNCOMPRESSED_POINT_LENGTH_BYTES && rawPoint[0] == 0x04.toByte()) {
            "P-256 public key must be a $UNCOMPRESSED_POINT_LENGTH_BYTES-byte uncompressed point starting with 0x04"
        }
        val x = BigInteger(1, rawPoint.copyOfRange(1, 1 + SCALAR_LENGTH_BYTES))
        val y = BigInteger(1, rawPoint.copyOfRange(1 + SCALAR_LENGTH_BYTES, 1 + 2 * SCALAR_LENGTH_BYTES))
        val params = ecParameterSpec()
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    fun encodePoint(point: ECPoint): ByteArray {
        val x = point.affineX.toFixedLengthBytes(SCALAR_LENGTH_BYTES)
        val y = point.affineY.toFixedLengthBytes(SCALAR_LENGTH_BYTES)
        return byteArrayOf(0x04) + x + y
    }

    fun encodePublicKey(publicKey: ECPublicKey): ByteArray = encodePoint(publicKey.w)

    /**
     * Normalizes a BigInteger's magnitude to exactly [length] bytes, big-endian.
     * [BigInteger.toByteArray] may prepend a 0x00 sign byte, or be shorter than
     * expected for small values; this pads/strips as needed.
     */
    fun BigInteger.toFixedLengthBytes(length: Int): ByteArray {
        val raw = this.toByteArray()
        return when {
            raw.size == length                             -> raw
            raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < length                               -> ByteArray(length - raw.size) + raw
            else                                             -> throw IllegalStateException(
                "Value does not fit in $length bytes (got ${raw.size})"
            )
        }
    }
}
