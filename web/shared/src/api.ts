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

export async function request<T>(token: string, method: string, path: string, body?: unknown,
                                 retried = false): Promise<T | null> {
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
    if (renewed) return request<T>(renewed, method, path, body, true);
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
