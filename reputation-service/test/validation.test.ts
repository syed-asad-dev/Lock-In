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

test("malformed identity fields are rejected instead of normalized", () => {
  const value = request({ identity: { ...request().identity, versionName: 42 as never } });
  assert.equal(parseEvaluateRequest(value), null);
});

test("newly populated install-source data must match the audited identity", () => {
  const value = request({
    audit: {
      ...request().audit,
      identity: {
        ...request().identity,
        installSource: { ...request().identity.installSource, initiatingPackageName: "com.example.installer" },
      },
    },
  });
  assert.equal(parseEvaluateRequest(value), null);
});

test("malformed audit booleans and permission records are rejected", () => {
  assert.equal(parseEvaluateRequest(request({ audit: { ...request().audit, hasVpnService: "false" as never } })), null);
  assert.equal(parseEvaluateRequest(request({ audit: { ...request().audit, requestedPermissions: [{ name: "x", granted: "yes", flags: 0 }] } })), null);
});
