import { afterEach, describe, expect, it, vi } from "vitest";
import { request, setClientName } from "../../shared/src/api";

describe("client identification", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("tells the API which app is calling so the audit trail can tell a click from a manual call", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    setClientName("PANEL");
    await request("tok", "POST", "/api/v1/x", { a: 1 });
    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect((init.headers as Record<string, string>)["X-Domus-Client"]).toBe("PANEL");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer tok");
  });
});
