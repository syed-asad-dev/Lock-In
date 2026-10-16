import { registrableDomainOf } from "./providers/brave.js";
import type {
  AdmissionDecision,
  AppAuditResult,
  ClassifierEvidence,
  EvaluationDependencies,
  EvaluateRequest,
  ReputationResponse,
  SearchEvidence,
} from "./types.js";

const HARD_BLOCKED_PACKAGES = new Set([
  "com.innersloth.spacemafia",
  "com.nordvpn.android",
  "ch.protonvpn.android",
  "com.expressvpn.vpn",
  "com.surfshark.vpnclient.android",
  "com.tunnelbear.android",
  "com.windscribe.vpn",
  "com.hotspotshield.android.vpn",
  "com.cyberghost.vpn",
  "com.ppure.vpn.proxy.puffin",
  "com.fast.free.unblock.secure.vpn",
  "com.free.vpn.unblock.proxy.turbovpn",
  "com.brave.browser",
  "org.mozilla.firefox",
  "org.mozilla.firefox_beta",
  "org.mozilla.fenix",
  "org.mozilla.focus",
  "com.microsoft.emmx",
  "com.opera.browser",
  "com.opera.mini.native",
  "com.opera.gx",
  "com.duckduckgo.mobile.android",
  "com.puffinbrowser.android.puffin",
  "com.sec.android.app.sbrowser",
  "com.sec.android.app.sbrowser.beta",
  "com.vivaldi.browser",
  "com.UCMobile.intl",
  "com.cloudmosa.puffinFree",
  "com.mbrowser.maxton",
  "com.android.browser",
  "com.google.android.browser",
]);

const PROHIBITED_TERMS = [
  /\bbrowser\b/i,
  /private\s*(?:\/|and)?\s*(?:incognito|browsing)/i,
  /video\s*downloader/i,
  /download(?:s|ing)?\s+videos?/i,
  /\bvpn\b/i,
  /\bproxy\b/i,
  /bypass(?:es|ing)?\s+(?:filter|restriction|block)/i,
  /unblock(?:er|ing)?/i,
];

const SUSPICIOUS_LOCAL_SIGNALS = new Set([
  "ACCESSIBILITY_SERVICE",
  "DEVICE_ADMIN_RECEIVER",
  "REQUEST_INSTALL_PACKAGES",
  "SYSTEM_ALERT_WINDOW",
  "QUERY_ALL_PACKAGES",
  "PACKAGE_USAGE_STATS",
  "WRITE_SETTINGS",
  "MANAGE_EXTERNAL_STORAGE",
  "REQUEST_DELETE_PACKAGES",
  "NOTIFICATION_LISTENER",
  "INPUT_METHOD_SERVICE",
]);

