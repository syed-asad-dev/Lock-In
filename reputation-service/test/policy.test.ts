import assert from "node:assert/strict";
import test from "node:test";
import { evaluateAdmission } from "../src/policy.js";
import { unavailable } from "../src/types.js";
import { audit, identity, request } from "./fixtures.js";

const play = { available: true, errorCode: null, value: { packageName: identity.packageName, title: "Calculator Pro", developer: "Example Tools", description: "A normal calculator for everyday arithmetic.", category: "Productivity", marketSource: "GOOGLE", marketStatus: "PUBLISHED", marketUrl: "https://play.google.com/store/apps/details?id=com.example.calculator", version: "4.2", website: null } };
const web = (results: any[]) => ({ available: true, errorCode: null, value: { provider: "brave-search" as const, queries: ["calculator"], results, checkedAtEpochMs: Date.now() } });
const ai = (decision: "ALLOW" | "DENY" | "UNKNOWN") => ({ available: true, errorCode: null, value: { decision, reasonCodes: ["TEST"], summary: `AI ${decision}`, evidence: [{ type: "classifier", source: "test", detail: "structured evidence", reference: null }] } });
const normalResults = [
  { query: "package", title: "Calculator Pro on Google Play", url: "https://play.google.com/store/apps/details?id=com.example.calculator", description: "Calculator Pro by Example Tools for com.example.calculator.", extraSnippets: [], sourceDomain: "play.google.com" },
  { query: "package", title: "Example Tools Calculator Pro", url: "https://catalog.example.net/com.example.calculator", description: "Independent listing for com.example.calculator by Example Tools, a normal arithmetic utility.", extraSnippets: [], sourceDomain: "catalog.example.net" },
];

test("hard blocked package is DENY before providers", () => {
  const blocked = { ...identity, packageName: "com.brave.browser" };
  const result = evaluateAdmission(request({ identity: blocked, audit: audit({ identity: blocked }) }), { play: unavailable("not-called"), web: unavailable("not-called"), ai: unavailable("not-called") });
  assert.equal(result.classificationDecision, "DENY");
});

test("legitimate corroborated evidence can ALLOW", () => {
  const result = evaluateAdmission(request(), { play, web: web(normalResults), ai: ai("ALLOW") });
  assert.equal(result.classificationDecision, "ALLOW");
});

