import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { StaffNotices } from "./StaffNotices";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("avisos da equipe", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("mostra os avisos não lidos e some quando o síndico marca como lido", async () => {
    let read = false;
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? "GET"} ${url}`);
      if (url.endsWith("/staff/notifications/n1/read")) { read = true; return json({ id: "n1" }); }
      return json({ items: [{ id: "n1", type: "CALL_AUTO_RELEASED", title: "Chamada devolvida à fila", body: "Uma chamada voltou à fila.",
        createdAt: new Date().toISOString(), readAt: read ? new Date().toISOString() : null }], page: 0, size: 20, totalElements: 1 });
    }));
    render(<StaffNotices token="t" condominiumId="c1" />);
    expect(await screen.findByText("Chamada devolvida à fila")).toBeTruthy();
    expect(screen.getByText("Avisos da equipe (1)")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Marcar como lido: Chamada devolvida à fila" }));
    await waitFor(() => expect(screen.queryByText("Chamada devolvida à fila")).toBeNull());
    expect(calls).toContain("POST /api/v1/condominiums/c1/staff/notifications/n1/read");
  });

  it("não ocupa espaço quando não há aviso novo", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ items: [], page: 0, size: 20, totalElements: 0 })));
    const { container } = render(<StaffNotices token="t" condominiumId="c1" />);
    await waitFor(() => expect(container.innerHTML).toBe(""));
  });
});
