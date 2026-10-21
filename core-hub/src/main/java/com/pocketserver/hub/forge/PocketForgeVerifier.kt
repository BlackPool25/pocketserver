package com.pocketserver.hub.forge

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays

/**
 * Minisign Ed25519 cryptographic verifier and SHA-256 integrity validator.
 *
 * Implements the Minisign signature format specification:
 *   - Ed25519 public key: [2 bytes algorithm "Ed"] + [8 bytes KeyID] + [32 bytes Raw PubKey]
 *   - Minisign signature:
 *       Line 1: untrusted comment: <comment>
 *       Line 2: Base64([2 bytes "Ed"] + [8 bytes KeyID] + [64 bytes Signature])
 *       Line 3: trusted comment: <comment>
 *       Line 4: Base64([64 bytes Global Signature over (raw sig record + trusted comment)])
 */
class PocketForgeVerifier {

    companion object {
        const val ALGORITHM_ED = "Ed"
        val ED_HEADER_BYTES = byteArrayOf(0x45.toByte(), 0x64.toByte()) // "Ed"

        // DER prefix for RFC 8410 Ed25519 SubjectPublicKeyInfo (12 bytes)
        // 30 2a 30 05 06 03 2b 65 70 03 21 00
        private val ED25519_X509_PREFIX = byteArrayOf(
            0x30.toByte(), 0x2a.toByte(), 0x30.toByte(), 0x05.toByte(),
            0x06.toByte(), 0x03.toByte(), 0x2b.toByte(), 0x65.toByte(),
            0x70.toByte(), 0x03.toByte(), 0x21.toByte(), 0x00.toByte()
        )
    }

