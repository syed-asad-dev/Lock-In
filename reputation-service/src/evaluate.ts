import { loadConfig, type ServiceConfig } from "./config.js";
import { evaluateAdmission } from "./policy.js";
import type { AiClassifierProvider } from "./providers/ai.js";
import { OpenAiCompatibleClassifierProvider } from "./providers/ai.js";
import type { PlayMetadataProvider } from "./providers/play.js";
import { Matters42PlayMetadataProvider } from "./providers/play.js";
import type { WebSearchProvider } from "./providers/brave.js";
import { BraveWebSearchProvider } from "./providers/brave.js";
import type { EvaluationDependencies, EvaluateRequest, ReputationResponse } from "./types.js";
import { unavailable } from "./types.js";

export interface ProviderSet {
  play: PlayMetadataProvider;
  web: WebSearchProvider;
  ai: AiClassifierProvider;
}

export function providersFromConfig(config: ServiceConfig = loadConfig()): ProviderSet {
  return {
    play: new Matters42PlayMetadataProvider(config.matters42ApiKey, config.providerTimeoutMs),
    web: new BraveWebSearchProvider(config.braveSearchApiKey, config.providerTimeoutMs, config.maxProviderResults),
    ai: new OpenAiCompatibleClassifierProvider(
      config.aiProviderApiKey,
      config.aiProviderBaseUrl,
      config.aiProviderModel,
      config.providerTimeoutMs,
    ),
  };
}

export async function evaluateRequest(request: EvaluateRequest, providers: ProviderSet): Promise<ReputationResponse> {
  const play = await providers.play.lookup(request.identity);
  const web = await providers.web.search(request.identity, request.audit, play.value);
  const ai = await providers.ai.classify({
    identity: request.identity,
    audit: request.audit,
    play: play.value,
    web: web.value,
  });
  const dependencies: EvaluationDependencies = { play, web, ai };
  return evaluateAdmission(request, dependencies);
}

export function unavailableDependencies(): EvaluationDependencies {
  return {
    play: unavailable("PLAY_PROVIDER_NOT_CONFIGURED"),
    web: unavailable("WEB_PROVIDER_NOT_CONFIGURED"),
    ai: unavailable("AI_PROVIDER_NOT_CONFIGURED"),
  };
}
