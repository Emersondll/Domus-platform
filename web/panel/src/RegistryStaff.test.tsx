import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RegistryStaff } from "./RegistryStaff";

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function stubApi(enabled: boolean) {
  const calls: { method: string; url: string; body?: string }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ method: init?.method ?? "GET", url, body: init?.body as string | undefined });
    if (url.endsWith("/identity/provisioning")) return json({ enabled });
    if (url.endsWith("/members/provision")) return json({ membership: { id: "m1" }, temporaryPassword: "Tmp-Pass-1234567890" }, 201);
    if (url.endsWith("/members")) return json([]);
    return json({});
  }));
  return calls;
}

describe("equipe: criar a conta pela tela", () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("com a criação ligada não pede o identificador e mostra a senha provisória uma vez", async () => {
    const calls = stubApi(true);
    render(<RegistryStaff token="t" condominiumId="c1" />);
    await waitFor(() => expect(screen.queryByLabelText("Identificador do usuário")).toBeNull());
    fireEvent.change(screen.getByLabelText("E-mail"), { target: { value: "ana@example.invalid" } });
    fireEvent.change(screen.getByLabelText("Nome"), { target: { value: "Ana Souza" } });
    fireEvent.click(screen.getByText("Criar conta e adicionar"));
    expect(await screen.findByText("Tmp-Pass-1234567890")).toBeTruthy();
    const post = calls.find((c) => c.url.endsWith("/members/provision"))!;
    expect(JSON.parse(post.body!)).toEqual({ role: "OPERATOR", account: { email: "ana@example.invalid", displayName: "Ana Souza" } });
    fireEvent.click(screen.getByText("Já entreguei, fechar"));
    expect(screen.queryByText("Tmp-Pass-1234567890")).toBeNull();
  });

  it("com a criação desligada continua pedindo o identificador (fluxo manual)", async () => {
    stubApi(false);
    render(<RegistryStaff token="t" condominiumId="c1" />);
    expect(await screen.findByLabelText("Identificador do usuário")).toBeTruthy();
    expect(screen.queryByText("Criar conta e adicionar")).toBeNull();
  });
});
