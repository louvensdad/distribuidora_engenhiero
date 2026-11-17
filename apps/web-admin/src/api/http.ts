import type { Problem } from "./types";

/** Erro de API no formato de `erro.schema.json`. Nunca carrega token nem dado sensível. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly fieldErrors: { field: string; message: string }[];

  constructor(p: Problem) {
    super(p.detail ?? p.title ?? "Erro inesperado");
    this.status = p.status;
    this.code = p.code;
    this.requestId = p.request_id;
    this.fieldErrors = p.errors ?? [];
  }
}

export const UNAUTHENTICATED_EVENT = "engenheiro:nao-autenticado";
const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);

function readCookie(name: string): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith(name + "="))
    ?.slice(name.length + 1);
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT";
  body?: unknown;
  ifMatch?: string;
  /** Comandos de estoque/financeiros: a mesma chave em nova tentativa nunca duplica o efeito. */
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  data: T;
  etag: string | null;
}

/**
 * Chamada à API na mesma origem. A sessão vai no cookie HttpOnly (o JavaScript não o lê);
 * mutações levam o token de CSRF no cabeçalho X-XSRF-TOKEN.
 */
export async function request<T>(path: string, opts: RequestOptions = {}): Promise<ApiResponse<T>> {
  const method = opts.method ?? "GET";
  const headers: Record<string, string> = { Accept: "application/json" };
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  if (opts.ifMatch) headers["If-Match"] = opts.ifMatch;
  if (opts.idempotencyKey) headers["Idempotency-Key"] = opts.idempotencyKey;
  if (!SAFE.has(method)) {
    const csrf = readCookie("XSRF-TOKEN");
    if (csrf) headers["X-XSRF-TOKEN"] = decodeURIComponent(csrf);
  }

  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === "AbortError") throw e;
    throw new ApiError({ status: 0, code: "NETWORK_ERROR", detail: "Sem conexão com o servidor." });
  }

  const text = await res.text();
  const json = text ? safeJson(text) : null;
  if (!res.ok) {
    if (res.status === 401) window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
    const p = (json as Problem | null) ?? { status: res.status, code: res.status === 403 ? "FORBIDDEN" : "HTTP_" + res.status };
    throw new ApiError({ ...p, status: res.status });
  }
  return { data: json as T, etag: res.headers.get("ETag") };
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const api = {
  get: <T,>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T,>(path: string, body: unknown, ifMatch?: string) => request<T>(path, { method: "POST", body, ifMatch }),
  put: <T,>(path: string, body: unknown, ifMatch?: string) => request<T>(path, { method: "PUT", body, ifMatch }),
  command: <T,>(path: string, body: unknown, idempotencyKey: string, ifMatch?: string) =>
    request<T>(path, { method: "POST", body, ifMatch, idempotencyKey }),
};

/** Envio de arquivo (multipart). Sessão por cookie + CSRF, como as demais mutações. */
export async function uploadFile<T>(path: string, file: File, fields: Record<string, string> = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  const csrf = readCookie("XSRF-TOKEN");
  if (csrf) headers["X-XSRF-TOKEN"] = decodeURIComponent(csrf);
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  form.append("file", file);
  let res: Response;
  try {
    res = await fetch(path, { method: "POST", headers, body: form, credentials: "same-origin" });
  } catch {
    throw new ApiError({ status: 0, code: "NETWORK_ERROR", detail: "Sem conexão com o servidor." });
  }
  const text = await res.text();
  const json = text ? safeJson(text) : null;
  if (!res.ok) {
    if (res.status === 401) window.dispatchEvent(new Event(UNAUTHENTICATED_EVENT));
    throw new ApiError({ ...((json as Problem | null) ?? { code: "HTTP_" + res.status }), status: res.status } as Problem);
  }
  return json as T;
}

/** Nova chave de idempotência (uma por intenção do usuário; reusar em nova tentativa). */
export const newIdempotencyKey = () => "web-" + crypto.randomUUID();
