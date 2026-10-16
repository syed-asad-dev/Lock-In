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
    const apiKey = this.apiKey;
    if (!apiKey) return unavailable("WEB_PROVIDER_NOT_CONFIGURED");
    const queries = buildQueries(identity, audit, play);
    const results: SearchEvidence[] = [];
    try {
      let nextQuery = 0;
      const worker = async (): Promise<void> => {
        while (true) {
          const queryIndex = nextQuery++;
          const query = queries[queryIndex];
          if (!query || results.length >= this.maxResults) return;
        const url = new URL(SEARCH_ENDPOINT);
        url.searchParams.set("q", query);
        url.searchParams.set("count", "5");
        url.searchParams.set("safesearch", "strict");
        url.searchParams.set("extra_snippets", "true");
        const body = await this.fetcher(url.toString(), {
          headers: {
            Accept: "application/json",
            "X-Subscription-Token": apiKey,
          },
        }, this.timeoutMs);
        if (!isRecord(body)) throw new ProviderHttpError("MALFORMED_RESPONSE");
        const web = body.web;
        if (!isRecord(web) || !Array.isArray(web.results)) throw new ProviderHttpError("MALFORMED_RESPONSE");
        for (const item of web.results) {
          if (!isRecord(item)) continue;
          const title = stringValue(item.title)?.slice(0, 500) ?? null;
          const resultUrl = stringValue(item.url);
          const description = stringValue(item.description)?.slice(0, 1000) ?? null;
          if (!title || !resultUrl || !description) continue;
          const extraSnippets = Array.isArray(item.extra_snippets)
            ? item.extra_snippets.filter((v): v is string => typeof v === "string").slice(0, 3).map((v) => v.slice(0, 1000))
            : [];
          results.push({
            query,
            title,
            url: resultUrl,
            description,
            extraSnippets,
            sourceDomain: registrableDomainOf(resultUrl),
          });
          if (results.length >= this.maxResults) break;
        }
        }
      };
      await Promise.all([worker(), worker(), worker()]);
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
    `${packageName} browser "private browsing" incognito`,
    `${packageName} downloader "video downloader"`,
    `${packageName} VPN proxy bypass unblock`,
    `${title} ${developer ?? ""} browser`,
    `${title} ${developer ?? ""} downloader`,
    ...(developer ? [`${developer} VPN proxy`, `${developer} bypass unblock`] : []),
  ]).slice(0, 8);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

export function registrableDomainOf(value: string): string | null {
  try {
    const hostname = new URL(value).hostname.toLowerCase().replace(/\.$/, "");
    if (!hostname || hostname === "localhost" || /^\d{1,3}(?:\.\d{1,3}){3}$/.test(hostname)) return hostname || null;
    const labels = hostname.split(".").filter(Boolean);
    if (labels.length < 2) return hostname;
    const suffix = labels.slice(-2).join(".");
    const multiLabelSuffixes = new Set([
      "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au",
      "co.nz", "com.br", "co.jp", "co.in", "com.cn", "com.sg",
    ]);
    const count = multiLabelSuffixes.has(suffix) && labels.length >= 3 ? 3 : 2;
    return labels.slice(-count).join(".");
  } catch { return null; }
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