test("missing providers are UNKNOWN", () => {
  const result = evaluateAdmission(request(), { play: unavailable("timeout"), web: unavailable("timeout"), ai: unavailable("timeout") });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

test("direct policy evaluation rejects an install-source audit mismatch", () => {
  const mismatchedAudit = audit({ identity: {
    ...identity,
    installSource: { ...identity.installSource, initiatingPackageName: "com.example.installer" },
  } });
  const result = evaluateAdmission(request({ audit: mismatchedAudit }), { play, web: web(normalResults), ai: ai("ALLOW") });
  assert.equal(result.classificationDecision, "UNKNOWN");
  assert.ok(result.reasonCodes.includes("IDENTITY_LOCAL_AUDIT_MISMATCH"));
});

test("conflicting prohibited web evidence is DENY only when corroborated", () => {
  const results = [
    { query: "x", title: "Calculator browser", url: "https://one.example/a", description: "Browse privately and download videos.", extraSnippets: [], sourceDomain: "one.example" },
    { query: "x", title: "App review", url: "https://two.example/b", description: "This utility includes a private browser and video downloader.", extraSnippets: [], sourceDomain: "two.example" },
  ];
  const result = evaluateAdmission(request(), { play, web: web(results), ai: ai("ALLOW") });
  assert.equal(result.classificationDecision, "DENY");
});

test("local browser capability is deterministic DENY", () => {
  const result = evaluateAdmission(request({ audit: audit({ hasBrowserCapability: true }) }), { play: unavailable("not-called"), web: unavailable("not-called"), ai: unavailable("not-called") });
  assert.equal(result.classificationDecision, "DENY");
});

test("AI malformed/unsupported output cannot allow", () => {
  const result = evaluateAdmission(request(), { play, web: web(normalResults), ai: unavailable("AI_MALFORMED_RESPONSE") });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

test("generic positive pages without exact package identity cannot allow", () => {
  const generic = [
    { query: "calculator", title: "Calculator help", url: "https://one.example/help", description: "A friendly calculator utility for everyday arithmetic.", extraSnippets: [], sourceDomain: "one.example" },
    { query: "calculator", title: "Calculator review", url: "https://two.example/review", description: "A positive review of a calculator app.", extraSnippets: [], sourceDomain: "two.example" },
  ];
  const result = evaluateAdmission(request(), { play, web: web(generic), ai: ai("ALLOW") });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

const prohibitedResult = {
  query: "calculator browser", title: "Calculator browser review", url: "https://one.example/review",
  description: "This utility includes a private browser and video downloader.", extraSnippets: [], sourceDomain: "one.example",
};

test("one arbitrary prohibited web result plus AI DENY remains UNKNOWN", () => {
  const result = evaluateAdmission(request(), { play, web: web([prohibitedResult]), ai: ai("DENY") });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

test("AI cannot resolve a single prohibited web result even alongside positive evidence", () => {
  for (const decision of ["ALLOW", "DENY", "UNKNOWN"] as const) {
    const result = evaluateAdmission(request(), { play, web: web([...normalResults, prohibitedResult]), ai: ai(decision) });
    assert.equal(result.classificationDecision, "UNKNOWN", `AI ${decision}`);
    assert.ok(result.reasonCodes.includes("UNCORROBORATED_PROHIBITED_EVIDENCE"));
  }
});

test("multiple prohibited results from one source plus AI DENY remain UNKNOWN", () => {
  const result = evaluateAdmission(request(), {
    play,
    web: web([prohibitedResult, { ...prohibitedResult, url: "https://one.example/another-review", query: "calculator VPN" }]),
    ai: ai("DENY"),
  });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

test("prohibited wording without a source domain cannot be corroborated by AI", () => {
  const result = evaluateAdmission(request(), {
    play, web: web([{ ...prohibitedResult, sourceDomain: null }]), ai: ai("DENY"),
  });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

test("subdomains of one registrable domain are not independent prohibited sources", () => {
  const result = evaluateAdmission(request(), {
    play: unavailable("not-configured"),
    web: web([
      prohibitedResult,
      { ...prohibitedResult, url: "https://www.one.example/another-review", sourceDomain: "www.one.example" },
    ]),
    ai: unavailable("not-configured"),
  });
  assert.equal(result.classificationDecision, "UNKNOWN");
});

test("two independent prohibited web sources deterministically DENY without AI or Play", () => {
  const result = evaluateAdmission(request(), {
    play: unavailable("not-configured"),
    web: web([prohibitedResult, { ...prohibitedResult, title: "Independent app review", url: "https://two.example/review", sourceDomain: "two.example" }]),
    ai: unavailable("not-configured"),
  });
  assert.equal(result.classificationDecision, "DENY");
  assert.ok(result.reasonCodes.includes("WEB_PROHIBITED_FUNCTIONALITY"));
});

test("local VPN capability is deterministic DENY", () => {
  const result = evaluateAdmission(request({ audit: audit({ hasVpnService: true }) }), {
    play: unavailable("not-called"), web: unavailable("not-called"), ai: unavailable("not-called"),
  });
  assert.equal(result.classificationDecision, "DENY");
  assert.ok(result.reasonCodes.includes("LOCAL_VPN_SERVICE"));
});

test("local browsable activity remains deterministic DENY", () => {
  const result = evaluateAdmission(request({ audit: audit({ hasBrowsableActivity: true }) }), {
    play: unavailable("not-called"), web: unavailable("not-called"), ai: unavailable("not-called"),
  });
  assert.equal(result.classificationDecision, "DENY");
  assert.ok(result.reasonCodes.includes("LOCAL_BROWSER_CAPABILITY"));
});

for (const description of [
  "Calculator with a hidden browser.", "Includes private browsing.", "Built-in VPN.",
  "Includes a proxy.", "Bypass restrictions and unblock sites.", "Built-in video downloader.",
]) {
  test(`exact authoritative Play metadata deterministically DENY: ${description}`, () => {
    for (const classifier of [unavailable<never>("not-configured"), ai("ALLOW"), ai("DENY"), ai("UNKNOWN")]) {
      const result = evaluateAdmission(request(), {
        play: { ...play, value: { ...play.value, description } }, web: unavailable("not-configured"), ai: classifier,
      });
      assert.equal(result.classificationDecision, "DENY");
      assert.ok(result.reasonCodes.includes("PLAY_PROHIBITED_FUNCTIONALITY"));
    }
  });
}

test("prohibited functionality advertised in the exact Play title is deterministic DENY", () => {
  const result = evaluateAdmission(request(), {
    play: { ...play, value: { ...play.value, title: "Calculator VPN" } },
    web: unavailable("not-configured"), ai: unavailable("not-configured"),
  });
  assert.equal(result.classificationDecision, "DENY");
});

test("non-authoritative or mismatched store metadata plus AI DENY remains UNKNOWN", () => {
  const prohibitedPlay = { ...play.value, description: "Includes a hidden browser." };
  for (const overrides of [
    { packageName: "com.other.app" }, { marketSource: "OTHER" }, { marketStatus: "UNPUBLISHED" },
    { marketUrl: null }, { marketUrl: "https://catalog.example/com.example.calculator" },
    { marketUrl: "https://play.google.com/store/apps/details?id=com.other.app" },
  ]) {
    const result = evaluateAdmission(request(), {
      play: { ...play, value: { ...prohibitedPlay, ...overrides } }, web: web(normalResults), ai: ai("DENY"),
    });
    assert.equal(result.classificationDecision, "UNKNOWN", JSON.stringify(overrides));
  }
});

test("each missing or malformed provider independently prevents ALLOW", () => {
  for (const provider of ["play", "web", "ai"] as const) {
    for (const missing of [unavailable<never>("not-configured"), unavailable<never>("MALFORMED_RESPONSE"), { available: true, errorCode: null, value: null }]) {
      const dependencies = { play, web: web(normalResults), ai: ai("ALLOW"), [provider]: missing };
      assert.equal(evaluateAdmission(request(), dependencies).classificationDecision, "UNKNOWN", provider);
    }
  }
});

test("Play presence alone and AI ALLOW alone never permit ALLOW", () => {
  const absent = unavailable<never>("not-configured");
  assert.equal(evaluateAdmission(request(), { play, web: absent, ai: absent }).classificationDecision, "UNKNOWN");
  assert.equal(evaluateAdmission(request(), { play: absent, web: absent, ai: ai("ALLOW") }).classificationDecision, "UNKNOWN");
});
