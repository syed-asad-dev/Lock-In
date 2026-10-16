import type { EvaluateRequest, InstallSourceIdentity, PackageIdentity } from "./types.js";

export const MAX_REQUEST_BYTES = 1024 * 1024;

export function parseEvaluateRequest(value: unknown): EvaluateRequest | null {
  if (!isRecord(value) || value.contractVersion !== "admission-gate.v1" || !isUuid(value.requestId)) return null;
  const identity = parseIdentity(value.identity);
  const audit = isRecord(value.audit) ? parseAudit(value.audit) : null;
  if (!identity || !audit) return null;
  if (!sameIdentity(audit.identity, identity)) return null;
  return { contractVersion: "admission-gate.v1", requestId: value.requestId, identity, audit };
}

function parseIdentity(value: unknown): PackageIdentity | null {
  if (!isRecord(value) || !isPackageName(value.packageName) || !isSafeInt(value.versionCode) || value.versionCode < 0) return null;
  if (!Object.prototype.hasOwnProperty.call(value, "versionName") ||
      (value.versionName !== null && (typeof value.versionName !== "string" || value.versionName.length > 500))) return null;
  if (!Array.isArray(value.signingIdentitySha256) || value.signingIdentitySha256.length === 0 ||
      value.signingIdentitySha256.some((v) => typeof v !== "string" || !/^[a-f0-9]{64}$/i.test(v))) return null;
  const signers = value.signingIdentitySha256.map((v) => v.toLowerCase());
  if (new Set(signers).size !== signers.length) return null;
  const installSource = parseInstallSource(value.installSource);
  if (!installSource) return null;
  return {
    packageName: value.packageName,
    versionCode: value.versionCode,
    versionName: value.versionName,
    signingIdentitySha256: signers,
    installSource,
  };
}

function parseInstallSource(value: unknown): InstallSourceIdentity | null {
  if (!isRecord(value) || typeof value.sourceReliable !== "boolean") return null;
  const fields = ["installingPackageName", "initiatingPackageName", "originatingPackageName", "packageSource"];
  if (fields.some((field) => !Object.prototype.hasOwnProperty.call(value, field))) return null;
  if (!nullablePackageNameStrict(value.installingPackageName) ||
      !nullablePackageNameStrict(value.initiatingPackageName) ||
      !nullablePackageNameStrict(value.originatingPackageName)) return null;
  if (value.packageSource !== null && (!isSafeInt(value.packageSource) || value.packageSource < 0)) return null;
  if (value.sourceReliable && value.installingPackageName === null && value.initiatingPackageName === null &&
      value.originatingPackageName === null && value.packageSource === null) return null;
  return {
    installingPackageName: value.installingPackageName as string | null,
    initiatingPackageName: value.initiatingPackageName as string | null,
    originatingPackageName: value.originatingPackageName as string | null,
    packageSource: value.packageSource as number | null,
    sourceReliable: value.sourceReliable,
  };
}

