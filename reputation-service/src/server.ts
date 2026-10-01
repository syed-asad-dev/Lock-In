import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { loadConfig, type ServiceConfig } from "./config.js";
import { evaluateRequest, providersFromConfig, type ProviderSet } from "./evaluate.js";
import { MAX_REQUEST_BYTES, parseEvaluateRequest } from "./validation.js";

export function createAdmissionServer(config: ServiceConfig = loadConfig(), providers: ProviderSet = providersFromConfig(config)) {
  return createServer(async (request, response) => {
    if (request.method !== "POST" || request.url?.split("?", 1)[0] !== "/v1/evaluate") {
      writeJson(response, 404, { error: "not_found" }); return;
    }
    if (!authorized(request, config.backendBearerToken)) {
      writeJson(response, 401, { error: "unauthorized" }); return;
    }
    try {
      const parsed = parseEvaluateRequest(JSON.parse(await readBody(request)));
      if (!parsed) { writeJson(response, 400, { error: "malformed_request" }); return; }
      writeJson(response, 200, await evaluateRequest(parsed, providers, config.evaluationDeadlineMs));
    } catch (error) {
      if (error instanceof SyntaxError) {
        writeJson(response, 400, { error: "malformed_json" });
        return;
      }
      writeJson(response, error instanceof RequestError ? error.status : 500, { error: error instanceof RequestError ? error.code : "evaluation_failure" });
    }
  });
}

export function startServer(config: ServiceConfig = loadConfig(), providers: ProviderSet = providersFromConfig(config)) {
  const server = createAdmissionServer(config, providers);
  server.listen(config.port, "0.0.0.0");
  return server;
}

function authorized(request: IncomingMessage, expected: string | null): boolean {
  if (!expected) return false;
  const value = request.headers.authorization;
  return typeof value === "string" && value.startsWith("Bearer ") && value.slice(7) === expected;
}

async function readBody(request: IncomingMessage): Promise<string> {
  let total = 0; const chunks: Buffer[] = [];
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk); total += buffer.length;
    if (total > MAX_REQUEST_BYTES) throw new RequestError(413, "request_too_large"); chunks.push(buffer);
  }
  return Buffer.concat(chunks).toString("utf8");
}

class RequestError extends Error { constructor(public readonly status: number, public readonly code: string) { super(code); } }
function writeJson(response: ServerResponse, status: number, body: unknown): void {
  response.statusCode = status; response.setHeader("Content-Type", "application/json; charset=utf-8"); response.end(JSON.stringify(body));
}
