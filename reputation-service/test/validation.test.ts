import assert from "node:assert/strict";
import test from "node:test";
import { parseEvaluateRequest } from "../src/validation.js";
import { request } from "./fixtures.js";

test("valid Android contract request parses", () => assert.ok(parseEvaluateRequest(request())));
test("malformed request is rejected", () => assert.equal(parseEvaluateRequest({ contractVersion: "admission-gate.v1" }), null));
test("package/audit identity mismatch is rejected", () => {
  const value = request({ audit: { ...request().audit, identity: { ...request().identity, versionCode: 99 } } });
  assert.equal(parseEvaluateRequest(value), null);
});
