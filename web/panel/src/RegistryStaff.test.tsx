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
    if (url.endsWith("/members/provision")) return json({ membership: { id: "m1" }, temporaryPassword: "senha-ficticia-de-teste" }, 201);
    if (url.endsWith("/members/m7/reset-password")) return json({ temporaryPassword: "nova-senha-ficticia" });
    if (url.endsWith("/members")) return json([{ id: "m7", userId: "u7777777-aaaa", displayName: "Bia Operadora", role: "OPERATOR" }]);
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
    expect(await screen.findByText("senha-ficticia-de-teste")).toBeTruthy();
    const post = calls.find((c) => c.url.endsWith("/members/provision"))!;
    expect(JSON.parse(post.body!)).toEqual({ role: "OPERATOR", account: { email: "ana@example.invalid", displayName: "Ana Souza" } });
    fireEvent.click(screen.getByText("Já entreguei, fechar"));
    expect(screen.queryByText("senha-ficticia-de-teste")).toBeNull();
  });

  it("com a criação desligada continua pedindo o identificador (fluxo manual)", async () => {
    stubApi(false);
    render(<RegistryStaff token="t" condominiumId="c1" />);
    expect(await screen.findByLabelText("Identificador do usuário")).toBeTruthy();
    expect(screen.queryByText("Criar conta e adicionar")).toBeNull();
  });

  it("gera uma nova senha provisória só depois da confirmação e a mostra uma vez", async () => {
    const calls = stubApi(true);
    render(<RegistryStaff token="t" condominiumId="c1" />);
    fireEvent.click(screen.getByText("Carregar equipe"));
    fireEvent.click(await screen.findByLabelText("Gerar nova senha para Bia Operadora"));
    expect(calls.some((c) => c.url.endsWith("/reset-password"))).toBe(false); // ainda só perguntou
    fireEvent.click(screen.getByText("Confirmar nova senha"));
    expect(await screen.findByText("nova-senha-ficticia")).toBeTruthy();
    expect(screen.getByText(/Nova senha provisória gerada para/)).toBeTruthy();
    expect(calls.filter((c) => c.url.endsWith("/members/m7/reset-password")).length).toBe(1);
  });

  it("sem a criação de contas ligada não oferece gerar nova senha", async () => {
    stubApi(false);
    render(<RegistryStaff token="t" condominiumId="c1" />);
    fireEvent.click(screen.getByText("Carregar equipe"));
    await screen.findByText(/Bia Operadora/);
    expect(screen.queryByLabelText("Gerar nova senha para Bia Operadora")).toBeNull();
  });
});
