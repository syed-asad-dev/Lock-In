import type { AppAuditResult, EvaluateRequest, PackageIdentity } from "../src/types.js";

export const identity: PackageIdentity = {
  packageName: "com.example.calculator", versionCode: 42, versionName: "4.2",
  signingIdentitySha256: ["a".repeat(64)],
  installSource: { installingPackageName: "com.android.vending", initiatingPackageName: null, originatingPackageName: null, packageSource: 2, sourceReliable: true },
};

export function audit(overrides: Partial<AppAuditResult> = {}): AppAuditResult {
  return {
    schemaVersion: 1, identity, appLabel: "Calculator Pro", uid: 10042,
    requestedPermissions: [], exportedComponents: [], intentCapabilities: [],
    hasBrowsableActivity: false, hasBrowserCapability: false, hasVpnService: false,
    hasAccessibilityService: false, hasDeviceAdminReceiver: false, requestsInstallPackages: false,
    requestsSystemAlertWindow: false, overlayAppOpAllowed: null, queriesAllPackages: false,
    requestsUsageAccess: false, requestsWriteSettings: false, requestsManageExternalStorage: false,
    requestsDeletePackages: false, hasNotificationListenerService: false, hasInputMethodService: false,
    hasBootReceiver: false, bypassSignals: [], collectionErrors: [], ...overrides,
  };
}

export function request(overrides: Partial<EvaluateRequest> = {}): EvaluateRequest {
  return { contractVersion: "admission-gate.v1", requestId: "123e4567-e89b-12d3-a456-426614174000", identity, audit: audit(), ...overrides };
}
