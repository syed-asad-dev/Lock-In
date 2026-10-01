import { fetchJson, ProviderHttpError } from "../http.js";
import type {
  AppAuditResult,
  PackageIdentity,
  PlayMetadata,
  ProviderOutcome,
  SearchEvidence,
  WebSearchBundle,
} from "../types.js";
import { unavailable } from "../types.js";
import type { JsonFetcher } from "./play.js";

export interface WebSearchProvider {
  search(identity: PackageIdentity, audit: AppAuditResult, play: PlayMetadata | null): Promise<ProviderOutcome<WebSearchBundle>>;
}

const SEARCH_ENDPOINT = "https://api.search.brave.com/res/v1/web/search";

export class BraveWebSearchProvider implements WebSearchProvider {
  constructor(
    private readonly apiKey: string | null,
    private readonly timeoutMs: number,
    private readonly maxResults: number,
    private readonly fetcher: JsonFetcher = fetchJson,
  ) {}

  async search(
    identity: PackageIdentity,
    audit: AppAuditResult,
    play: PlayMetadata | null,
  ): Promise<ProviderOutcome<WebSearchBundle>> {
    if (!this.apiKey) return unavailable("WEB_PROVIDER_NOT_CONFIGURED");
    const queries = buildQueries(identity, audit, play);
    const results: SearchEvidence[] = [];
    try {
      for (const query of queries) {
        const url = new URL(SEARCH_ENDPOINT);
        url.searchParams.set("q", query);
        url.searchParams.set("count", "5");
        url.searchParams.set("safesearch", "strict");
        url.searchParams.set("extra_snippets", "true");
        const body = await this.fetcher(url.toString(), {
          headers: {
            Accept: "application/json",
            "X-Subscription-Token": this.apiKey,
          },
        }, this.timeoutMs);
        if (!isRecord(body)) throw new ProviderHttpError("MALFORMED_RESPONSE");
        const web = body.web;
        if (!isRecord(web) || !Array.isArray(web.results)) throw new ProviderHttpError("MALFORMED_RESPONSE");
        for (const item of web.results) {
          if (!isRecord(item)) continue;
          const title = stringValue(item.title);
          const resultUrl = stringValue(item.url);
          const description = stringValue(item.description);
          if (!title || !resultUrl || !description) continue;
          const extraSnippets = Array.isArray(item.extra_snippets)
            ? item.extra_snippets.filter((v): v is string => typeof v === "string").slice(0, 3)
            : [];
          results.push({
            query,
            title,
            url: resultUrl,
            description,
            extraSnippets,
            sourceDomain: domainOf(resultUrl),
          });
          if (results.length >= this.maxResults) break;
        }
        if (results.length >= this.maxResults) break;
      }
      return {
        available: true,
        errorCode: null,
        value: {
          provider: "brave-search",
          queries,
          results: dedupeResults(results).slice(0, this.maxResults),
          checkedAtEpochMs: Date.now(),
        },
      };
    } catch (error) {
      return unavailable(error instanceof ProviderHttpError ? `WEB_${error.code}` : "WEB_PROVIDER_FAILURE");
    }
  }
}

export function buildQueries(identity: PackageIdentity, audit: AppAuditResult, play: PlayMetadata | null): string[] {
  const packageName = identity.packageName;
  const title = play?.title || audit.appLabel;
  const developer = play?.developer;
  return dedupeStrings([
    `"${packageName}"`,
    `${packageName} browser`,
    `${packageName} downloader`,
    `${packageName} "video downloader"`,
    `${packageName} "private browser"`,
    `${packageName} VPN`,
    `${packageName} proxy`,
    `${title} ${developer ?? ""} browser`,
    `${title} downloader`,
    ...(developer ? [`${developer} browser`, `${developer} VPN`] : []),
  ]).slice(0, 11);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function domainOf(value: string): string | null {
  try { return new URL(value).hostname.toLowerCase(); } catch { return null; }
}

function dedupeResults(results: SearchEvidence[]): SearchEvidence[] {
  const seen = new Set<string>();
  return results.filter((result) => {
    const key = `${result.url}\n${result.title}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function dedupeStrings(values: string[]): string[] {
  return [...new Set(values.map((value) => value.replace(/\s+/g, " ").trim()).filter(Boolean))];
}
