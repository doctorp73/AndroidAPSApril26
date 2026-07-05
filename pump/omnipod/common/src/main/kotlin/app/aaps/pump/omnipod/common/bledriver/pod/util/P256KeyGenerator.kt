package app.aaps.pump.omnipod.common.bledriver.pod.util

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * NIST P-256 (secp256r1) key generation and ECDH key agreement for Omnipod 5 pairing.
 *
 * Ported from OmnipodKit's P256KeyGenerator.swift (loopandlearn/OmnipodKit), which wraps
 * Apple's CryptoKit P256.KeyAgreement. There is no equivalent to CryptoKit available on
 * Android, so this uses the standard java.security / javax.crypto EC APIs directly.
 *
 * Raw encodings match CryptoKit's `rawRepresentation` for P256.KeyAgreement keys:
 * - Private key: the 32-byte big-endian scalar (no ASN.1/DER wrapping).
 * - Public key: the 65-byte X9.63 uncompressed point encoding (0x04 || X(32) || Y(32)).
 *
 * The shared secret returned by [computeSharedSecret] is the raw ECDH result (the X
 * coordinate of the agreed point), matching CryptoKit's `sharedSecretFromKeyAgreement`,
 * which also returns the raw (un-hashed) agreed secret. Any KDF/hashing on top of this
 * shared secret is the caller's responsibility, same as with the Swift original.
 */
class P256KeyGenerator {

    fun generatePrivateKey(): ByteArray {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec(CURVE_NAME))
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private as java.security.interfaces.ECPrivateKey
        return privateKey.s.toFixedLengthBytes(SCALAR_LENGTH_BYTES)
    }

    fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == SCALAR_LENGTH_BYTES) {
            "P-256 private key scalar must be $SCALAR_LENGTH_BYTES bytes, got ${privateKey.size}"
        }
        val params = ecParameterSpec()
        val s = BigInteger(1, privateKey)
        val publicPoint = multiplyPoint(params.generator, s, params)
        return encodeUncompressedPoint(publicPoint)
    }

    fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val ecPrivateKey = decodePrivateKey(privateKey)
        val ecPublicKey = decodePublicKey(publicKey)

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(ecPrivateKey)
        keyAgreement.doPhase(ecPublicKey, true)
        return keyAgreement.generateSecret()
    }

    // -- encoding helpers --------------------------------------------------------------

    private fun decodePrivateKey(rawScalar: ByteArray): java.security.interfaces.ECPrivateKey {
        require(rawScalar.size == SCALAR_LENGTH_BYTES) {
            "P-256 private key scalar must be $SCALAR_LENGTH_BYTES bytes, got ${rawScalar.size}"
        }
        val params = ecParameterSpec()
        val s = BigInteger(1, rawScalar)
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(ECPrivateKeySpec(s, params)) as java.security.interfaces.ECPrivateKey
    }

    private fun decodePublicKey(rawPoint: ByteArray): java.security.interfaces.ECPublicKey {
        require(rawPoint.size == UNCOMPRESSED_POINT_LENGTH_BYTES && rawPoint[0] == 0x04.toByte()) {
            "P-256 public key must be a $UNCOMPRESSED_POINT_LENGTH_BYTES-byte uncompressed point starting with 0x04"
        }
        val x = BigInteger(1, rawPoint.copyOfRange(1, 1 + SCALAR_LENGTH_BYTES))
        val y = BigInteger(1, rawPoint.copyOfRange(1 + SCALAR_LENGTH_BYTES, 1 + 2 * SCALAR_LENGTH_BYTES))
        val params = ecParameterSpec()
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as java.security.interfaces.ECPublicKey
    }

    private fun encodeUncompressedPoint(point: ECPoint): ByteArray {
        val x = point.affineX.toFixedLengthBytes(SCALAR_LENGTH_BYTES)
        val y = point.affineY.toFixedLengthBytes(SCALAR_LENGTH_BYTES)
        return byteArrayOf(0x04) + x + y
    }

    private fun BigInteger.toFixedLengthBytes(length: Int): ByteArray {
        val raw = this.toByteArray()
        // BigInteger.toByteArray() may include a leading 0x00 sign byte, or be shorter
        // than `length` for small values; normalize to exactly `length` bytes.
        return when {
            raw.size == length                       -> raw
            raw.size == length + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < length                         -> ByteArray(length - raw.size) + raw
            else                                       -> throw IllegalStateException(
                "Value does not fit in $length bytes (got ${raw.size})"
            )
        }
    }

    private fun ecParameterSpec(): ECParameterSpec {
        val algorithmParameters = AlgorithmParameters.getInstance("EC")
        algorithmParameters.init(ECGenParameterSpec(CURVE_NAME))
        return algorithmParameters.getParameterSpec(ECParameterSpec::class.java)
    }

    // -- elliptic curve point multiplication (affine, double-and-add) ------------------
    //
    // The JCE's KeyFactory/KeyPairGenerator APIs don't expose "derive the public point
    // from an existing private scalar" directly, so this implements it directly against
    // the curve parameters (short Weierstrass form: y^2 = x^3 + a*x + b mod p). This is
    // only used for point multiplication with the *generator* point, never with an
    // externally-supplied point, so there's no risk of an invalid-curve-point attack here.

    private val POINT_AT_INFINITY = ECPoint(BigInteger.valueOf(-1), BigInteger.valueOf(-1))

    private fun isInfinity(point: ECPoint) = point == POINT_AT_INFINITY

    private fun multiplyPoint(point: ECPoint, scalar: BigInteger, params: ECParameterSpec): ECPoint {
        val p = (params.curve.field as java.security.spec.ECFieldFp).p
        val a = params.curve.a

        var result = POINT_AT_INFINITY
        var addend = point
        var k = scalar

        while (k.signum() > 0) {
            if (k.testBit(0)) {
                result = addPoints(result, addend, p, a)
            }
            addend = addPoints(addend, addend, p, a)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun addPoints(p1: ECPoint, p2: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
        if (isInfinity(p1)) return p2
        if (isInfinity(p2)) return p1

        val lambda: BigInteger
        if (p1.affineX == p2.affineX) {
            if ((p1.affineY.add(p2.affineY)).mod(p) == BigInteger.ZERO) {
                return POINT_AT_INFINITY // p1 == -p2
            }
            // point doubling: lambda = (3*x1^2 + a) / (2*y1) mod p
            val numerator = p1.affineX.pow(2).multiply(BigInteger.valueOf(3)).add(a).mod(p)
            val denominator = p1.affineY.multiply(BigInteger.TWO).mod(p)
            lambda = numerator.multiply(denominator.modInverse(p)).mod(p)
        } else {
            // point addition: lambda = (y2 - y1) / (x2 - x1) mod p
            val numerator = p2.affineY.subtract(p1.affineY).mod(p)
            val denominator = p2.affineX.subtract(p1.affineX).mod(p)
            lambda = numerator.multiply(denominator.modInverse(p)).mod(p)
        }

        val x3 = lambda.pow(2).subtract(p1.affineX).subtract(p2.affineX).mod(p)
        val y3 = lambda.multiply(p1.affineX.subtract(x3)).subtract(p1.affineY).mod(p)
        return ECPoint(x3, y3)
    }

    companion object {
        private const val CURVE_NAME = "secp256r1"
        private const val SCALAR_LENGTH_BYTES = 32
        private const val UNCOMPRESSED_POINT_LENGTH_BYTES = 65
    }
}
