# Admission Gate backend contract

The Android client posts to the managed endpoint from the app restriction
`admission_backend_endpoint` (HTTPS only). The bearer credential is read from
the managed app restriction `admission_backend_bearer_token`; neither value is
embedded in the APK. If the credential is absent, the client records
`UNKNOWN` and keeps the package suspended.

## Request

`POST /v1/evaluate`

Headers:

```text
Accept: application/json
Content-Type: application/json; charset=utf-8
Authorization: Bearer <managed credential>
X-Admission-Contract: admission-gate.v1
X-Request-Id: <UUID>
```

The JSON body is:

```json
{
  "contractVersion": "admission-gate.v1",
  "requestId": "uuid",
  "identity": {
    "packageName": "com.example.app",
    "versionCode": 42,
    "versionName": "4.2",
    "signingIdentitySha256": ["lowercase-sha256-certificate-digest"],
    "installSource": {
      "installingPackageName": "com.android.vending",
      "initiatingPackageName": null,
      "originatingPackageName": null,
      "packageSource": 2,
      "sourceReliable": true
    }
  },
  "audit": {
    "schemaVersion": 1,
    "identity": {
      "packageName": "com.example.app",
      "versionCode": 42,
      "versionName": "4.2",
      "signingIdentitySha256": ["lowercase-sha256-certificate-digest"],
      "installSource": {
        "installingPackageName": "com.android.vending",
        "initiatingPackageName": null,
        "originatingPackageName": null,
        "packageSource": 2,
        "sourceReliable": true
      }
    },
    "appLabel": "Example",
    "uid": 10142,
    "firstInstallTimeEpochMs": 1730000000000,
    "lastUpdateTimeEpochMs": 1730000000000,
    "rawApplicationFlags": 0,
    "rawPrivateFlags": null,
    "isSystemApp": false,
    "isUpdatedSystemApp": false,
    "isDebuggable": false,
    "isTestOnly": false,
    "isPersistent": false,
    "allowsBackup": true,
    "usesCleartextTraffic": false,
    "systemFlags": ["HAS_CODE"],
    "requestedPermissions": [
      {"name": "android.permission.INTERNET", "granted": true, "flags": 0}
    ],
    "exportedComponents": [
      {
        "type": "activity",
        "className": "com.example.app.MainActivity",
        "exported": true,
        "enabled": true,
        "directBootAware": false,
        "permission": null,
        "authorities": [],
        "actions": [],
        "categories": [],
        "dataSchemes": []
      }
    ],
    "exportedComponentCount": 1,
    "componentsTruncated": false,
    "intentCapabilities": [
      {
        "capability": "BROWSER_BROWSABLE",
        "componentType": "activity",
        "componentName": "com.example.app.MainActivity",
        "actions": ["android.intent.action.VIEW"],
        "categories": ["android.intent.category.BROWSABLE"],
        "dataSchemes": ["https"]
      }
    ],
    "capabilitiesTruncated": false,
    "hasBrowsableActivity": true,
    "hasBrowserCapability": true,
    "hasVpnService": false,
    "hasAccessibilityService": false,
    "hasDeviceAdminReceiver": false,
    "requestsInstallPackages": false,
    "requestsSystemAlertWindow": false,
    "overlayAppOpAllowed": null,
    "queriesAllPackages": false,
    "requestsUsageAccess": false,
    "requestsWriteSettings": false,
    "requestsManageExternalStorage": false,
    "requestsDeletePackages": false,
    "hasNotificationListenerService": false,
    "hasInputMethodService": false,
    "hasBootReceiver": false,
    "bypassSignals": ["BROWSER_BROWSABLE"],
    "collectionErrors": [],
    "collectedAtEpochMs": 1730000000000
  }
}
```

The Android client limits the response body to 1 MiB, does not follow
redirects, and rejects non-HTTPS endpoints.

## Response

A successful response is HTTP 2xx and must contain all fields below. The
`identity` is checked against the stored local identity and freshly queried
PackageManager identity before an `ALLOW` can be applied.

```json
{
  "contractVersion": "admission-gate.v1",
  "identity": {
    "packageName": "com.example.app",
    "versionCode": 42,
    "versionName": "4.2",
    "signingIdentitySha256": ["lowercase-sha256-certificate-digest"],
    "installSource": {
      "installingPackageName": "com.android.vending",
      "initiatingPackageName": null,
      "originatingPackageName": null,
      "packageSource": 2,
      "sourceReliable": true
    }
  },
  "webReputation": {
    "verdict": "clean",
    "score": 0.98,
    "categories": ["productivity"],
    "source": "reputation-provider",
    "checkedAtEpochMs": 1730000000000
  },
  "classificationDecision": "ALLOW",
  "reasonCodes": ["KNOWN_REPUTATION", "NO_BYPASS_SIGNALS"],
  "summary": "No admission policy violations found.",
  "evidence": [
    {
      "type": "reputation",
      "source": "reputation-provider",
      "detail": "Package certificate and version matched the clean result.",
      "reference": "provider-record-id"
    }
  ]
}
```

`classificationDecision` must be exactly `ALLOW`, `DENY`, or `UNKNOWN`.
`reasonCodes`, `summary`, and `evidence` are required even when the decision
is `UNKNOWN`; `webReputation` may be `null` only when no reputation result is
available. `DENY` is persisted as local `DENIED` and remains suspended.

Missing credential, timeout, unavailable network, non-2xx response, malformed
JSON, malformed identity, or an identity mismatch is treated as `UNKNOWN` and
the package remains suspended. No response can approve a hard-blocked package.
