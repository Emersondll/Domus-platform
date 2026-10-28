/** Cliente HTTP mínimo da API da plataforma, compartilhado entre o painel e o app do morador. */

export class ApiError extends Error {
  constructor(public status: number, public detail: string) {
    super(`${status}: ${detail}`);
  }
}

let onUnauthorized: (() => void) | null = null;

/** O app registra o que fazer quando a API responde 401 (token expirado ou inválido): normalmente, novo login. */
export function setUnauthorizedHandler(fn: (() => void) | null): void {
  onUnauthorized = fn;
}

let refresher: (() => Promise<string | null>) | null = null;

/** O app registra como obter um novo access token; num 401 a requisição é refeita uma vez com ele. */
export function setTokenRefresher(fn: (() => Promise<string | null>) | null): void {
  refresher = fn;
}

export type Page<T> = { items: T[]; page: number; size: number; totalElements: number };

/** Somente desenvolvimento: troca um subject semeado por um token. Em produção o login é OIDC (Keycloak). */
export async function devLogin(subject: string): Promise<string> {
  const res = await fetch(`/dev/token?subject=${encodeURIComponent(subject)}`);
  if (!res.ok) throw new ApiError(res.status, "Dev login unavailable");
  return (await res.json()).access_token as string;
}

let busy = 0;

/** Liga/desliga o indicador global de "trabalhando" (barra fina no topo, só CSS) enquanto houver gravação em andamento. */
function trackBusy(delta: 1 | -1): void {
  busy += delta;
  if (typeof document !== "undefined") {
    if (busy > 0) document.documentElement.dataset.busy = "1";
    else delete document.documentElement.dataset.busy;
  }
}

const inFlightWrites = new Map<string, Promise<unknown>>();

/**
 * Chamada à API. Gravações (qualquer método diferente de GET) mostram o indicador de atividade e, se a MESMA gravação
 * (método, caminho e corpo) já estiver em andamento, reaproveitam a requisição em vez de enviar outra: um duplo clique
 * em "Registrar" não cria dois registros.
 */
export async function request<T>(token: string, method: string, path: string, body?: unknown,
                                 retried = false): Promise<T | null> {
  if (method === "GET") return send<T>(token, method, path, body, retried);
  const key = `${method} ${path} ${body === undefined ? "" : JSON.stringify(body)}`;
  const running = inFlightWrites.get(key);
  if (running) return running as Promise<T | null>;
  trackBusy(1);
  const call = send<T>(token, method, path, body, retried).finally(() => {
    inFlightWrites.delete(key);
    trackBusy(-1);
  });
  inFlightWrites.set(key, call);
  return call;
}

async function send<T>(token: string, method: string, path: string, body: unknown, retried: boolean): Promise<T | null> {
  const res = await fetch(path, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
  if (res.status === 401 && !retried && refresher) {
    const renewed = await refresher();
    if (renewed) return send<T>(renewed, method, path, body, true);
  }
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = (await res.json()).detail ?? detail;
    } catch {
      /* corpo sem JSON */
    }
    throw new ApiError(res.status, detail);
  }
  return (await res.json()) as T;
}

/** Baixa uma imagem protegida (o cabeçalho Authorization não vai em `<img>`) e devolve uma URL local para exibi-la. */
export async function requestImageUrl(token: string, path: string): Promise<string> {
  const res = await fetch(path, { headers: { Authorization: `Bearer ${token}` } });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) throw new ApiError(res.status, res.statusText);
  return URL.createObjectURL(await res.blob());
}

/** Envia um arquivo (imagem) como corpo bruto, com o tipo do próprio arquivo. A API recompõe e valida o conteúdo. */
export async function uploadFile<T>(token: string, path: string, file: Blob): Promise<T | null> {
  const res = await fetch(path, {
    method: "PUT",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": file.type || "application/octet-stream" },
    body: file,
  });
  if (res.status === 401) onUnauthorized?.();
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = (await res.json()).detail ?? detail;
    } catch {
      /* corpo sem JSON */
    }
    throw new ApiError(res.status, detail);
  }
  return res.status === 204 ? null : ((await res.json()) as T);
}
