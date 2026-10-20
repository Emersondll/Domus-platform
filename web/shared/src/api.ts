/** Cliente HTTP mínimo da API da plataforma, compartilhado entre o painel e o app do morador. */

export class ApiError extends Error {
  constructor(public status: number, public detail: string) {
    super(`${status}: ${detail}`);
  }
}

export type Page<T> = { items: T[]; page: number; size: number; totalElements: number };

/** Somente desenvolvimento: troca um subject semeado por um token. Em produção o login é OIDC (Keycloak). */
export async function devLogin(subject: string): Promise<string> {
  const res = await fetch(`/dev/token?subject=${encodeURIComponent(subject)}`);
  if (!res.ok) throw new ApiError(res.status, "Dev login unavailable");
  return (await res.json()).access_token as string;
}

export async function request<T>(token: string, method: string, path: string, body?: unknown): Promise<T | null> {
  const res = await fetch(path, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
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
