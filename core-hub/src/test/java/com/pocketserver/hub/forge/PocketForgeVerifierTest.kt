package com.pocketserver.hub.forge

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PocketForgeVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var verifier: PocketForgeVerifier

    @Before
    fun setUp() {
        verifier = PocketForgeVerifier()
    }

    @Test
    fun testValidMinisignSignatureVerification() {
        val testKeyPair = verifier.generateKeyPairForTesting()
        val manifestContent = """
            {
              "repository": "PocketForge-Community",
              "packages": [
                {
                  "id": "ollama-arm64",
                  "version": "0.3.12"
                }
              ]
            }
        """.trimIndent()

        val signatureContent = testKeyPair.signManifest(manifestContent, "timestamp:1729500000")

        val isValid = verifier.verifyManifestSignature(
            manifestContent = manifestContent,
            signatureContent = signatureContent,
            publicKeyBase64 = testKeyPair.publicKeyBase64
        )

        assertThat(isValid).isTrue()
    }

    @Test
    fun testTamperRejectionWhenManifestModified() {
        val testKeyPair = verifier.generateKeyPairForTesting()
        val originalManifest = """{"repository": "Safe-Repo", "packages": []}"""
        val signatureContent = testKeyPair.signManifest(originalManifest)

        // Modify manifest payload (attacker tampering)
        val tamperedManifest = """{"repository": "Safe-Repo", "packages": [{"id": "backdoor"}]}"""

        val isValid = verifier.verifyManifestSignature(
            manifestContent = tamperedManifest,
            signatureContent = signatureContent,
            publicKeyBase64 = testKeyPair.publicKeyBase64
        )

        assertThat(isValid).isFalse()
    }

    @Test
    fun testTamperRejectionWithMismatchedKey() {
        val keyPair1 = verifier.generateKeyPairForTesting()
        val keyPair2 = verifier.generateKeyPairForTesting()

        val manifest = """{"repository": "Test-Repo"}"""
        val signatureSignedWithKey1 = keyPair1.signManifest(manifest)

        // Attempt verification with Key 2
        val isValid = verifier.verifyManifestSignature(
            manifestContent = manifest,
            signatureContent = signatureSignedWithKey1,
            publicKeyBase64 = keyPair2.publicKeyBase64
        )

        assertThat(isValid).isFalse()
    }

    @Test
    fun testSha256VerificationAndTamperDetection() {
        val file = tempFolder.newFile("sample-archive.tar.zst")
        val content = "PocketServer Archive Payload 123456789".toByteArray(Charsets.UTF_8)
        file.writeBytes(content)

        val computedHash = verifier.computeFileSha256(file)
        assertThat(computedHash).isNotEmpty()

        // Exact match
        assertThat(verifier.verifySha256(file, computedHash)).isTrue()

        // Tampered file
        file.writeBytes("PocketServer Tampered Malicious Payload".toByteArray(Charsets.UTF_8))
        assertThat(verifier.verifySha256(file, computedHash)).isFalse()
    }
}
