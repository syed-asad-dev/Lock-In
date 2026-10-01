import { fetchJson, ProviderHttpError } from "../http.js";
import type { PackageIdentity, PlayMetadata, ProviderOutcome } from "../types.js";
import { unavailable } from "../types.js";

export interface PlayMetadataProvider {
  lookup(identity: PackageIdentity): Promise<ProviderOutcome<PlayMetadata>>;
}

export interface JsonFetcher {
  (url: string, init: RequestInit, timeoutMs: number, maxBytes?: number): Promise<unknown>;
}

const LOOKUP_ENDPOINT = "https://data.42matters.com/api/v2.0/android/apps/lookup.json";

export class Matters42PlayMetadataProvider implements PlayMetadataProvider {
  constructor(
    private readonly apiKey: string | null,
    private readonly timeoutMs: number,
    private readonly fetcher: JsonFetcher = fetchJson,
  ) {}

  async lookup(identity: PackageIdentity): Promise<ProviderOutcome<PlayMetadata>> {
    if (!this.apiKey) return unavailable("PLAY_PROVIDER_NOT_CONFIGURED");
    const url = new URL(LOOKUP_ENDPOINT);
    url.searchParams.set("p", identity.packageName);
    url.searchParams.set("access_token", this.apiKey);
    try {
      const body = await this.fetcher(url.toString(), { headers: { Accept: "application/json" } }, this.timeoutMs);
      if (!isRecord(body)) return unavailable("PLAY_MALFORMED_RESPONSE");
      const packageName = stringValue(body.package_name);
      if (!packageName || packageName !== identity.packageName) {
        return unavailable("PLAY_PACKAGE_IDENTITY_MISMATCH");
      }
      return {
        available: true,
        errorCode: null,
        value: {
          packageName,
          title: stringValue(body.title),
          developer: stringValue(body.developer),
          description: stringValue(body.description),
          category: stringValue(body.category),
          marketSource: stringValue(body.market_source),
          marketStatus: stringValue(body.market_status),
          marketUrl: stringValue(body.market_url),
          version: stringValue(body.version),
          website: stringValue(body.website),
        },
      };
    } catch (error) {
      return unavailable(error instanceof ProviderHttpError ? `PLAY_${error.code}` : "PLAY_PROVIDER_FAILURE");
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}