export function evaluateAdmission(request: EvaluateRequest, dependencies: EvaluationDependencies): ReputationResponse {
  const identity = request.identity;
  const now = Date.now();
  const evidence: ClassifierEvidence[] = [];
  const reasons: string[] = [];
  const audit = request.audit;

  if (HARD_BLOCKED_PACKAGES.has(identity.packageName)) {
    return response(identity, "DENY", ["HARD_BLOCKED_PACKAGE"], "The package is on Lock-In's authoritative prohibited package list.", [
      evidenceItem("policy", "lock-in", "Exact package name matches the authoritative hard block list.", identity.packageName),
    ], null);
  }

  if (!identityMatchesAudit(request)) {
    return response(identity, "UNKNOWN", ["IDENTITY_LOCAL_AUDIT_MISMATCH"], "The request identity does not match its local audit.", [], null);
  }

  const localCapabilityReasons = localHardCapabilityReasons(audit);
  if (localCapabilityReasons.length > 0) {
    return response(identity, "DENY", localCapabilityReasons, "Local package capabilities establish prohibited browser or network-bypass functionality.", [
      evidenceItem("local-audit", "android-package-manager", `Local audit signals: ${localCapabilityReasons.join(", ")}.`, null),
    ], null);
  }

  const play = dependencies.play.value;
  if (dependencies.play.available && play && play.packageName !== identity.packageName) {
    return response(identity, "UNKNOWN", ["PLAY_PACKAGE_IDENTITY_MISMATCH"], "Store metadata did not identify the exact installed package.", [], null);
  }

  const search = dependencies.web.value;
  const hasAuthoritativePlayIdentity = Boolean(
    dependencies.play.available &&
    play?.packageName === identity.packageName &&
    play.marketSource?.toUpperCase() === "GOOGLE" &&
    play.marketStatus?.toUpperCase() === "PUBLISHED" &&
    isExactPlayMarketUrl(play.marketUrl, identity.packageName)
  );
  const prohibitedPlayMetadata = Boolean(play && PROHIBITED_TERMS.some((term) =>
    term.test(`${play.title ?? ""} ${play.description ?? ""}`)));
  if (hasAuthoritativePlayIdentity && prohibitedPlayMetadata && play) {
    return response(identity, "DENY", ["PLAY_PROHIBITED_FUNCTIONALITY"], "Exact-package authoritative Play metadata advertises prohibited browser, downloader, VPN, proxy, or bypass functionality.", [
      evidenceItem("play-identity", "42matters", `${play.title ?? ""}: ${play.description ?? ""}`, play.marketUrl),
    ], reputation(search, "flagged"));
  }

  const prohibitedSearch = search ? prohibitedSearchEvidence(search.results) : { terms: [], domains: new Set<string>() };
  if (prohibitedSearch.domains.size >= 2) {
    reasons.push("WEB_PROHIBITED_FUNCTIONALITY");
    evidence.push(...search!.results.filter((result) => matchesProhibited(result)).slice(0, 6).map((result) =>
      evidenceItem("web-search", "brave-search", `${result.title}: ${result.description}`, result.url)));
    return response(identity, "DENY", reasons, "Independent web results establish prohibited browser, downloader, VPN, proxy, or bypass functionality.", evidence, reputation(search, "flagged"));
  }

  const externalProhibitedEvidence = Boolean(
    search?.results.some(matchesProhibited) || prohibitedPlayMetadata
  );
  // AI interprets the supplied evidence; it is not an independent source.
  // Uncorroborated prohibited wording must hold UNKNOWN even if AI says ALLOW.
  if (externalProhibitedEvidence) reasons.push("UNCORROBORATED_PROHIBITED_EVIDENCE");

  if (!dependencies.play.available || !play) reasons.push("PLAY_METADATA_UNAVAILABLE");
  if (!dependencies.web.available || !search || search.results.length === 0) reasons.push("WEB_REPUTATION_UNAVAILABLE");
  if (!dependencies.ai.available || !dependencies.ai.value) reasons.push("AI_CLASSIFIER_UNAVAILABLE");

  const suspiciousSignals = (audit.bypassSignals ?? []).filter((signal) => SUSPICIOUS_LOCAL_SIGNALS.has(signal));
  if (suspiciousSignals.length > 0) reasons.push("LOCAL_SENSITIVE_CAPABILITY");
  if (audit.collectionErrors.length > 0) reasons.push("LOCAL_AUDIT_INCOMPLETE");

  const ai = dependencies.ai.value;
  if (ai?.decision === "DENY") reasons.push("AI_CLASSIFIER_DENY", ...ai.reasonCodes);
  const hasPositiveIdentity = Boolean(
    hasAuthoritativePlayIdentity && play?.title && play.developer && play.description
  );
  const hasCorroboratedNormalWeb = search ? corroboratedNormalEvidence(search.results, identity, play) : false;
  if (
    reasons.length === 0 &&
    hasPositiveIdentity &&
    hasCorroboratedNormalWeb &&
    ai?.decision === "ALLOW" &&
    ai.evidence.length > 0
  ) {
    const verifiedPlay = play;
    if (!verifiedPlay) return response(identity, "UNKNOWN", ["PLAY_METADATA_UNAVAILABLE"], "Store identity evidence is unavailable.", ai.evidence, reputation(search, "unknown"));
    evidence.push(evidenceItem("play-identity", "42matters", `${verifiedPlay.title} is listed for ${verifiedPlay.developer} in category ${verifiedPlay.category ?? "unknown"}.`, verifiedPlay.marketUrl));
    evidence.push(...search!.results.slice(0, 4).map((result) => evidenceItem("web-search", "brave-search", `${result.title}: ${result.description}`, result.url)));
    evidence.push(...ai.evidence);
    return response(identity, "ALLOW", unique(["VERIFIED_STORE_IDENTITY", "CORROBORATED_NORMAL_USE", ...ai.reasonCodes]), ai.summary, evidence, reputation(search, "clean"));
  }

  if (ai?.decision === "UNKNOWN") reasons.push(...ai.reasonCodes);
  if (ai?.decision === "ALLOW" && reasons.length > 0) reasons.push("ALLOW_REQUIREMENTS_INCOMPLETE");
  const summary = ai?.decision === "UNKNOWN" ? ai.summary : "Admission evidence is incomplete or conflicting; the package remains suspended.";
  return response(identity, "UNKNOWN", unique(reasons.length > 0 ? reasons : ["INSUFFICIENT_POSITIVE_EVIDENCE"]), summary, ai?.evidence ?? [], reputation(search, "unknown"));
}

function identityMatchesAudit(request: EvaluateRequest): boolean {
  const audit = request.audit.identity;
  return audit.packageName === request.identity.packageName &&
    audit.versionCode === request.identity.versionCode &&
    audit.versionName === request.identity.versionName &&
    sameSet(audit.signingIdentitySha256, request.identity.signingIdentitySha256) &&
    audit.installSource.installingPackageName === request.identity.installSource.installingPackageName &&
    audit.installSource.initiatingPackageName === request.identity.installSource.initiatingPackageName &&
    audit.installSource.originatingPackageName === request.identity.installSource.originatingPackageName &&
    audit.installSource.packageSource === request.identity.installSource.packageSource &&
    audit.installSource.sourceReliable === request.identity.installSource.sourceReliable;
}

