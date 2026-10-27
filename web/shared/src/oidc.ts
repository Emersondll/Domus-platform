/**
 * Login OIDC (Authorization Code + PKCE) sem biblioteca, para o painel e o app do morador.
 * - Cliente público: não há segredo no navegador; o PKCE (S256) protege a troca do código.
 * - `state` aleatório por tentativa, verificado no retorno (proteção contra CSRF de login).
 * - Access token e refresh token ficam só em memória (menor exposição a XSS). O access token (5 min) é renovado em
 *   segundo plano com o refresh token; ao recarregar a página, o SSO do Keycloak devolve um novo código sem pedir
 *   senha de novo (e o MFA já foi cumprido na sessão).
 * - MFA/OTP e bloqueio por tentativas são aplicados pelo Keycloak, não aqui.
 */

export type OidcConfig = { authority: string; clientId: string };

type TokenResponse = { access_token: string; refresh_token?: string; id_token?: string; expires_in?: number };

// Somente em memória, de propósito: nada de localStorage/sessionStorage para token.
let refreshToken: string | null = null;
// O id_token só serve de "id_token_hint" no logout (o Keycloak encerra a sessão sem pedir confirmação).
let idToken: string | null = null;
let lifetimeMs = 300_000;
let inFlight: Promise<string | null> | null = null;

function remember(t: TokenResponse): void {
  refreshToken = t.refresh_token ?? refreshToken;
  idToken = t.id_token ?? idToken;
  if (t.expires_in) lifetimeMs = t.expires_in * 1000;
}

const STATE_KEY = "oidc.state";
const VERIFIER_KEY = "oidc.verifier";

function b64url(bytes: Uint8Array): string {
  let s = "";
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function randomString(byteLength = 32): string {
  return b64url(crypto.getRandomValues(new Uint8Array(byteLength)));
}

/** code_challenge = BASE64URL(SHA256(verifier)) — RFC 7636, método S256. */
export async function challengeFor(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return b64url(new Uint8Array(digest));
}

/** Lê a configuração publicada pelo proxy em /config.json; null se não houver (ex.: servidor de desenvolvimento). */
export async function loadOidcConfig(): Promise<OidcConfig | null> {
  try {
    const res = await fetch("/config.json", { cache: "no-store" });
    if (!res.ok || !(res.headers.get("content-type") ?? "").includes("json")) return null;
    const c = await res.json();
    return c.authority && c.clientId ? { authority: c.authority, clientId: c.clientId } : null;
  } catch {
    return null;
  }
}

function redirectUri(): string {
  return `${window.location.origin}/`;
}

export async function startLogin(cfg: OidcConfig): Promise<void> {
  const verifier = randomString(48);
  const state = randomString(24);
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(STATE_KEY, state);
  const q = new URLSearchParams({
    client_id: cfg.clientId,
    redirect_uri: redirectUri(),
    response_type: "code",
    scope: "openid profile email",
    state,
    code_challenge: await challengeFor(verifier),
    code_challenge_method: "S256",
  });
  window.location.assign(`${cfg.authority}/protocol/openid-connect/auth?${q}`);
}

/**
 * Se a URL traz um retorno do Keycloak (?code=&state=), valida o state e troca o código pelo token.
 * Retorna o access_token, ou null se não for um retorno de login. Lança erro se o retorno for inválido.
 */
export async function handleCallback(cfg: OidcConfig): Promise<string | null> {
  const params = new URLSearchParams(window.location.search);
  const code = params.get("code");
  if (!code && !params.get("error")) return null;
  const expectedState = sessionStorage.getItem(STATE_KEY);
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  const state = params.get("state");
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
  window.history.replaceState({}, "", window.location.pathname); // tira code/state da URL
  if (params.get("error")) throw new Error(`login failed: ${params.get("error")}`);
  if (!expectedState || !verifier || state !== expectedState) throw new Error("invalid login state");
  const res = await fetch(`${cfg.authority}/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: cfg.clientId,
      code: code!,
      redirect_uri: redirectUri(),
      code_verifier: verifier,
    }),
  });
  if (!res.ok) throw new Error("token exchange failed");
  const token = (await res.json()) as TokenResponse;
  remember(token);
  return token.access_token;
}

/**
 * Renova o access token com o refresh token. Devolve null se não houver refresh token ou se o Keycloak recusar
 * (sessão SSO expirada ou revogada): aí o app volta à tela de entrada. Chamadas simultâneas compartilham uma só
 * requisição, porque o Keycloak invalida o refresh token usado.
 */
export function refreshAccessToken(cfg: OidcConfig): Promise<string | null> {
  if (!refreshToken) return Promise.resolve(null);
  inFlight ??= (async () => {
    try {
      const res = await fetch(`${cfg.authority}/protocol/openid-connect/token`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({ grant_type: "refresh_token", client_id: cfg.clientId, refresh_token: refreshToken! }),
      });
      if (!res.ok) {
        refreshToken = null;
        return null;
      }
      const token = (await res.json()) as TokenResponse;
      remember(token);
      return token.access_token;
    } catch {
      return null; // rede indisponível: mantém o refresh token para a próxima tentativa
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

/** Agenda a renovação em 80% da vida do token (mínimo 10 s) e reagenda a cada sucesso. Devolve a função que cancela. */
export function scheduleRefresh(cfg: OidcConfig, onToken: (token: string) => void): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let cancelled = false;
  const arm = () => {
    timer = setTimeout(async () => {
      const t = await refreshAccessToken(cfg);
      if (cancelled) return;
      if (t) {
        onToken(t);
        arm();
      }
    }, Math.max(10_000, lifetimeMs * 0.8));
  };
  arm();
  return () => {
    cancelled = true;
    clearTimeout(timer);
  };
}

export function logout(cfg: OidcConfig): void {
  refreshToken = null;
  const q = new URLSearchParams({ client_id: cfg.clientId, post_logout_redirect_uri: redirectUri() });
  // sem o id_token_hint o Keycloak mostra uma tela de confirmação e a sessão continua aberta até alguém confirmar
  if (idToken) q.set("id_token_hint", idToken);
  idToken = null;
  window.location.assign(`${cfg.authority}/protocol/openid-connect/logout?${q}`);
}

/** Nome para exibição, lido do próprio token (sem validar: quem valida é a API). */
export function displayNameFrom(token: string): string {
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return payload.preferred_username ?? payload.email ?? "usuário";
  } catch {
    return "usuário";
  }
}
