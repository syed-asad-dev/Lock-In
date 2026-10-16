package com.app.lockin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionPersistenceTest {
    @Test
    fun failedApprovalPersistenceTransitionsApprovalToPendingFailClosed() {
        val approved = record("com.example.approved", AdmissionStatus.APPROVED)
        val denied = record("com.example.denied", AdmissionStatus.DENIED)
        val state = AdmissionGateState(
            schemaVersion = 2,
            snapshotId = "snapshot",
            grandfatheringInitialized = true,
            grandfatheredAtEpochMs = 1L,
            records = mutableMapOf(approved.packageName to approved, denied.packageName to denied)
        )

        val safe = failClosedAdmissionStateAfterPersistenceFailure(state)

        assertEquals(AdmissionStatus.PENDING, safe.records.getValue(approved.packageName).status)
        assertEquals(ClassificationDecision.UNKNOWN, safe.records.getValue(approved.packageName).finalDecision)
        assertNull(safe.records.getValue(approved.packageName).externalResult)
        assertEquals(AdmissionStatus.DENIED, safe.records.getValue(denied.packageName).status)
    }

    @Test
    fun onlyMatchingNonEmptyCommitMarkerCanTrustSnapshot() {
        assertTrue(isAdmissionSnapshotCommitTrusted("commit-1", "commit-1"))
        assertFalse(isAdmissionSnapshotCommitTrusted("commit-1", "commit-2"))
        assertFalse(isAdmissionSnapshotCommitTrusted("commit-1", null))
        assertFalse(isAdmissionSnapshotCommitTrusted(null, "commit-1"))
    }

    private fun record(packageName: String, status: AdmissionStatus) = AdmissionGateRecord(
        packageName = packageName,
        status = status,
        signingCertDigest = "AA11",
        installerPackageName = "com.android.vending",
        versionCode = 1L,
        versionName = "1.0",
        appLabel = packageName,
        installedAtEpochMs = 1L,
        statusUpdatedAtEpochMs = 1L,
        finalDecision = when (status) {
            AdmissionStatus.APPROVED -> ClassificationDecision.ALLOW
            AdmissionStatus.DENIED -> ClassificationDecision.DENY
            AdmissionStatus.PENDING -> ClassificationDecision.UNKNOWN
        }
    )
}
