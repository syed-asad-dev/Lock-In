package com.app.lockin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionIdentityVerifierTest {
    private fun identity(
        versionCode: Long = 7L,
        signer: List<String> = listOf("AA11"),
        installer: String? = "com.android.vending",
        reliableSource: Boolean = true
    ) = PackageIdentity(
        packageName = "com.example.app",
        versionCode = versionCode,
        versionName = "7.0",
        signingIdentitySha256 = signer,
        installSource = InstallSourceIdentity(
            installingPackageName = installer,
            initiatingPackageName = null,
            originatingPackageName = null,
            packageSource = 2,
            sourceReliable = reliableSource
        )
    )

    @Test
    fun sameSignerAndReliableInstallerMatchWithoutRequiringVersion() {
        val result = AdmissionIdentityVerifier.validate(
            expected = identity(versionCode = 6L),
            actual = identity(versionCode = 7L),
            requireVersionMatch = false
        )

        assertTrue(result.isMatch)
    }

    @Test
    fun installerMismatchFailsClosed() {
        val result = AdmissionIdentityVerifier.validate(
            expected = identity(installer = "com.android.vending"),
            actual = identity(installer = "com.example.sideload"),
            requireVersionMatch = false
        )

        assertFalse(result.isMatch)
        assertTrue(result.reasonCodes.contains("IDENTITY_INSTALLER_MISMATCH"))
    }

    @Test
    fun missingSigningIdentityNeverMatches() {
        val result = AdmissionIdentityVerifier.validate(
            expected = identity(signer = emptyList()),
            actual = identity(),
            requireVersionMatch = false
        )

        assertFalse(result.isMatch)
        assertTrue(result.reasonCodes.contains("IDENTITY_SIGNING_UNAVAILABLE"))
    }

    @Test
    fun backendAllowIdentityRequiresExactVersion() {
        val result = AdmissionIdentityVerifier.validate(
            expected = identity(versionCode = 7L),
            actual = identity(versionCode = 8L),
            requireVersionMatch = true
        )

        assertFalse(result.isMatch)
        assertTrue(result.reasonCodes.contains("IDENTITY_VERSION_MISMATCH"))
    }

    @Test
    fun newlyAvailableInstallSourceDoesNotMatchAnUnreliableIdentity() {
        val result = AdmissionIdentityVerifier.validate(
            expected = identity(installer = null, reliableSource = false),
            actual = identity(installer = "com.example.sideload", reliableSource = true),
            requireVersionMatch = false
        )

        assertFalse(result.isMatch)
        assertTrue(result.reasonCodes.contains("IDENTITY_INSTALL_SOURCE_NEWLY_AVAILABLE"))
    }

    @Test
    fun newlyPopulatedReliableSourceFieldFailsClosed() {
        val expected = identity().copy(
            installSource = identity().installSource.copy(initiatingPackageName = null)
        )
        val actual = expected.copy(
            installSource = expected.installSource.copy(initiatingPackageName = "com.example.installer")
        )
        val result = AdmissionIdentityVerifier.validate(expected, actual, requireVersionMatch = false)

        assertFalse(result.isMatch)
        assertTrue(result.reasonCodes.contains("IDENTITY_INITIATOR_MISMATCH"))
    }
}
