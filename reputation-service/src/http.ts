export class ProviderHttpError extends Error {
  constructor(public readonly code: string, public readonly status?: number) {
    super(code);
    this.name = "ProviderHttpError";
  }
}

export async function fetchJson(
  url: string,
  init: RequestInit,
  timeoutMs: number,
  maxBytes = 512 * 1024,
): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal, redirect: "error" });
    if (!response.ok) throw new ProviderHttpError(`HTTP_${response.status}`, response.status);
    if (!response.body) throw new ProviderHttpError("EMPTY_RESPONSE");
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        throw new ProviderHttpError("RESPONSE_TOO_LARGE");
      }
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    try {
      return JSON.parse(new TextDecoder().decode(bytes));
    } catch {
      throw new ProviderHttpError("MALFORMED_JSON");
    }
  } catch (error) {
    if (error instanceof ProviderHttpError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ProviderHttpError("TIMEOUT");
    }
    throw new ProviderHttpError("NETWORK_FAILURE");
  } finally {
    clearTimeout(timeout);
  }
}
