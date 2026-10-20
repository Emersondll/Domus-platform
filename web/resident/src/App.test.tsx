import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const residency = [{ condominiumId: "c1", condominiumName: "Residencial Sol", unitId: "u1", unitNumber: "101", residentId: "r1" }];
const area = { id: "a1", name: "Salão", capacity: 40 };
const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length });

let calls: { url: string; method: string; body?: string }[];
let reservationStatus = 201;

/** Substitui apenas a rede (fetch); UI, validações do cliente e geração do QR rodam de verdade. */
function fakeApi() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body as string | undefined });
    const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status });
    if (url.startsWith("/dev/token")) return json({ access_token: "t" });
    if (url === "/api/v1/me/residencies") return json(residency);
    if (url.endsWith("/me/notifications")) return json(page([]));
    if (url.endsWith("/me/common-areas")) return json(page([area]));
    if (url.endsWith("/me/reservations") && method === "POST")
      return reservationStatus === 201 ? json({ id: "x" }, 201) : json({ detail: "conflict" }, 409);
    if (url.endsWith("/me/reservations")) return json(page([]));
    if (url.endsWith("/me/invitations") && method === "POST")
      return json({ id: "i1", visitorName: "Tia Maria", validFrom: new Date().toISOString(),
        validUntil: new Date(Date.now() + 3600_000).toISOString(), maxUses: 1, useCount: 0, status: "ACTIVE",
        token: "11111111-1111-1111-1111-111111111111.sig" }, 201);
    if (url.endsWith("/me/invitations")) return json(page([]));
    if (url.endsWith("/me/profile")) return json({ fullName: "Fulano", email: null, phone: null });
    if (url.endsWith("/me/preferences") && method === "PUT") return json(JSON.parse(init!.body as string));
    if (url.endsWith("/me/preferences")) return json({ parcelsEnabled: true, visitorArrivalEnabled: true });
    if (url.endsWith("/me/access-history")) return json(page([]));
    return json({}, 404);
  });
}

async function openTab(name: string) {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole("button", { name: /Entrar/ }));
  await screen.findByText(/Residencial Sol/);
  await user.click(screen.getByRole("button", { name }));
  return user;
}

describe("resident app", () => {
  beforeEach(() => {
    calls = [];
    reservationStatus = 201;
    vi.stubGlobal("fetch", fakeApi());
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows the QR code once after creating an invitation and sends no personal data in it", async () => {
    const user = await openTab("Convites");
    await user.type(screen.getByLabelText("Nome do visitante"), "Tia Maria");
    await user.click(screen.getByRole("button", { name: "Gerar convite" }));
    expect(await screen.findByAltText("QR Code do convite")).toBeInTheDocument();
    const post = calls.find((c) => c.method === "POST" && c.url.endsWith("/me/invitations"))!;
    expect(JSON.parse(post.body!).maxUses).toBe(1);
  });

  it("explains a reservation conflict instead of failing silently", async () => {
    reservationStatus = 409;
    const user = await openTab("Reservas");
    await screen.findByRole("option", { name: /Salão/ });
    await user.type(screen.getByLabelText("Início"), "2030-01-01T10:00");
    await user.click(screen.getByRole("button", { name: "Reservar" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/horário está indisponível/);
  });

  it("persists a privacy preference change", async () => {
    const user = await openTab("Perfil");
    const box = await screen.findByLabelText("Avisar sobre encomendas");
    await user.click(box);
    await waitFor(() => {
      const put = calls.find((c) => c.method === "PUT" && c.url.endsWith("/me/preferences"));
      expect(JSON.parse(put!.body!)).toEqual({ parcelsEnabled: false, visitorArrivalEnabled: true });
    });
  });

  it("states honestly that push/e-mail/WhatsApp are not integrated", async () => {
    await openTab("Início");
    expect(await screen.findByText(/push, e-mail e WhatsApp ainda não estão integrados/)).toBeInTheDocument();
  });
});
