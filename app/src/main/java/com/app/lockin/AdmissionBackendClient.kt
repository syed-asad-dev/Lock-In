package com.app.lockin

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

private const val BACKEND_TAG = "AdmissionBackendClient"
private const val CONTRACT_VERSION = "admission-gate.v1"
private const val DEFAULT_ENDPOINT = "https://admission.lock-in.invalid/v1/evaluate"
private const val ENDPOINT_RESTRICTION_KEY = "admission_backend_endpoint"
private const val TOKEN_RESTRICTION_KEY = "admission_backend_bearer_token"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val MAX_RESPONSE_BYTES = 1_048_576

data class AdmissionBackendConfiguration(
    val endpoint: String,
    val bearerToken: String?
)

/**
 * Configuration is intentionally supplied at runtime through managed app
 * restrictions. No endpoint credential is compiled into the APK.
 */
object AdmissionBackendConfig {
    fun resolve(context: Context): AdmissionBackendConfiguration {
        val restrictions = try {
            val manager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            manager?.applicationRestrictions
        } catch (e: Exception) {
            Log.w(BACKEND_TAG, "Unable to read managed backend restrictions: ${e.message}")
            null
        }
        return fromRestrictions(restrictions)
    }

    internal fun fromRestrictions(restrictions: Bundle?): AdmissionBackendConfiguration {
        val endpoint = restrictions
            ?.getString(ENDPOINT_RESTRICTION_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ENDPOINT
        val token = restrictions
            ?.getString(TOKEN_RESTRICTION_KEY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return AdmissionBackendConfiguration(endpoint = endpoint, bearerToken = token)
    }
}

/**
 * Small HTTPS-only client. It is called only by AdmissionEvaluationJobService;
 * PackageInstallReceiver never invokes this class's network method.
 *
 * Request contract (POST /v1/evaluate):
 * {
 *   "contractVersion":"admission-gate.v1",
 *   "requestId":"uuid",
 *   "identity":{
 *     "packageName":"com.example.app",
 *     "versionCode":42,
 *     "versionName":"4.2",
 *     "signingIdentitySha256":["lowercase-cert-digest"],
 *     "installSource":{
 *       "installingPackageName":"com.android.vending",
 *       "initiatingPackageName":null,
 *       "originatingPackageName":null,
 *       "packageSource":2,
 *       "sourceReliable":true
 *     }
 *   },
 *   "audit":{ "...": "AppAuditResult JSON" }
 * }
 *
 * Response contract (HTTP 2xx):
 * {
 *   "contractVersion":"admission-gate.v1",
 *   "identity":{
 *     "packageName":"com.example.app",
 *     "versionCode":42,
 *     "versionName":"4.2",
 *     "signingIdentitySha256":["lowercase-cert-digest"],
 *     "installSource":{ "...": "same shape as request" }
 *   },
 *   "webReputation":{
 *     "verdict":"clean",
 *     "score":0.98,
 *     "categories":["productivity"],
 *     "source":"provider-name",
 *     "checkedAtEpochMs":1730000000000
 *   },
 *   "classificationDecision":"ALLOW|DENY|UNKNOWN",
 *   "reasonCodes":["REASON_CODE"],
 *   "summary":"human-readable explanation",
 *   "evidence":[
 *     {"type":"reputation","source":"provider-name","detail":"...","reference":"..."}
 *   ]
 * }
 *
 * A missing credential, unavailable endpoint, timeout, non-2xx response, or
 * malformed response becomes UNKNOWN. Callers must keep the package suspended.
 */
object AdmissionBackendClient {
    fun evaluate(context: Context, audit: AppAuditResult): AdmissionExternalResult {
        val configuration = AdmissionBackendConfig.resolve(context)
        val token = configuration.bearerToken
        if (token.isNullOrBlank()) {
            return unknownResult(
                identity = audit.identity,
                errorCode = "NO_CREDENTIAL",
                reasonCodes = listOf("EXTERNAL_CREDENTIAL_UNAVAILABLE"),
                summary = "External admission review is not configured.",
                retryable = false
            )
        }

        val connection = try {
            val url = URL(configuration.endpoint)
            if (url.protocol.lowercase() != "https") {
                return unknownResult(
                    identity = audit.identity,
                    errorCode = "INSECURE_ENDPOINT",
                    reasonCodes = listOf("EXTERNAL_ENDPOINT_NOT_HTTPS"),
                    summary = "External admission endpoint is not HTTPS.",
                    retryable = false
                )
            }
            (url.openConnection() as? HttpURLConnection) ?: return unknownResult(
                identity = audit.identity,
                errorCode = "UNSUPPORTED_CONNECTION",
                reasonCodes = listOf("EXTERNAL_ENDPOINT_UNSUPPORTED"),
                summary = "External admission endpoint is unsupported.",
                retryable = false
            )
        } catch (e: Exception) {
            return unknownResult(
                identity = audit.identity,
                errorCode = "INVALID_ENDPOINT",
                reasonCodes = listOf("EXTERNAL_ENDPOINT_INVALID"),
                summary = "External admission endpoint is invalid.",
                retryable = false
            )
        }

        return try {
            val requestBody = buildRequest(audit).toString().toByteArray(StandardCharsets.UTF_8)
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Admission-Contract", CONTRACT_VERSION)
                setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
                setFixedLengthStreamingMode(requestBody.size)
            }
            connection.outputStream.use { output -> output.write(requestBody) }

            val httpStatus = connection.responseCode
            val responseBytes = (if (httpStatus in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.use(::readLimited)

            if (httpStatus !in 200..299 || responseBytes == null) {
                unknownResult(
                    identity = audit.identity,
                    errorCode = "HTTP_$httpStatus",
                    reasonCodes = listOf("EXTERNAL_HTTP_FAILURE"),
                    summary = "External admission review was unavailable.",
                    httpStatus = httpStatus,
                    retryable = httpStatus == 408 || httpStatus == 429 || httpStatus >= 500
                )
            } else {
                parseResponse(
                    responseBody = responseBytes.toString(StandardCharsets.UTF_8),
                    httpStatus = httpStatus,
                    requestedIdentity = audit.identity
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            unknownResult(
                identity = audit.identity,
                errorCode = "TIMEOUT",
                reasonCodes = listOf("EXTERNAL_TIMEOUT"),
                summary = "External admission review timed out.",
                retryable = true
            )
        } catch (e: java.io.IOException) {
            unknownResult(
                identity = audit.identity,
                errorCode = "NETWORK_UNAVAILABLE",
                reasonCodes = listOf("EXTERNAL_NETWORK_UNAVAILABLE"),
                summary = "External admission review could not be reached.",
                retryable = true
            )
        } catch (e: Exception) {
            Log.w(BACKEND_TAG, "External admission review failed without exposing response data: ${e.message}")
            unknownResult(
                identity = audit.identity,
                errorCode = "CLIENT_FAILURE",
                reasonCodes = listOf("EXTERNAL_CLIENT_FAILURE"),
                summary = "External admission review failed.",
                retryable = false
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequest(audit: AppAuditResult): JSONObject = JSONObject().apply {
        put("contractVersion", CONTRACT_VERSION)
        put("requestId", UUID.randomUUID().toString())
        put("identity", audit.identity.toJson())
        put("audit", audit.toJson())
    }

    /** Strict parser: required contract fields missing or malformed become UNKNOWN. */
    internal fun parseResponse(
        responseBody: String,
        httpStatus: Int,
        requestedIdentity: PackageIdentity
    ): AdmissionExternalResult {
        val now = System.currentTimeMillis()
        return try {
            val root = JSONObject(responseBody)
            if (root.optString("contractVersion", "") != CONTRACT_VERSION) {
                return unknownResult(
                    identity = requestedIdentity,
                    errorCode = "MALFORMED_RESPONSE_CONTRACT",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response used an unsupported contract version.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            }
            val responseIdentityJson = root.optJSONObject("identity")
                ?: return unknownResult(
                    identity = requestedIdentity,
                    errorCode = "MALFORMED_RESPONSE_IDENTITY",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response did not contain package identity.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            val responseIdentity = packageIdentityFromJsonStrict(responseIdentityJson)
                ?: return unknownResult(
                    identity = requestedIdentity,
                    errorCode = "MALFORMED_RESPONSE_IDENTITY",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response identity was malformed.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )

            val decision = if (root.has("classificationDecision") &&
                !root.isNull("classificationDecision") &&
                root.get("classificationDecision") is String
            ) {
                runCatching { ClassificationDecision.valueOf(root.getString("classificationDecision")) }.getOrNull()
            } else {
                null
            }
                ?: return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_DECISION",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response contained an unknown classification decision.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )

            val reasonArray = root.optJSONArray("reasonCodes")
                ?: return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_REASONS",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response did not contain reason codes.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            val reasonCodes = mutableListOf<String>()
            for (index in 0 until reasonArray.length()) {
                val value = reasonArray.opt(index)
                if (value !is String || value.trim().isBlank()) {
                    return unknownResult(
                        identity = responseIdentity,
                        errorCode = "MALFORMED_RESPONSE_REASONS",
                        reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                        summary = "External response reason codes were malformed.",
                        httpStatus = httpStatus,
                        evaluatedAtEpochMs = now,
                        retryable = false
                    )
                }
                reasonCodes += value.trim()
            }
            if (reasonCodes.size != reasonArray.length()) {
                return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_REASONS",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response reason codes were malformed.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            }

            val summary = if (root.has("summary") && !root.isNull("summary")) {
                root.getString("summary").trim()
            } else {
                ""
            }
            if (summary.isBlank()) {
                return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_SUMMARY",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response did not contain a summary.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            }

            val evidenceArray = root.optJSONArray("evidence")
                ?: return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_EVIDENCE",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response did not contain evidence.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            val evidence = mutableListOf<ExternalEvidence>()
            for (index in 0 until evidenceArray.length()) {
                val evidenceJson = evidenceArray.optJSONObject(index)
                    ?: return unknownResult(
                        identity = responseIdentity,
                        errorCode = "MALFORMED_RESPONSE_EVIDENCE",
                        reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                        summary = "External response evidence was malformed.",
                        httpStatus = httpStatus,
                        evaluatedAtEpochMs = now,
                        retryable = false
                    )
                val type = strictNullableString(evidenceJson, "type")
                val detail = strictNullableString(evidenceJson, "detail")
                val source = strictNullableString(evidenceJson, "source")
                val reference = strictNullableString(evidenceJson, "reference")
                if (type.isNullOrBlank() || detail.isNullOrBlank() ||
                    (evidenceJson.has("source") && !evidenceJson.isNull("source") && source == null) ||
                    (evidenceJson.has("reference") && !evidenceJson.isNull("reference") && reference == null)
                ) {
                    return unknownResult(
                        identity = responseIdentity,
                        errorCode = "MALFORMED_RESPONSE_EVIDENCE",
                        reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                        summary = "External response evidence was incomplete.",
                        httpStatus = httpStatus,
                        evaluatedAtEpochMs = now,
                        retryable = false
                    )
                }
                evidence += ExternalEvidence(
                    type = type,
                    source = source,
                    detail = detail,
                    reference = reference
                )
            }

            if (!root.has("webReputation")) {
                return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_REPUTATION",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response did not contain web reputation.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
            }
            val reputation = if (root.isNull("webReputation")) {
                null
            } else {
                val reputationJson = root.optJSONObject("webReputation")
                    ?: return unknownResult(
                        identity = responseIdentity,
                        errorCode = "MALFORMED_RESPONSE_REPUTATION",
                        reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                        summary = "External response web reputation was malformed.",
                        httpStatus = httpStatus,
                        evaluatedAtEpochMs = now,
                        retryable = false
                    )
                if (!reputationJson.has("verdict") || !reputationJson.has("score") ||
                    !reputationJson.has("categories") || !reputationJson.has("source") ||
                    !reputationJson.has("checkedAtEpochMs") || !validNullableString(reputationJson, "verdict") ||
                    !validNullableString(reputationJson, "source") ||
                    !validNullableLong(reputationJson, "checkedAtEpochMs")
                ) return unknownResult(
                    identity = responseIdentity,
                    errorCode = "MALFORMED_RESPONSE_REPUTATION",
                    reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                    summary = "External response web reputation was malformed.",
                    httpStatus = httpStatus,
                    evaluatedAtEpochMs = now,
                    retryable = false
                )
                val categoriesArray = reputationJson.optJSONArray("categories")
                    ?: return unknownResult(
                        identity = responseIdentity,
                        errorCode = "MALFORMED_RESPONSE_REPUTATION",
                        reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                        summary = "External response web reputation was malformed.",
                        httpStatus = httpStatus,
                        evaluatedAtEpochMs = now,
                        retryable = false
                    )
                val categories = mutableListOf<String>()
                for (index in 0 until categoriesArray.length()) {
                    val category = categoriesArray.opt(index)
                    if (category !is String || category.isBlank()) {
                        return unknownResult(
                            identity = responseIdentity,
                            errorCode = "MALFORMED_RESPONSE_REPUTATION",
                            reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                            summary = "External response web reputation was malformed.",
                            httpStatus = httpStatus,
                            evaluatedAtEpochMs = now,
                            retryable = false
                        )
                    }
                    categories += category
                }
                val score = if (reputationJson.has("score") && !reputationJson.isNull("score")) {
                    val rawScore = reputationJson.opt("score")
                    if (rawScore !is Number || !rawScore.toDouble().isFinite()) return unknownResult(
                        identity = responseIdentity,
                        errorCode = "MALFORMED_RESPONSE_REPUTATION",
                        reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                        summary = "External response web reputation was malformed.",
                        httpStatus = httpStatus,
                        evaluatedAtEpochMs = now,
                        retryable = false
                    )
                    rawScore.toDouble()
                } else null
                WebReputation(
                    verdict = strictNullableString(reputationJson, "verdict"),
                    score = score,
                    categories = categories,
                    source = strictNullableString(reputationJson, "source"),
                    checkedAtEpochMs = strictNullableLong(reputationJson, "checkedAtEpochMs")
                )
            }

            AdmissionExternalResult(
                identity = responseIdentity,
                webReputation = reputation,
                decision = decision,
                reasonCodes = reasonCodes,
                summary = summary,
                evidence = evidence,
                evaluatedAtEpochMs = now,
                httpStatus = httpStatus,
                errorCode = null,
                retryable = false
            )
        } catch (e: Exception) {
            unknownResult(
                identity = requestedIdentity,
                errorCode = "MALFORMED_RESPONSE",
                reasonCodes = listOf("EXTERNAL_RESPONSE_MALFORMED"),
                summary = "External admission response could not be parsed.",
                httpStatus = httpStatus,
                evaluatedAtEpochMs = now,
                retryable = false
            )
        }
    }

    private fun packageIdentityFromJsonStrict(json: JSONObject): PackageIdentity? {
        return try {
            val packageName = strictRequiredString(json, "packageName")?.takeIf { it.isNotBlank() } ?: return null
            val rawVersion = json.opt("versionCode")
            if (rawVersion !is Number || rawVersion.toLong() < 0L || rawVersion.toDouble() != rawVersion.toLong().toDouble()) return null
            if (!json.has("versionName") || !validNullableString(json, "versionName")) return null
            val signersArray = json.optJSONArray("signingIdentitySha256") ?: return null
            val signers = mutableListOf<String>()
            for (index in 0 until signersArray.length()) {
                val signer = signersArray.opt(index)
                if (signer !is String || !Regex("^[a-fA-F0-9]{64}$").matches(signer)) return null
                signers += signer.lowercase(Locale.ROOT)
            }
            if (signers.isEmpty() || signers.distinct().size != signers.size) return null
            val installSourceJson = json.optJSONObject("installSource") ?: return null
            val installSource = installSourceFromJsonStrict(installSourceJson) ?: return null
            PackageIdentity(
                packageName = packageName,
                versionCode = rawVersion.toLong(),
                versionName = strictNullableString(json, "versionName"),
                signingIdentitySha256 = signers,
                installSource = installSource
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun installSourceFromJsonStrict(json: JSONObject): InstallSourceIdentity? {
        val fields = listOf("installingPackageName", "initiatingPackageName", "originatingPackageName", "packageSource")
        if (fields.any { !json.has(it) } || !json.has("sourceReliable") || json.opt("sourceReliable") !is Boolean) return null
        if (!validNullablePackageName(json, "installingPackageName") ||
            !validNullablePackageName(json, "initiatingPackageName") ||
            !validNullablePackageName(json, "originatingPackageName") ||
            !validNullableInt(json, "packageSource")
        ) return null
        val reliable = json.getBoolean("sourceReliable")
        val packageSource = strictNullableInt(json, "packageSource")
        val installing = strictNullableString(json, "installingPackageName")
        val initiating = strictNullableString(json, "initiatingPackageName")
        val originating = strictNullableString(json, "originatingPackageName")
        if (reliable && installing == null && initiating == null && originating == null && packageSource == null) return null
        return InstallSourceIdentity(installing, initiating, originating, packageSource, reliable)
    }

    private fun strictRequiredString(json: JSONObject, key: String): String? =
        if (json.has(key) && !json.isNull(key) && json.opt(key) is String) json.getString(key) else null

    private fun strictNullableString(json: JSONObject, key: String): String? =
        if (!json.has(key) || json.isNull(key)) null else strictRequiredString(json, key)

    private fun validNullableString(json: JSONObject, key: String): Boolean =
        !json.has(key) || json.isNull(key) || json.opt(key) is String

    private fun strictNullableLong(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key)
        return if (value is Number && value.toDouble() == value.toLong().toDouble()) value.toLong() else null
    }

    private fun validNullableLong(json: JSONObject, key: String): Boolean =
        !json.has(key) || json.isNull(key) || strictNullableLong(json, key) != null

    private fun strictNullableInt(json: JSONObject, key: String): Int? {
        val value = strictNullableLong(json, key) ?: return null
        return value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    private fun validNullableInt(json: JSONObject, key: String): Boolean =
        !json.has(key) || json.isNull(key) || strictNullableInt(json, key) != null

    private fun validNullablePackageName(json: JSONObject, key: String): Boolean {
        if (!json.has(key) || json.isNull(key)) return true
        val value = strictNullableString(json, key) ?: return false
        return value.matches(Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$"))
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw IllegalArgumentException("response too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun unknownResult(
        identity: PackageIdentity,
        errorCode: String,
        reasonCodes: List<String>,
        summary: String,
        httpStatus: Int? = null,
        evaluatedAtEpochMs: Long = System.currentTimeMillis(),
        retryable: Boolean
    ): AdmissionExternalResult = AdmissionExternalResult(
        identity = identity,
        webReputation = null,
        decision = ClassificationDecision.UNKNOWN,
        reasonCodes = reasonCodes,
        summary = summary,
        evidence = emptyList(),
        evaluatedAtEpochMs = evaluatedAtEpochMs,
        httpStatus = httpStatus,
        errorCode = errorCode,
        retryable = retryable
    )
}
