export type AdmissionDecision = "ALLOW" | "DENY" | "UNKNOWN";

export interface InstallSourceIdentity {
  installingPackageName: string | null;
  initiatingPackageName: string | null;
  originatingPackageName: string | null;
  packageSource: number | null;
  sourceReliable: boolean;
}

export interface PackageIdentity {
  packageName: string;
  versionCode: number;
  versionName: string | null;
  signingIdentitySha256: string[];
  installSource: InstallSourceIdentity;
}

export interface AuditPermission {
  name: string;
  granted: boolean;
  flags: number;
}

export interface AppAuditResult {
  schemaVersion: number;
  identity: PackageIdentity;
  appLabel: string;
  uid: number;
  requestedPermissions: AuditPermission[];
  exportedComponents: unknown[];
  intentCapabilities: unknown[];
  hasBrowsableActivity: boolean;
  hasBrowserCapability: boolean;
  hasVpnService: boolean;
  hasAccessibilityService: boolean;
  hasDeviceAdminReceiver: boolean;
  requestsInstallPackages: boolean;
  requestsSystemAlertWindow: boolean;
  overlayAppOpAllowed: boolean | null;
  queriesAllPackages: boolean;
  requestsUsageAccess: boolean;
  requestsWriteSettings: boolean;
  requestsManageExternalStorage: boolean;
  requestsDeletePackages: boolean;
  hasNotificationListenerService: boolean;
  hasInputMethodService: boolean;
  hasBootReceiver: boolean;
  bypassSignals: string[];
  collectionErrors: string[];
  [key: string]: unknown;
}

export interface EvaluateRequest {
  contractVersion: "admission-gate.v1";
  requestId: string;
  identity: PackageIdentity;
  audit: AppAuditResult;
}

export interface PlayMetadata {
  packageName: string;
  title: string | null;
  developer: string | null;
  description: string | null;
  category: string | null;
  marketSource: string | null;
  marketStatus: string | null;
  marketUrl: string | null;
  version: string | null;
  website: string | null;
  rawFields: Record<string, unknown>;
}

export interface SearchEvidence {
  query: string;
  title: string;
  url: string;
  description: string;
  extraSnippets: string[];
  sourceDomain: string | null;
}

export interface WebSearchBundle {
  provider: "brave-search";
  queries: string[];
  results: SearchEvidence[];
  checkedAtEpochMs: number;
}

export interface ClassifierEvidence {
  type: string;
  source: string | null;
  detail: string;
  reference: string | null;
}

export interface ClassifierResult {
  decision: AdmissionDecision;
  reasonCodes: string[];
  summary: string;
  evidence: ClassifierEvidence[];
}

export interface ProviderOutcome<T> {
  available: boolean;
  value: T | null;
  errorCode: string | null;
}

export interface EvaluationDependencies {
  play: ProviderOutcome<PlayMetadata>;
  web: ProviderOutcome<WebSearchBundle>;
  ai: ProviderOutcome<ClassifierResult>;
}

export interface ReputationResponse {
  contractVersion: "admission-gate.v1";
  identity: PackageIdentity;
  webReputation: {
    verdict: string;
    score: number | null;
    categories: string[];
    source: string;
    checkedAtEpochMs: number;
  } | null;
  classificationDecision: AdmissionDecision;
  reasonCodes: string[];
  summary: string;
  evidence: ClassifierEvidence[];
}

export const unavailable = <T>(errorCode: string): ProviderOutcome<T> => ({
  available: false,
  value: null,
  errorCode,
});
