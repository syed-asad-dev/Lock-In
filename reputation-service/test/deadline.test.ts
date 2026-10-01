import assert from "node:assert/strict";
import test from "node:test";
import { evaluateRequest } from "../src/evaluate.js";
import { audit, identity, request } from "./fixtures.js";

const wait = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));

test("evaluation deadline fails closed without waiting for sequential provider timeouts", async () => {
  const started = Date.now();
  const slow = () => wait(150).then(() => ({ available: true, value: null, errorCode: null }));
  const result = await evaluateRequest(request(), {
    play: { lookup: slow },
    web: { search: slow },
    ai: { classify: slow },
  }, 25);
  assert.equal(result.classificationDecision, "UNKNOWN");
  assert.ok(Date.now() - started < 120);
});

test("identity mismatch remains UNKNOWN even when providers are otherwise positive", async () => {
  const mismatched = { ...identity, packageName: "com.other.identity" };
  const result = await evaluateRequest(request({ identity: mismatched, audit: audit() }), {
    play: { lookup: async () => ({ available: true, value: null, errorCode: null }) },
    web: { search: async () => ({ available: true, value: null, errorCode: null }) },
    ai: { classify: async () => ({ available: true, value: null, errorCode: null }) },
  }, 1000);
  assert.equal(result.classificationDecision, "UNKNOWN");
});
