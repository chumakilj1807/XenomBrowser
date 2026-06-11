package com.xenombrowser.airplay

import android.content.Context
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.SecureRandom

class AirPlayCrypto(context: Context) {

    // Long-term Ed25519 device identity — persisted across reboots
    val ltEdPriv: Ed25519PrivateKeyParameters
    val ltEdPub:  Ed25519PublicKeyParameters

    // SRP per-session state
    private var srpServer: SRP6Server?  = null
    private var srpB:      BigInteger?  = null

    // Pair-Verify per-session state
    private var pvMyPriv:    X25519PrivateKeyParameters? = null
    var pvMyPub:             X25519PublicKeyParameters?  = null
        private set
    private var pvShared:    ByteArray? = null
    private var pvClientPub: ByteArray? = null

    companion object {
        // RFC 5054 §A.4 — 3072-bit group
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
            "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
            "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
            "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF" +
            "FFFFFFFF", 16)
        val G = BigInteger.valueOf(5)

        const val PIN          = "3939"
        const val PREFS_NAME   = "airplay_keys"
        const val KEY_ED_PRIV  = "ed_priv"
        const val KEY_ED_PUB   = "ed_pub"

        fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun hexToBytes(h: String)    = ByteArray(h.length / 2) { h.substring(it*2, it*2+2).toInt(16).toByte() }
    }

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privHex = prefs.getString(KEY_ED_PRIV, null)
        val pubHex  = prefs.getString(KEY_ED_PUB,  null)
        if (privHex != null && pubHex != null) {
            ltEdPriv = Ed25519PrivateKeyParameters(hexToBytes(privHex), 0)
            ltEdPub  = Ed25519PublicKeyParameters(hexToBytes(pubHex), 0)
        } else {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val kp = gen.generateKeyPair()
            ltEdPriv = kp.private as Ed25519PrivateKeyParameters
            ltEdPub  = kp.public  as Ed25519PublicKeyParameters
            prefs.edit()
                .putString(KEY_ED_PRIV, bytesToHex(ltEdPriv.encoded))
                .putString(KEY_ED_PUB,  bytesToHex(ltEdPub.encoded))
                .apply()
        }
    }

    // ── Pair-Setup (SRP-6a) ──────────────────────────────────────────────

    /** Returns (salt, B). Call before srp-setup M3. */
    fun srpBegin(): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

        val vg = SRP6VerifierGenerator()
        vg.init(N, G, SHA512Digest())
        val verifier = vg.generateVerifier(salt, "Pair-Setup".toByteArray(), PIN.toByteArray())

        srpServer = SRP6Server()
        srpServer!!.init(N, G, verifier, SHA512Digest(), SecureRandom())
        srpB = srpServer!!.generateServerCredentials()

        return Pair(salt, toFixedBytes(srpB!!, 384))
    }

    /** Verify client M1; returns M2 on success, null on failure. */
    fun srpVerify(clientA: ByteArray, clientM1: ByteArray): ByteArray? {
        val srv = srpServer ?: return null
        return try {
            // calculateSecret stores S internally
            srv.calculateSecret(BigInteger(1, clientA))
            // verifyClientEvidenceMessage returns Boolean in BC 1.77
            if (!srv.verifyClientEvidenceMessage(BigInteger(1, clientM1))) return null
            val M2 = srv.calculateServerEvidenceMessage()
            toFixedBytes(M2, 64)
        } catch (_: Exception) { null }
    }

    // ── Pair-Verify (X25519 + Ed25519 + ChaCha20-Poly1305) ───────────────

    /** Phase 1: iOS sends its Curve25519 pub. We return our pub + encrypted sub-TLV. */
    fun pvPhase1(iosCurvePub: ByteArray): ByteArray {
        pvClientPub = iosCurvePub

        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        pvMyPriv = kp.private as X25519PrivateKeyParameters
        pvMyPub  = kp.public  as X25519PublicKeyParameters

        val agree = X25519Agreement()
        agree.init(pvMyPriv)
        val shared = ByteArray(agree.agreementSize)
        agree.calculateAgreement(X25519PublicKeyParameters(iosCurvePub, 0), shared, 0)
        pvShared = shared

        val encKey = hkdf(shared, "Pair-Verify-Encrypt-Salt".toByteArray(),
                                  "Pair-Verify-Encrypt-Info".toByteArray(), 32)

        // Sign device_curve_pub || ios_curve_pub
        val msg = pvMyPub!!.encoded + iosCurvePub
        val signer = Ed25519Signer()
        signer.init(true, ltEdPriv)
        signer.update(msg, 0, msg.size)
        val sig = signer.generateSignature()

        // Encrypt sub-TLV: identifier(device_ed_pub) || signature
        val plain = ltEdPub.encoded + sig
        val nonce = "PV-Msg02".toByteArray().let { ByteArray(4) + it }
        return chaEncrypt(encKey, nonce, plain)
    }

    /** Phase 2: decrypt iOS sub-TLV, verify its Ed25519 signature. Returns true on success. */
    fun pvPhase2(encrypted: ByteArray): Boolean {
        val shared    = pvShared    ?: return false
        val iosCurve  = pvClientPub ?: return false

        val encKey = hkdf(shared, "Pair-Verify-Encrypt-Salt".toByteArray(),
                                  "Pair-Verify-Encrypt-Info".toByteArray(), 32)
        val nonce = "PV-Msg03".toByteArray().let { ByteArray(4) + it }
        val plain = chaDecrypt(encKey, nonce, encrypted) ?: return false

        if (plain.size < 96) return false
        val iosEdPub = plain.copyOfRange(0, 32)
        val iosSig   = plain.copyOfRange(32, 96)

        val toVerify = iosCurve + pvMyPub!!.encoded
        val ver = Ed25519Signer()
        ver.init(false, Ed25519PublicKeyParameters(iosEdPub, 0))
        ver.update(toVerify, 0, toVerify.size)
        return ver.verifySignature(iosSig)
    }

    // ── Crypto primitives ────────────────────────────────────────────────

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(outLen).also { gen.generateBytes(it, 0, outLen) }
    }

    fun chaEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun chaDecrypt(key: ByteArray, nonce: ByteArray, cipher: ByteArray): ByteArray? = try {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(c.getOutputSize(cipher.size))
        val n = c.processBytes(cipher, 0, cipher.size, out, 0)
        c.doFinal(out, n)
        out.copyOfRange(0, out.size)
    } catch (_: Exception) { null }

    private fun toFixedBytes(n: BigInteger, size: Int): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == size     -> raw
            raw.size > size      -> raw.copyOfRange(raw.size - size, raw.size)
            else                 -> ByteArray(size - raw.size) + raw
        }
    }

    private operator fun ByteArray.plus(other: ByteArray) =
        ByteArray(size + other.size).also { copyInto(it); other.copyInto(it, size) }
}
