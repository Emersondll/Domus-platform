import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { request, setTokenRefresher, setUnauthorizedHandler } from "../../shared/src/api";
import { handleCallback, logout, refreshAccessToken, scheduleRefresh, type OidcConfig } from "../../shared/src/oidc";

const cfg: OidcConfig = { authority: "https://auth.example.test/realms/portaria", clientId: "portaria-panel" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

/** Faz um login "de verdade" pelo callback para o módulo guardar o refresh token em memória. */
async function signIn(refresh: string | undefined = "r1", expiresIn = 300) {
  sessionStorage.setItem("oidc.state", "s");
  sessionStorage.setItem("oidc.verifier", "v");
  vi.stubGlobal("location", { ...window.location, search: "?code=c&state=s", pathname: "/", origin: "https://panel.example.test" });
  vi.stubGlobal("fetch", vi.fn(async () => json({ access_token: "a1", refresh_token: refresh, expires_in: expiresIn })));
  await handleCallback(cfg);
}

describe("renovação silenciosa do token", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.stubGlobal("location", { ...window.location, origin: "https://panel.example.test", assign: vi.fn() });
    logout(cfg); // limpa o refresh token em memória (o assign é simulado: não navega)
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    setTokenRefresher(null);
    setUnauthorizedHandler(null);
  });

  it("sem refresh token não chama o Keycloak", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    expect(await refreshAccessToken(cfg)).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("troca o refresh token por um novo access token (grant refresh_token, sem segredo)", async () => {
    await signIn();
    const fetchMock = vi.fn(async () => json({ access_token: "a2", refresh_token: "r2", expires_in: 300 }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await refreshAccessToken(cfg)).toBe("a2");
    const body = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as URLSearchParams;
    expect(body.get("grant_type")).toBe("refresh_token");
    expect(body.get("refresh_token")).toBe("r1");
    expect(body.get("client_secret")).toBeNull();
    // o refresh token rotacionado passa a valer
    const second = vi.fn(async () => json({ access_token: "a3", expires_in: 300 }));
    vi.stubGlobal("fetch", second);
    await refreshAccessToken(cfg);
    expect(((second.mock.calls[0] as unknown as [string, RequestInit])[1].body as URLSearchParams).get("refresh_token")).toBe("r2");
  });

  it("chamadas simultâneas compartilham uma única requisição (o Keycloak invalida o refresh token usado)", async () => {
    await signIn();
    const fetchMock = vi.fn(async () => json({ access_token: "a2", refresh_token: "r2", expires_in: 300 }));
    vi.stubGlobal("fetch", fetchMock);
    const [a, b] = await Promise.all([refreshAccessToken(cfg), refreshAccessToken(cfg)]);
    expect([a, b]).toEqual(["a2", "a2"]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("sessão SSO expirada: devolve null e esquece o refresh token (não insiste)", async () => {
    await signIn();
    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "invalid_grant" }, 400)));
    expect(await refreshAccessToken(cfg)).toBeNull();
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    expect(await refreshAccessToken(cfg)).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("falha de rede mantém o refresh token para a próxima tentativa", async () => {
    await signIn();
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("offline"); }));
    expect(await refreshAccessToken(cfg)).toBeNull();
    vi.stubGlobal("fetch", vi.fn(async () => json({ access_token: "a2", expires_in: 300 })));
    expect(await refreshAccessToken(cfg)).toBe("a2");
  });

  it("agenda a renovação em 80% da vida do token e reagenda a cada sucesso", async () => {
    await signIn("r1", 100); // 100 s -> renova aos 80 s
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn(async () => json({ access_token: "novo", refresh_token: "rN", expires_in: 100 })));
    const onToken = vi.fn();
    const cancel = scheduleRefresh(cfg, onToken);
    await vi.advanceTimersByTimeAsync(79_000);
    expect(onToken).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2_000);
    expect(onToken).toHaveBeenCalledWith("novo");
    await vi.advanceTimersByTimeAsync(81_000);
    expect(onToken).toHaveBeenCalledTimes(2);
    cancel();
    await vi.advanceTimersByTimeAsync(200_000);
    expect(onToken).toHaveBeenCalledTimes(2);
  });
});

describe("401 da API", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setTokenRefresher(null);
    setUnauthorizedHandler(null);
  });

  it("renova o token e refaz a requisição uma única vez", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (_p: string, init: RequestInit) => {
      const auth = (init.headers as Record<string, string>).Authorization;
      calls.push(auth);
      return auth === "Bearer velho" ? new Response(null, { status: 401 }) : json({ ok: true });
    }));
    const unauthorized = vi.fn();
    setUnauthorizedHandler(unauthorized);
    setTokenRefresher(async () => "novo");
    expect(await request("velho", "GET", "/api/v1/x")).toEqual({ ok: true });
    expect(calls).toEqual(["Bearer velho", "Bearer novo"]);
    expect(unauthorized).not.toHaveBeenCalled();
  });

  it("se a renovação falha, volta à tela de entrada", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ detail: "expired" }), { status: 401 })));
    const unauthorized = vi.fn();
    setUnauthorizedHandler(unauthorized);
    setTokenRefresher(async () => null);
    await expect(request("velho", "GET", "/api/v1/x")).rejects.toThrow("401");
    expect(unauthorized).toHaveBeenCalledTimes(1);
  });

  it("não entra em laço: 401 depois da renovação também volta à tela de entrada", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ detail: "no" }), { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);
    const unauthorized = vi.fn();
    setUnauthorizedHandler(unauthorized);
    setTokenRefresher(async () => "novo");
    await expect(request("velho", "GET", "/api/v1/x")).rejects.toThrow("401");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(unauthorized).toHaveBeenCalledTimes(1);
  });
});
