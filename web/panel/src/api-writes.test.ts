import { afterEach, describe, expect, it, vi } from "vitest";
import { request } from "../../shared/src/api";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("gravações na API", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("a mesma gravação em andamento é reaproveitada (duplo clique não cria dois registros)", async () => {
    let release!: (r: Response) => void;
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { release = resolve; }));
    vi.stubGlobal("fetch", fetchMock);

    const a = request<{ id: string }>("t", "POST", "/api/v1/x", { n: 1 });
    const b = request<{ id: string }>("t", "POST", "/api/v1/x", { n: 1 });
    release(jsonResponse({ id: "1" }));

    expect(await a).toEqual({ id: "1" });
    expect(await b).toEqual({ id: "1" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("gravações diferentes e consultas (GET) não são reaproveitadas", async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await Promise.all([
      request("t", "POST", "/api/v1/x", { n: 1 }),
      request("t", "POST", "/api/v1/x", { n: 2 }),
      request("t", "GET", "/api/v1/x"),
      request("t", "GET", "/api/v1/x"),
    ]);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("depois de terminar, a mesma gravação pode ser enviada de novo", async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await request("t", "POST", "/api/v1/x", { n: 1 });
    await request("t", "POST", "/api/v1/x", { n: 1 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("o indicador de atividade liga durante a gravação e desliga ao terminar, inclusive em erro", async () => {
    let release!: (r: Response) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((resolve) => { release = resolve; })));

    const call = request("t", "PUT", "/api/v1/y", { a: 1 }).catch(() => "falhou");
    expect(document.documentElement.dataset.busy).toBe("1");
    release(jsonResponse({ detail: "erro" }, 500));
    expect(await call).toBe("falhou");
    expect(document.documentElement.dataset.busy).toBeUndefined();
  });
});
