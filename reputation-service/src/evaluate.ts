import { loadConfig, type ServiceConfig } from "./config.js";
import { evaluateAdmission } from "./policy.js";
import type { AiClassifierProvider } from "./providers/ai.js";
import { OpenAiCompatibleClassifierProvider } from "./providers/ai.js";
import type { PlayMetadataProvider } from "./providers/play.js";
import { Matters42PlayMetadataProvider } from "./providers/play.js";
import type { WebSearchProvider } from "./providers/brave.js";
import { BraveWebSearchProvider } from "./providers/brave.js";
import type { EvaluationDependencies, EvaluateRequest, ProviderOutcome, ReputationResponse } from "./types.js";
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

export async function evaluateRequest(request: EvaluateRequest, providers: ProviderSet, evaluationDeadlineMs = 12000): Promise<ReputationResponse> {
  // Run deterministic local policy first so hard-block/browser/VPN decisions
  // never wait on or depend on an external provider.
  const deterministic = evaluateAdmission(request, unavailableDependencies());
  if (deterministic.classificationDecision === "DENY") return deterministic;

  const deadline = Date.now() + evaluationDeadlineMs;
  const play = await runWithinDeadline(() => providers.play.lookup(request.identity), deadline, "PLAY_EVALUATION_DEADLINE");
  const web = await runWithinDeadline(() => providers.web.search(request.identity, request.audit, play.value), deadline, "WEB_EVALUATION_DEADLINE");
  const ai = await runWithinDeadline(() => providers.ai.classify({
    identity: request.identity,
    audit: request.audit,
    play: play.value,
    web: web.value,
  }), deadline, "AI_EVALUATION_DEADLINE");
  const dependencies: EvaluationDependencies = { play, web, ai };
  return evaluateAdmission(request, dependencies);
}

async function runWithinDeadline<T>(
  operation: () => Promise<ProviderOutcome<T>>,
  deadline: number,
  errorCode: string,
): Promise<ProviderOutcome<T>> {
  const remaining = deadline - Date.now();
  if (remaining <= 0) return unavailable(errorCode);
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation(),
      new Promise<ProviderOutcome<T>>((resolve) => {
        timer = setTimeout(() => resolve(unavailable(errorCode)), remaining);
      }),
    ]);
  } catch {
    return unavailable(errorCode);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

export function unavailableDependencies(): EvaluationDependencies {
  return {
    play: unavailable("PLAY_PROVIDER_NOT_CONFIGURED"),
    web: unavailable("WEB_PROVIDER_NOT_CONFIGURED"),
    ai: unavailable("AI_PROVIDER_NOT_CONFIGURED"),
  };
}
