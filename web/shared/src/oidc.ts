/**
 * Login OIDC (Authorization Code + PKCE) sem biblioteca, para o painel e o app do morador.
 * - Cliente público: não há segredo no navegador; o PKCE (S256) protege a troca do código.
 * - `state` aleatório por tentativa, verificado no retorno (proteção contra CSRF de login).
 * - O token de acesso fica só em memória (menor exposição a XSS); ao recarregar a página, o SSO do Keycloak
 *   devolve um novo código sem pedir senha de novo (e o MFA já foi cumprido na sessão).
 * - MFA/OTP e bloqueio por tentativas são aplicados pelo Keycloak, não aqui.
 */

export type OidcConfig = { authority: string; clientId: string };

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
  return (await res.json()).access_token as string;
}

export function logout(cfg: OidcConfig): void {
  const q = new URLSearchParams({ client_id: cfg.clientId, post_logout_redirect_uri: redirectUri() });
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