    data class ParsedMinisignPublicKey(
        val keyId: ByteArray,
        val rawPublicKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedMinisignPublicKey) return false
            return keyId.contentEquals(other.keyId) && rawPublicKey.contentEquals(other.rawPublicKey)
        }

        override fun hashCode(): Int = 31 * Arrays.hashCode(keyId) + Arrays.hashCode(rawPublicKey)
    }

    data class ParsedMinisignSignature(
        val untrustedComment: String,
        val keyId: ByteArray,
        val signatureBytes: ByteArray,
        val trustedComment: String?,
        val globalSignatureBytes: ByteArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedMinisignSignature) return false
            return keyId.contentEquals(other.keyId) && signatureBytes.contentEquals(other.signatureBytes)
        }

        override fun hashCode(): Int = 31 * Arrays.hashCode(keyId) + Arrays.hashCode(signatureBytes)
    }

    /**
     * Decodes a Minisign base64 public key (42 bytes decoded: 2 bytes 'Ed', 8 bytes key ID, 32 bytes pubkey).
     */
    fun parsePublicKey(publicKeyBase64: String): ParsedMinisignPublicKey {
        val decoded = decodeBase64(publicKeyBase64.trim())
        if (decoded.size != 42) {
            throw IllegalArgumentException("Invalid Minisign public key length: ${decoded.size} bytes (expected 42)")
        }
        if (decoded[0] != ED_HEADER_BYTES[0] || decoded[1] != ED_HEADER_BYTES[1]) {
            throw IllegalArgumentException("Invalid Minisign public key algorithm prefix: expected 'Ed'")
        }
        val keyId = decoded.copyOfRange(2, 10)
        val rawKey = decoded.copyOfRange(10, 42)
        return ParsedMinisignPublicKey(keyId, rawKey)
    }

    /**
     * Parses a Minisign .minisig file string into its components.
     */
    fun parseSignature(signatureContent: String): ParsedMinisignSignature {
        val lines = signatureContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) {
            throw IllegalArgumentException("Invalid Minisign signature: expected at least 2 lines")
        }

        val untrustedComment = lines[0]
        val sigRecordBytes = decodeBase64(lines[1])
        if (sigRecordBytes.size != 74) {
            throw IllegalArgumentException("Invalid Minisign signature record length: ${sigRecordBytes.size} bytes (expected 74)")
        }
        if (sigRecordBytes[0] != ED_HEADER_BYTES[0] || sigRecordBytes[1] != ED_HEADER_BYTES[1]) {
            throw IllegalArgumentException("Invalid Minisign signature algorithm prefix: expected 'Ed'")
        }

        val keyId = sigRecordBytes.copyOfRange(2, 10)
        val sigBytes = sigRecordBytes.copyOfRange(10, 74)

        var trustedComment: String? = null
        var globalSigBytes: ByteArray? = null

        if (lines.size >= 4) {
            trustedComment = lines[2]
            val globalBytes = decodeBase64(lines[3])
            if (globalBytes.size == 64) {
                globalSigBytes = globalBytes
            }
        }

        return ParsedMinisignSignature(
            untrustedComment = untrustedComment,
            keyId = keyId,
            signatureBytes = sigBytes,
            trustedComment = trustedComment,
            globalSignatureBytes = globalSigBytes
        )
    }

    /**
     * Verifies the cryptographic Minisign signature of manifest content against the specified public key.
     */
    fun verifyManifestSignature(
        manifestContent: String,
        signatureContent: String,
        publicKeyBase64: String
    ): Boolean {
        return try {
            val pubKey = parsePublicKey(publicKeyBase64)
            val parsedSig = parseSignature(signatureContent)

            // Key ID must match
            if (!pubKey.keyId.contentEquals(parsedSig.keyId)) {
                return false
            }

            // Verify Ed25519 signature over manifest bytes
            val manifestBytes = manifestContent.toByteArray(Charsets.UTF_8)
            val isDataValid = verifyEd25519(pubKey.rawPublicKey, manifestBytes, parsedSig.signatureBytes)
            if (!isDataValid) {
                return false
            }

            // Verify global signature if trusted comment is present
            if (parsedSig.trustedComment != null && parsedSig.globalSignatureBytes != null) {
                val recordForGlobal = decodeBase64(signatureContent.lines().map { it.trim() }.filter { it.isNotEmpty() }[1])
                val commentBytes = parsedSig.trustedComment.toByteArray(Charsets.UTF_8)
                val combined = recordForGlobal + commentBytes
                val isGlobalValid = verifyEd25519(pubKey.rawPublicKey, combined, parsedSig.globalSignatureBytes)
                if (!isGlobalValid) {
                    return false
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifies SHA-256 hash of a file against expected lowercase hex string.
     */
    fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.exists() || !file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(65536)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            actualHash.lowercase().toByteArray(Charsets.UTF_8),
            expectedHash.trim().lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Computes the SHA-256 hash of a byte array as a lowercase hex string.
     */
    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the SHA-256 hash of a file as a lowercase hex string.
     */
    fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(65536)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyEd25519(rawPublicKey: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean {
        return try {
            val x509Bytes = ED25519_X509_PREFIX + rawPublicKey
            val keySpec = X509EncodedKeySpec(x509Bytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val pubKey = keyFactory.generatePublic(keySpec)

            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }

    private fun decodeBase64(input: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(input)
        } catch (e: Exception) {
            Base64.decode(input, Base64.DEFAULT)
        }
    }

    private fun encodeBase64(input: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(input)
        } catch (e: Exception) {
            Base64.encodeToString(input, Base64.NO_WRAP)
        }
    }

    // --- Helper Utilities for Testing / Manifest Publishing ---

    /**
     * Generates a new Minisign-compatible Ed25519 key pair for tests or community repository publishing.
     */
    fun generateKeyPairForTesting(): TestKeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val pair: KeyPair = kpg.generateKeyPair()

        // Extract raw 32-byte public key from X.509 encoding (last 32 bytes)
        val x509 = pair.public.encoded
        val rawPub = x509.copyOfRange(x509.size - 32, x509.size)

        // 8-byte deterministic or random Key ID
        val keyId = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val fullPubBytes = ED_HEADER_BYTES + keyId + rawPub
        val publicKeyBase64 = encodeBase64(fullPubBytes)

        return TestKeyPair(pair, keyId, publicKeyBase64)
    }

    data class TestKeyPair(
        val keyPair: KeyPair,
        val keyId: ByteArray,
        val publicKeyBase64: String
    ) {
        fun signManifest(manifestContent: String, trustedCommentText: String = "timestamp:1729500000"): String {
            val manifestBytes = manifestContent.toByteArray(Charsets.UTF_8)
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(keyPair.private)
            signer.update(manifestBytes)
            val sigBytes = signer.sign()

            val sigRecord = ED_HEADER_BYTES + keyId + sigBytes
            val sigRecordBase64 = try {
                java.util.Base64.getEncoder().encodeToString(sigRecord)
            } catch (e: Exception) {
                Base64.encodeToString(sigRecord, Base64.NO_WRAP)
            }

            val trustedCommentLine = "trusted comment: $trustedCommentText"
            val globalCombined = sigRecord + trustedCommentLine.toByteArray(Charsets.UTF_8)
            signer.initSign(keyPair.private)
            signer.update(globalCombined)
            val globalSigBytes = signer.sign()
            val globalSigBase64 = try {
                java.util.Base64.getEncoder().encodeToString(globalSigBytes)
            } catch (e: Exception) {
                Base64.encodeToString(globalSigBytes, Base64.NO_WRAP)
            }

            return """
                untrusted comment: signature from PocketForge key
                $sigRecordBase64
                $trustedCommentLine
                $globalSigBase64
            """.trimIndent()
        }
    }
}
