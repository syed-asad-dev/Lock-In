import assert from "node:assert/strict";
import test from "node:test";
import { evaluateAdmission } from "../src/policy.js";
import { unavailable } from "../src/types.js";
import { audit, identity, request } from "./fixtures.js";

const play = { available: true, errorCode: null, value: { packageName: identity.packageName, title: "Calculator Pro", developer: "Example Tools", description: "A normal calculator for everyday arithmetic.", category: "Productivity", marketSource: "GOOGLE", marketStatus: "PUBLISHED", marketUrl: "https://play.google.com/store/apps/details?id=com.example.calculator", version: "4.2", website: null, rawFields: {} } };
const web = (results: any[]) => ({ available: true, errorCode: null, value: { provider: "brave-search" as const, queries: ["calculator"], results, checkedAtEpochMs: Date.now() } });
const ai = (decision: "ALLOW" | "DENY" | "UNKNOWN") => ({ available: true, errorCode: null, value: { decision, reasonCodes: ["TEST"], summary: `AI ${decision}`, evidence: [{ type: "classifier", source: "test", detail: "structured evidence", reference: null }] } });
const normalResults = [
  { query: "calculator", title: "Calculator help", url: "https://docs.example.org/calculator", description: "A normal arithmetic calculator with history and themes.", extraSnippets: [], sourceDomain: "docs.example.org" },
  { query: "calculator", title: "Calculator review", url: "https://review.example.net/app", description: "Independent review of the arithmetic utility and its features.", extraSnippets: [], sourceDomain: "review.example.net" },
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