function localHardCapabilityReasons(audit: AppAuditResult): string[] {
  const reasons: string[] = [];
  if (audit.hasVpnService) reasons.push("LOCAL_VPN_SERVICE");
  if (audit.hasBrowserCapability || audit.hasBrowsableActivity) reasons.push("LOCAL_BROWSER_CAPABILITY");
  return reasons;
}

function prohibitedSearchEvidence(results: SearchEvidence[]): { terms: string[]; domains: Set<string> } {
  const terms = new Set<string>();
  const domains = new Set<string>();
  for (const result of results) {
    if (!matchesProhibited(result)) continue;
    for (const term of PROHIBITED_TERMS) if (term.test(`${result.title} ${result.description} ${result.extraSnippets.join(" ")}`)) terms.add(term.source);
    const domain = registrableDomainOf(result.url);
    if (domain) domains.add(domain);
  }
  return { terms: [...terms], domains };
}

function matchesProhibited(result: SearchEvidence): boolean {
  const text = `${result.title} ${result.description} ${result.extraSnippets.join(" ")}`;
  return PROHIBITED_TERMS.some((term) => term.test(text));
}

function corroboratedNormalEvidence(
  results: SearchEvidence[],
  identity: EvaluateRequest["identity"],
  play: EvaluationDependencies["play"]["value"],
): boolean {
  const useful = results.filter((result) => !matchesProhibited(result) && result.description.trim().length >= 20);
  const exactPackage = useful.filter((result) => resultMentionsPackage(result, identity.packageName));
  const domains = new Set(exactPackage
    .map((result) => registrableDomainOf(result.url))
    .filter((domain): domain is string => Boolean(domain)));
  const hasCanonicalMarket = exactPackage.some((result) => isCanonicalMarketResult(result, identity.packageName));
  const hasTiedIdentity = exactPackage.some((result) =>
    isCanonicalMarketResult(result, identity.packageName) ||
    (Boolean(play?.title && contains(result, play.title)) && Boolean(play?.developer && contains(result, play.developer)))
  );
  // Generic reviews or pages controlled by a developer are not enough. The
  // positive path needs an exact package mention, a canonical market identity,
  // and an independent exact-package source on another domain.
  return useful.length >= 2 && hasCanonicalMarket && hasTiedIdentity && domains.size >= 2;
}

function resultMentionsPackage(result: SearchEvidence, packageName: string): boolean {
  return `${result.title} ${result.url} ${result.description} ${result.extraSnippets.join(" ")}`
    .toLowerCase().includes(packageName.toLowerCase());
}

function isCanonicalMarketResult(result: SearchEvidence, packageName: string): boolean {
  try {
    const url = new URL(result.url);
    return url.hostname.toLowerCase() === "play.google.com" &&
      url.pathname === "/store/apps/details" &&
      url.searchParams.get("id")?.toLowerCase() === packageName.toLowerCase();
  } catch {
    return false;
  }
}

function isExactPlayMarketUrl(value: string | null, packageName: string): boolean {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.hostname.toLowerCase() === "play.google.com" &&
      url.pathname === "/store/apps/details" &&
      url.searchParams.get("id")?.toLowerCase() === packageName.toLowerCase();
  } catch {
    return false;
  }
}

function contains(result: SearchEvidence, value: string): boolean {
  return `${result.title} ${result.url} ${result.description} ${result.extraSnippets.join(" ")}`
    .toLowerCase().includes(value.toLowerCase());
}

function reputation(search: EvaluationDependencies["web"]["value"], verdict: string) {
  if (!search) return null;
  return {
    verdict,
    score: null,
    categories: [],
    source: "brave-search",
    checkedAtEpochMs: search.checkedAtEpochMs,
  };
}

function response(
  identity: EvaluateRequest["identity"],
  decision: AdmissionDecision,
  reasonCodes: string[],
  summary: string,
  evidence: ClassifierEvidence[],
  webReputation: ReputationResponse["webReputation"],
): ReputationResponse {
  return {
    contractVersion: "admission-gate.v1",
    identity,
    webReputation,
    classificationDecision: decision,
    reasonCodes: unique(reasonCodes),
    summary: summary.trim() || "Admission review completed.",
    evidence: evidence.slice(0, 24),
  };
}

function evidenceItem(type: string, source: string, detail: string, reference: string | null): ClassifierEvidence {
  return { type, source, detail: detail.slice(0, 2000), reference };
}

function sameSet(a: string[], b: string[]): boolean {
  const left = new Set(a.map((value) => value.trim().toLowerCase()).filter(Boolean));
  const right = new Set(b.map((value) => value.trim().toLowerCase()).filter(Boolean));
  return left.size > 0 && left.size === right.size && [...left].every((value) => right.has(value));
}

function unique(values: string[]): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}