function parseAudit(value: Record<string, unknown>): EvaluateRequest["audit"] | null {
  const identity = parseIdentity(value.identity);
  const permissions = value.requestedPermissions;
  const exportedComponents = value.exportedComponents;
  const intentCapabilities = value.intentCapabilities;
  const bypassSignals = value.bypassSignals;
  const collectionErrors = value.collectionErrors;
  if (!identity || typeof value.appLabel !== "string" || value.appLabel.length > 500 ||
      !isSafeInt(value.schemaVersion) || value.schemaVersion < 1 ||
      !isSafeInt(value.uid) || value.uid < 0 ||
      !Array.isArray(permissions) || permissions.length > 256 ||
      !Array.isArray(exportedComponents) || exportedComponents.length > 256 ||
      !Array.isArray(intentCapabilities) || intentCapabilities.length > 128 ||
      !Array.isArray(bypassSignals) || bypassSignals.length > 128 ||
      !Array.isArray(collectionErrors) || collectionErrors.length > 128 ||
      permissions.some((permission) => !isRecord(permission) || typeof permission.name !== "string" || !permission.name.trim() ||
        permission.name.length > 255 || typeof permission.granted !== "boolean" || !isSafeInt(permission.flags)) ||
      exportedComponents.some((component) => !isRecord(component)) ||
      intentCapabilities.some((capability) => !isRecord(capability)) ||
      bypassSignals.some((signal) => typeof signal !== "string" || !signal.trim()) ||
      collectionErrors.some((error) => typeof error !== "string" || !error.trim())) return null;

  const booleanFields = [
    "hasBrowsableActivity", "hasBrowserCapability", "hasVpnService", "hasAccessibilityService",
    "hasDeviceAdminReceiver", "requestsInstallPackages", "requestsSystemAlertWindow", "queriesAllPackages",
    "requestsUsageAccess", "requestsWriteSettings", "requestsManageExternalStorage", "requestsDeletePackages",
    "hasNotificationListenerService", "hasInputMethodService", "hasBootReceiver",
  ];
  if (booleanFields.some((field) => typeof value[field] !== "boolean")) return null;
  if (value.overlayAppOpAllowed !== null && typeof value.overlayAppOpAllowed !== "boolean") return null;
  if (!Object.prototype.hasOwnProperty.call(value, "overlayAppOpAllowed")) return null;
  return {
    ...value,
    schemaVersion: value.schemaVersion,
    identity,
    appLabel: value.appLabel,
    uid: value.uid,
    requestedPermissions: permissions.map((permission) => permission as { name: string; granted: boolean; flags: number }),
    exportedComponents,
    intentCapabilities,
    hasBrowsableActivity: value.hasBrowsableActivity as boolean,
    hasBrowserCapability: value.hasBrowserCapability as boolean,
    hasVpnService: value.hasVpnService as boolean,
    hasAccessibilityService: value.hasAccessibilityService as boolean,
    hasDeviceAdminReceiver: value.hasDeviceAdminReceiver as boolean,
    requestsInstallPackages: value.requestsInstallPackages as boolean,
    requestsSystemAlertWindow: value.requestsSystemAlertWindow as boolean,
    overlayAppOpAllowed: value.overlayAppOpAllowed as boolean | null,
    queriesAllPackages: value.queriesAllPackages as boolean,
    requestsUsageAccess: value.requestsUsageAccess as boolean,
    requestsWriteSettings: value.requestsWriteSettings as boolean,
    requestsManageExternalStorage: value.requestsManageExternalStorage as boolean,
    requestsDeletePackages: value.requestsDeletePackages as boolean,
    hasNotificationListenerService: value.hasNotificationListenerService as boolean,
    hasInputMethodService: value.hasInputMethodService as boolean,
    hasBootReceiver: value.hasBootReceiver as boolean,
    bypassSignals,
    collectionErrors,
  };
}

function isRecord(value: unknown): value is Record<string, any> { return typeof value === "object" && value !== null && !Array.isArray(value); }
function isSafeInt(value: unknown): value is number { return typeof value === "number" && Number.isSafeInteger(value); }
function isPackageName(value: unknown): value is string { return typeof value === "string" && /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+$/.test(value) && value.length <= 255; }
function nullablePackageNameStrict(value: unknown): value is string | null { return value === null || isPackageName(value); }
function isUuid(value: unknown): value is string { return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value); }
function sameIdentity(left: PackageIdentity, right: PackageIdentity): boolean {
  return left.packageName === right.packageName && left.versionCode === right.versionCode &&
    left.versionName === right.versionName && sameSet(left.signingIdentitySha256, right.signingIdentitySha256) &&
    left.installSource.installingPackageName === right.installSource.installingPackageName &&
    left.installSource.initiatingPackageName === right.installSource.initiatingPackageName &&
    left.installSource.originatingPackageName === right.installSource.originatingPackageName &&
    left.installSource.packageSource === right.installSource.packageSource &&
    left.installSource.sourceReliable === right.installSource.sourceReliable;
}
function sameSet(left: string[], right: string[]): boolean { const a = new Set(left.map((v) => v.toLowerCase())); const b = new Set(right.map((v) => v.toLowerCase())); return a.size === b.size && [...a].every((v) => b.has(v)); }
