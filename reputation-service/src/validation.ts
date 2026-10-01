import type { EvaluateRequest, InstallSourceIdentity, PackageIdentity } from "./types.js";

export const MAX_REQUEST_BYTES = 1024 * 1024;

export function parseEvaluateRequest(value: unknown): EvaluateRequest | null {
  if (!isRecord(value) || value.contractVersion !== "admission-gate.v1" || !isUuid(value.requestId)) return null;
  const identity = parseIdentity(value.identity);
  const audit = isRecord(value.audit) ? parseAudit(value.audit) : null;
  if (!identity || !audit) return null;
  if (audit.identity.packageName !== identity.packageName || audit.identity.versionCode !== identity.versionCode) return null;
  if (!sameSet(audit.identity.signingIdentitySha256, identity.signingIdentitySha256)) return null;
  return { contractVersion: "admission-gate.v1", requestId: value.requestId, identity, audit };
}

function parseIdentity(value: unknown): PackageIdentity | null {
  if (!isRecord(value) || !isPackageName(value.packageName) || !isSafeInt(value.versionCode) || value.versionCode < 0) return null;
  if (!Array.isArray(value.signingIdentitySha256) || value.signingIdentitySha256.length === 0 || value.signingIdentitySha256.some((v) => typeof v !== "string" || !/^[a-f0-9]{64}$/i.test(v))) return null;
  const installSource = parseInstallSource(value.installSource);
  if (!installSource) return null;
  return {
    packageName: value.packageName,
    versionCode: value.versionCode,
    versionName: nullableString(value.versionName),
    signingIdentitySha256: value.signingIdentitySha256.map((v) => v.toLowerCase()),
    installSource,
  };
}

function parseInstallSource(value: unknown): InstallSourceIdentity | null {
  if (!isRecord(value) || typeof value.sourceReliable !== "boolean") return null;
  if (value.packageSource !== null && value.packageSource !== undefined && !isSafeInt(value.packageSource)) return null;
  return {
    installingPackageName: nullablePackageName(value.installingPackageName),
    initiatingPackageName: nullablePackageName(value.initiatingPackageName),
    originatingPackageName: nullablePackageName(value.originatingPackageName),
    packageSource: typeof value.packageSource === "number" ? value.packageSource : null,
    sourceReliable: value.sourceReliable,
  };
}

function parseAudit(value: Record<string, unknown>): EvaluateRequest["audit"] | null {
  const identity = parseIdentity(value.identity);
  if (!identity || typeof value.appLabel !== "string" || !Array.isArray(value.requestedPermissions) || !Array.isArray(value.bypassSignals) || !Array.isArray(value.collectionErrors)) return null;
  return {
    ...value,
    schemaVersion: typeof value.schemaVersion === "number" ? value.schemaVersion : 1,
    identity,
    appLabel: value.appLabel.slice(0, 500),
    uid: typeof value.uid === "number" ? value.uid : -1,
    requestedPermissions: value.requestedPermissions.slice(0, 256).filter(isRecord).map((permission) => ({
      name: typeof permission.name === "string" ? permission.name.slice(0, 255) : "unknown",
      granted: Boolean(permission.granted),
      flags: typeof permission.flags === "number" && Number.isSafeInteger(permission.flags) ? permission.flags : 0,
    })),
    exportedComponents: Array.isArray(value.exportedComponents) ? value.exportedComponents.slice(0, 256) : [],
    intentCapabilities: Array.isArray(value.intentCapabilities) ? value.intentCapabilities.slice(0, 128) : [],
    hasBrowsableActivity: Boolean(value.hasBrowsableActivity),
    hasBrowserCapability: Boolean(value.hasBrowserCapability),
    hasVpnService: Boolean(value.hasVpnService),
    hasAccessibilityService: Boolean(value.hasAccessibilityService),
    hasDeviceAdminReceiver: Boolean(value.hasDeviceAdminReceiver),
    requestsInstallPackages: Boolean(value.requestsInstallPackages),
    requestsSystemAlertWindow: Boolean(value.requestsSystemAlertWindow),
    overlayAppOpAllowed: typeof value.overlayAppOpAllowed === "boolean" ? value.overlayAppOpAllowed : null,
    queriesAllPackages: Boolean(value.queriesAllPackages),
    requestsUsageAccess: Boolean(value.requestsUsageAccess),
    requestsWriteSettings: Boolean(value.requestsWriteSettings),
    requestsManageExternalStorage: Boolean(value.requestsManageExternalStorage),
    requestsDeletePackages: Boolean(value.requestsDeletePackages),
    hasNotificationListenerService: Boolean(value.hasNotificationListenerService),
    hasInputMethodService: Boolean(value.hasInputMethodService),
    hasBootReceiver: Boolean(value.hasBootReceiver),
    bypassSignals: value.bypassSignals.filter((v): v is string => typeof v === "string").slice(0, 128),
    collectionErrors: value.collectionErrors.filter((v): v is string => typeof v === "string").slice(0, 128),
  };
}

function isRecord(value: unknown): value is Record<string, any> { return typeof value === "object" && value !== null && !Array.isArray(value); }
function isSafeInt(value: unknown): value is number { return typeof value === "number" && Number.isSafeInteger(value); }
function isPackageName(value: unknown): value is string { return typeof value === "string" && /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(value) && value.length <= 255; }
function nullableString(value: unknown): string | null { return value === null || value === undefined ? null : typeof value === "string" && value.length <= 500 ? value : null; }
function nullablePackageName(value: unknown): string | null { return value === null || value === undefined ? null : isPackageName(value) ? value : null; }
function isUuid(value: unknown): value is string { return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value); }
function sameSet(left: string[], right: string[]): boolean { const a = new Set(left.map((v) => v.toLowerCase())); const b = new Set(right.map((v) => v.toLowerCase())); return a.size === b.size && [...a].every((v) => b.has(v)); }
