import assert from "node:assert/strict";
import test from "node:test";
import { OpenAiCompatibleClassifierProvider } from "../src/providers/ai.js";
import { BraveWebSearchProvider, buildQueries } from "../src/providers/brave.js";
import { Matters42PlayMetadataProvider } from "../src/providers/play.js";
import { audit, identity } from "./fixtures.js";

test("42matters maps exact package identity metadata", async () => {
  const provider = new Matters42PlayMetadataProvider("server-only-key", 1000, async () => ({
    package_name: identity.packageName, title: "Calculator Pro", developer: "Example Tools", description: "Arithmetic", category: "Productivity", market_source: "GOOGLE", market_status: "PUBLISHED", market_url: "https://play.google.com/store/apps/details?id=com.example.calculator",
  }));
  const result = await provider.lookup(identity);
  assert.equal(result.available, true);
  assert.equal(result.value?.packageName, identity.packageName);
});

test("42matters package mismatch is unavailable, never identity evidence", async () => {
  const provider = new Matters42PlayMetadataProvider("key", 1000, async () => ({ package_name: "com.other.app" }));
  const result = await provider.lookup(identity);
  assert.equal(result.available, false);
  assert.equal(result.errorCode, "PLAY_PACKAGE_IDENTITY_MISMATCH");
});

test("provider timeout/failure becomes unavailable", async () => {
  const provider = new Matters42PlayMetadataProvider("key", 1000, async () => { throw new Error("timeout"); });
  const result = await provider.lookup(identity);
  assert.equal(result.available, false);
  assert.equal(result.errorCode, "PLAY_PROVIDER_FAILURE");
});

test("Brave malformed response is unavailable and targeted queries are generated", async () => {
  const seen: string[] = [];
  const provider = new BraveWebSearchProvider("key", 1000, 10, async (url) => { seen.push(url); return {}; });
  const result = await provider.search(identity, audit(), null);
  assert.equal(result.available, false);
  assert.equal(result.errorCode, "WEB_MALFORMED_RESPONSE");
  const queries = buildQueries(identity, audit(), null);
  assert.ok(queries.some((query) => query.includes("browser")));
  assert.ok(queries.some((query) => query.includes("video downloader")));
});

test("AI unsupported decision is rejected", async () => {
  const provider = new OpenAiCompatibleClassifierProvider("key", "https://ai.example.test/v1", "model", 1000, async () => ({
    choices: [{ message: { content: JSON.stringify({ decision: "SAFE", reasonCodes: [], summary: "bad", evidence: [] }) } }],
  }));
  const result = await provider.classify({ identity, audit: audit(), play: null, web: null });
  assert.equal(result.available, false);
  assert.equal(result.errorCode, "AI_MALFORMED_RESPONSE");
});
