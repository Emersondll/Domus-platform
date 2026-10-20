import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { challengeFor, handleCallback, startLogin, type OidcConfig } from "../../shared/src/oidc";

const cfg: OidcConfig = { authority: "https://auth.example.test/realms/portaria", clientId: "portaria-panel" };

describe("OIDC authorization code + PKCE", () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it("computes the S256 challenge exactly as RFC 7636 appendix B", async () => {
    expect(await challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe(
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
    );
  });

  it("sends the browser to the authorization endpoint with PKCE, state and a fixed redirect", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, origin: "https://panel.example.test", assign, search: "", pathname: "/" });
    await startLogin(cfg);
    const url = new URL(assign.mock.calls[0][0]);
    expect(url.origin + url.pathname).toBe("https://auth.example.test/realms/portaria/protocol/openid-connect/auth");
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("redirect_uri")).toBe("https://panel.example.test/");
    expect(url.searchParams.get("state")).toBe(sessionStorage.getItem("oidc.state"));
    // o verifier fica só no navegador; o que vai pela URL é apenas o desafio derivado dele
    expect(url.searchParams.get("code_challenge")).toBe(await challengeFor(sessionStorage.getItem("oidc.verifier")!));
  });

  it("rejects a callback whose state does not match (login CSRF) and never calls the token endpoint", async () => {
    sessionStorage.setItem("oidc.state", "expected");
    sessionStorage.setItem("oidc.verifier", "v");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("location", { ...window.location, search: "?code=abc&state=forged", pathname: "/", origin: "https://panel.example.test" });
    await expect(handleCallback(cfg)).rejects.toThrow("invalid login state");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("exchanges the code with the verifier and cleans the URL; the state can only be used once", async () => {
    sessionStorage.setItem("oidc.state", "good");
    sessionStorage.setItem("oidc.verifier", "the-verifier");
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ access_token: "tok" }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("location", { ...window.location, search: "?code=abc&state=good", pathname: "/", origin: "https://panel.example.test" });
    expect(await handleCallback(cfg)).toBe("tok");
    const body = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as URLSearchParams;
    expect(body.get("grant_type")).toBe("authorization_code");
    expect(body.get("code_verifier")).toBe("the-verifier");
    expect(body.get("client_id")).toBe("portaria-panel");
    expect(sessionStorage.getItem("oidc.verifier")).toBeNull();
    await expect(handleCallback(cfg)).rejects.toThrow("invalid login state"); // reuso
  });

  it("is a no-op when the URL is not a login callback", async () => {
    vi.stubGlobal("location", { ...window.location, search: "", pathname: "/", origin: "https://panel.example.test" });
    expect(await handleCallback(cfg)).toBeNull();
  });
});
