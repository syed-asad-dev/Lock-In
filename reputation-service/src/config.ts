export interface ServiceConfig {
  port: number;
  backendBearerToken: string | null;
  matters42ApiKey: string | null;
  braveSearchApiKey: string | null;
  aiProviderApiKey: string | null;
  aiProviderBaseUrl: string | null;
  aiProviderModel: string;
  providerTimeoutMs: number;
  maxProviderResults: number;
}

function positiveInt(value: string | undefined, fallback: number, max: number): number {
  const parsed = Number.parseInt(value ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(parsed, max) : fallback;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): ServiceConfig {
  return {
    port: positiveInt(env.PORT, 8080, 65535),
    backendBearerToken: env.ADMISSION_BACKEND_BEARER_TOKEN?.trim() || null,
    matters42ApiKey: env.MATTERS42_API_KEY?.trim() || null,
    braveSearchApiKey: env.BRAVE_SEARCH_API_KEY?.trim() || null,
    aiProviderApiKey: env.AI_PROVIDER_API_KEY?.trim() || null,
    aiProviderBaseUrl: env.AI_PROVIDER_BASE_URL?.trim() || null,
    aiProviderModel: env.AI_PROVIDER_MODEL?.trim() || "",
    providerTimeoutMs: positiveInt(env.PROVIDER_TIMEOUT_MS, 10000, 30000),
    maxProviderResults: positiveInt(env.MAX_PROVIDER_RESULTS, 24, 50),
  };
}
