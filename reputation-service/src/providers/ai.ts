import { fetchJson, ProviderHttpError } from "../http.js";
import type {
  AppAuditResult,
  ClassifierEvidence,
  ClassifierResult,
  PackageIdentity,
  PlayMetadata,
  ProviderOutcome,
  WebSearchBundle,
} from "../types.js";
import { unavailable } from "../types.js";
import type { JsonFetcher } from "./play.js";

export interface AiClassifierProvider {
  classify(input: {
    identity: PackageIdentity;
    audit: AppAuditResult;
    play: PlayMetadata | null;
    web: WebSearchBundle | null;
  }): Promise<ProviderOutcome<ClassifierResult>>;
}

export class OpenAiCompatibleClassifierProvider implements AiClassifierProvider {
  constructor(
    private readonly apiKey: string | null,
    private readonly baseUrl: string | null,
    private readonly model: string,
    private readonly timeoutMs: number,
    private readonly fetcher: JsonFetcher = fetchJson,
  ) {}

  async classify(input: {
    identity: PackageIdentity;
    audit: AppAuditResult;
    play: PlayMetadata | null;
    web: WebSearchBundle | null;
  }): Promise<ProviderOutcome<ClassifierResult>> {
    if (!this.apiKey || !this.baseUrl || !this.model) return unavailable("AI_PROVIDER_NOT_CONFIGURED");
    let endpoint: URL;
    try {
      endpoint = new URL(this.baseUrl);
      endpoint.pathname = `${endpoint.pathname.replace(/\/$/, "")}/chat/completions`;
    } catch {
      return unavailable("AI_PROVIDER_INVALID_ENDPOINT");
    }
    const request = {
      model: this.model,
      temperature: 0,
      response_format: { type: "json_object" },
      messages: [
        {
          role: "system",
          content: "Classify Android app admission. Return only JSON with decision ALLOW, DENY, or UNKNOWN; reasonCodes string array; summary string; evidence array of {type,source,detail,reference}. Never use confidence percentages. ALLOW requires a consistent legitimate identity and positive corroborating evidence. Treat browser, private browsing, VPN, proxy, bypass, or downloader capability as DENY.",
        },
        { role: "user", content: JSON.stringify({ identity: input.identity, audit: input.audit, play: input.play, web: input.web }) },
      ],
    };
    try {
      const body = await this.fetcher(endpoint.toString(), {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify(request),
      }, this.timeoutMs);
      const parsed = extractClassifierPayload(body);
      if (!parsed) return unavailable("AI_MALFORMED_RESPONSE");
      return { available: true, value: parsed, errorCode: null };
    } catch (error) {
      return unavailable(error instanceof ProviderHttpError ? `AI_${error.code}` : "AI_PROVIDER_FAILURE");
    }
  }
}

function extractClassifierPayload(body: unknown): ClassifierResult | null {
  const direct = validClassifier(body);
  if (direct) return direct;
  if (!isRecord(body) || !Array.isArray(body.choices)) return null;
  const choice = body.choices[0];
  if (!isRecord(choice) || !isRecord(choice.message)) return null;
  const content = choice.message.content;
  if (typeof content !== "string") return null;
  try {
    const parsed = JSON.parse(stripCodeFence(content));
    return validClassifier(parsed);
  } catch {
    return null;
  }
}

function validClassifier(value: unknown): ClassifierResult | null {
  if (!isRecord(value)) return null;
  const decision = value.decision;
  if (decision !== "ALLOW" && decision !== "DENY" && decision !== "UNKNOWN") return null;
  if (!Array.isArray(value.reasonCodes) || value.reasonCodes.some((v) => typeof v !== "string" || !v.trim())) return null;
  if (typeof value.summary !== "string" || !value.summary.trim()) return null;
  if (!Array.isArray(value.evidence)) return null;
  const evidence: ClassifierEvidence[] = [];
  for (const item of value.evidence) {
    if (!isRecord(item) || typeof item.type !== "string" || typeof item.detail !== "string" || !item.detail.trim()) return null;
    evidence.push({
      type: item.type,
      source: typeof item.source === "string" ? item.source : null,
      detail: item.detail,
      reference: typeof item.reference === "string" ? item.reference : null,
    });
  }
  return { decision, reasonCodes: value.reasonCodes, summary: value.summary.trim(), evidence };
}

function stripCodeFence(value: string): string {
  return value.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "").trim();
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
