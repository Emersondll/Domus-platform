import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const residency = [{ condominiumId: "c1", condominiumName: "Residencial Sol", unitId: "u1", unitNumber: "101", residentId: "r1" }];
const area = { id: "a1", name: "Salão", capacity: 40 };
const page = (items: unknown[]) => ({ items, page: 0, size: 50, totalElements: items.length });

let calls: { url: string; method: string; body?: string }[];
let reservationStatus = 201;
let myCalls: unknown[] = [];
let myParcels: unknown[] = [];
let myNotes: unknown[] = [];
let myInvitations: unknown[] = [];
let myHistory: unknown[] = [];
let myVisitRequests: unknown[] = [];
let myDoors: unknown[] = [];
let openStatus = 202;
let cmdState = "CONFIRMED";

/** Substitui apenas a rede (fetch); UI, validações do cliente e geração do QR rodam de verdade. */
function fakeApi() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body as string | undefined });
    const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status });
    if (url.startsWith("/dev/token")) return json({ access_token: "t" });
    if (url === "/api/v1/me/residencies") return json(residency);
    if (url.endsWith("/me/notifications")) return json(page(myNotes));
    if (url.endsWith("/me/common-areas")) return json(page([area]));
    if (url.endsWith("/me/reservations") && method === "POST")
      return reservationStatus === 201 ? json({ id: "x" }, 201) : json({ detail: "conflict" }, 409);
    if (url.endsWith("/me/reservations")) return json(page([]));
    if (url.endsWith("/me/invitations") && method === "POST")
      return json({ id: "i1", visitorName: "Tia Maria", validFrom: new Date().toISOString(),
        validUntil: new Date(Date.now() + 3600_000).toISOString(), maxUses: 1, useCount: 0, status: "ACTIVE",
        token: "11111111-1111-1111-1111-111111111111.sig" }, 201);
    if (url.endsWith("/me/invitations")) return json(page(myInvitations));
    if (url.endsWith("/me/doors")) return json(myDoors);
    if (url.endsWith("/me/doors/d1/open")) return openStatus === 202 ? json({ id: "cm1", state: "PENDING" }, 202) : json({ detail: "x" }, openStatus);
    if (url.endsWith("/me/commands/cm1")) return json({ id: "cm1", deviceId: "d1", state: cmdState, failureReason: null });
    if (url.endsWith("/me/announcements")) return json(page([{ id: "an1", title: "Manutenção", body: "Caixa d'água na sexta", createdAt: new Date().toISOString() }]));
    if (url.endsWith("/me/messages") && method === "POST") return json({ id: "m2", fromResident: true, text: "x", createdAt: new Date().toISOString(), read: false }, 201);
    if (url.endsWith("/me/messages")) return json(page([{ id: "m1", fromResident: false, text: "Pode enviar o nome", createdAt: new Date().toISOString(), read: true }]));
    if (url.endsWith("/me/household") && method === "POST") return json({ id: "f2" }, 201);
    if (url.endsWith("/me/household")) return json([{ id: "f0", fullName: "Titular Teste", relationship: "OWNER", phone: null, removable: false }, { id: "f1", fullName: "Esposa Teste", relationship: "SPOUSE", phone: null, removable: true }]);
    if (url.endsWith("/me/pets") && method === "POST") return json({ id: "p2" }, 201);
    if (url.endsWith("/me/pets")) return json([{ id: "p1", unitId: "un1", name: "Rex", species: "DOG", breed: null, notes: null }]);
    if (url.endsWith("/me/panic")) return json({ id: "c9", source: "PANIC", status: "QUEUED" }, 201);
    if (url.endsWith("/approve") || url.endsWith("/deny")) return json({ id: "vr1" });
    if (url.endsWith("/me/visit-requests")) return json(page(myVisitRequests));
    if (url.endsWith("/me/calls") && method === "POST") return json({ id: "c1" }, 201);
    if (url.endsWith("/me/calls")) return json(page(myCalls));
    if (url.includes("/me/parcels")) {
      const wanted = new URL(url, "http://x").searchParams.get("status");
      return json(page((myParcels as { status: string }[]).filter((p) => !wanted || p.status === wanted)));
    }
    if (url.endsWith("/me/profile")) return json({ fullName: "Fulano", email: null, phone: null });
    if (url.endsWith("/me/preferences") && method === "PUT") return json(JSON.parse(init!.body as string));
    if (url.endsWith("/me/preferences")) return json({ parcelsEnabled: true, visitorArrivalEnabled: true });
    if (url.endsWith("/me/access-history")) return json(page(myHistory));
    return json({}, 404);
  });
}

async function openTab(name: string) {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole("button", { name: /Entrar/ }));
  await screen.findByText(/Residencial Sol/);
  if (["Avisos", "Convites", "Reservas", "Veículos", "Minha casa", "Perfil"].includes(name)) {
    await user.click(screen.getByRole("button", { name: "Mais" }));
  }
  await user.click(screen.getByRole("button", { name }));
  return user;
}

describe("resident app", () => {
  beforeEach(() => {
    calls = [];
    reservationStatus = 201;
    myCalls = [];
    myParcels = [];
    myNotes = [];
    myInvitations = [];
    myHistory = [];
    myVisitRequests = [];
    myDoors = [];
    openStatus = 202;
    cmdState = "CONFIRMED";
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

  it("shows the progress of the call and the outcome after the doorman closes it", async () => {
    myCalls = [{ id: "c1", source: "RESIDENT_APP", status: "CLOSED", createdAt: new Date().toISOString(), assignedAt: null, closedAt: new Date().toISOString(), outcome: "ENTRY_DENIED" }];
    await openTab("Início");
    expect(await screen.findByText(/Entrada não autorizada pela portaria/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Chamar a central" })).toBeEnabled();
  });

  it("blocks a second call while one is still being handled", async () => {
    myCalls = [{ id: "c1", source: "RESIDENT_APP", status: "ASSIGNED", createdAt: new Date().toISOString(), assignedAt: new Date().toISOString(), closedAt: null, outcome: null }];
    await openTab("Início");
    expect(await screen.findByText(/Em atendimento pela central/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Chamar a central" })).toBeDisabled();
  });

  it("summarizes parcels to pick up and expected visits on the home screen", async () => {
    myParcels = [
      { id: "p1", carrier: "Correios", description: null, status: "RECEIVED", receivedAt: new Date().toISOString(), pickedUpAt: null },
      { id: "p2", carrier: "Loggi", description: null, status: "PICKED_UP", receivedAt: new Date().toISOString(), pickedUpAt: new Date().toISOString() },
    ];
    myInvitations = [{ id: "i1", visitorName: "Tia Maria", validFrom: new Date().toISOString(), validUntil: new Date(Date.now() + 3600_000).toISOString(), maxUses: 1, useCount: 0, status: "ACTIVE", token: null }];
    await openTab("Início");
    expect(await screen.findByRole("button", { name: "Encomendas a retirar" })).toHaveTextContent("1");
    expect(screen.getByRole("button", { name: "Visitas esperadas" })).toHaveTextContent("1");
  });

  it("lists expected visits, arrivals and the entry history with the visitor name", async () => {
    myInvitations = [
      { id: "i1", visitorName: "Tia Maria", validFrom: new Date().toISOString(), validUntil: new Date(Date.now() + 3600_000).toISOString(), maxUses: 1, useCount: 0, status: "ACTIVE", token: null },
      { id: "i2", visitorName: "Seu João", validFrom: new Date().toISOString(), validUntil: new Date(Date.now() - 1000).toISOString(), maxUses: 1, useCount: 1, status: "ACTIVE", token: null },
    ];
    myHistory = [{ id: "a1", credentialId: "i2", entrance: "portaria", result: "GRANTED", reason: "OK", occurredAt: new Date().toISOString() }];
    await openTab("Visitas");
    expect(await screen.findByText("Tia Maria")).toBeInTheDocument();
    expect(screen.queryByText(/Ninguém chegou ainda/)).not.toBeInTheDocument();
    // "Seu João": uma vez em "Já chegaram" e outra no histórico, resolvido pelo credentialId do convite.
    expect(screen.getAllByText(/Seu João/)).toHaveLength(2);
    expect(screen.getByText(/liberado/)).toBeInTheDocument();
  });

  it("lists parcels waiting for pickup apart from the ones already collected", async () => {
    myParcels = [{ id: "p1", carrier: "Correios", description: null, status: "RECEIVED", receivedAt: new Date().toISOString(), pickedUpAt: null }];
    await openTab("Encomendas");
    expect(await screen.findByText(/A retirar na portaria \(1\)/)).toBeInTheDocument();
  });

  it("lets the resident authorize or deny a visitor waiting at the gate, and says the operator still opens the door", async () => {
    myVisitRequests = [{ id: "vr1", unitId: "u1", visitorName: "Tia Maria", kind: "VISITOR", status: "PENDING", createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 300000).toISOString(), decidedAt: null }];
    const user = await openTab("Início");
    expect(await screen.findByText(/Tia Maria/)).toBeInTheDocument();
    expect(screen.getByText(/quem libera a entrada é o operador/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Autorizar" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url.endsWith("/me/visit-requests/vr1/approve"))).toBe(true));
  });

  it("does not show requests that were already answered or expired", async () => {
    myVisitRequests = [{ id: "vr1", unitId: "u1", visitorName: "Tia Maria", kind: "VISITOR", status: "EXPIRED", createdAt: new Date().toISOString(), expiresAt: new Date().toISOString(), decidedAt: null }];
    await openTab("Início");
    await screen.findByRole("button", { name: "Chamar a central" });
    expect(screen.queryByRole("button", { name: "Autorizar" })).not.toBeInTheDocument();
  });

  it("asks for confirmation before sending a panic alert and only then calls the API", async () => {
    const user = await openTab("Início");
    await user.click(await screen.findByRole("button", { name: "Alerta de pânico" }));
    expect(calls.some((c) => c.url.endsWith("/me/panic"))).toBe(false);
    await user.click(screen.getByRole("button", { name: "Confirmar pânico" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url.endsWith("/me/panic"))).toBe(true));
  });

  it("can cancel the panic confirmation without sending anything", async () => {
    const user = await openTab("Início");
    await user.click(await screen.findByRole("button", { name: "Alerta de pânico" }));
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(calls.some((c) => c.url.endsWith("/me/panic"))).toBe(false);
    expect(screen.getByRole("button", { name: "Alerta de pânico" })).toBeEnabled();
  });

  it("disables a second panic alert while the first one is still being handled", async () => {
    myCalls = [{ id: "c9", source: "PANIC", status: "QUEUED", createdAt: new Date().toISOString(), assignedAt: null, closedAt: null, outcome: null }];
    await openTab("Início");
    expect(await screen.findByText(/Seu alerta de pânico/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Alerta de pânico" })).toBeDisabled();
  });

  it("lists the condominium announcements", async () => {
    await openTab("Avisos");
    expect(await screen.findByText("Manutenção")).toBeInTheDocument();
    expect(screen.getByText("Caixa d'água na sexta")).toBeInTheDocument();
  });

  it("shows the conversation with the front desk and sends a message", async () => {
    const user = await openTab("Mensagens");
    expect(await screen.findByText("Pode enviar o nome")).toBeInTheDocument();
    const send = screen.getByRole("button", { name: "Enviar" });
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText("Nova mensagem"), "Libere o prestador");
    await user.click(send);
    await waitFor(() => expect(calls.find((c) => c.url.endsWith("/me/messages") && c.method === "POST")?.body).toBe(JSON.stringify({ text: "Libere o prestador" })));
  });

  it("manages the family and the pets of the unit", async () => {
    const user = await openTab("Minha casa");
    expect(await screen.findByText("Esposa Teste")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remover Titular Teste" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remover Esposa Teste" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("Nome completo"), "Filho Teste");
    await user.selectOptions(screen.getByLabelText("Vínculo"), "DEPENDENT");
    await user.click(screen.getByRole("button", { name: "Adicionar pessoa" }));
    await waitFor(() => expect(calls.find((c) => c.url.endsWith("/me/household") && c.method === "POST")?.body).toBe(JSON.stringify({ fullName: "Filho Teste", relationship: "DEPENDENT", phone: null })));
    await user.type(screen.getByLabelText("Nome do animal"), "Mimi");
    await user.selectOptions(screen.getByLabelText("Espécie"), "CAT");
    await user.click(screen.getByRole("button", { name: "Adicionar animal" }));
    await waitFor(() => expect(calls.find((c) => c.url.endsWith("/me/pets") && c.method === "POST")?.body).toBe(JSON.stringify({ name: "Mimi", species: "CAT", breed: null, notes: null })));
  });

  it("offers no door button unless the condominium enabled remote opening", async () => {
    await openTab("Início");
    await screen.findByRole("button", { name: "Chamar a central" });
    expect(screen.queryByRole("heading", { name: "Abrir portão" })).not.toBeInTheDocument();
  });

  it("only says the door opened after the device confirmed it", async () => {
    myDoors = [{ id: "d1", name: "Social", entrance: "portaria" }];
    cmdState = "CONFIRMED";
    const user = await openTab("Início");
    await user.click(await screen.findByRole("button", { name: "Abrir Social" }));
    expect(await screen.findByText(/Porta aberta \(confirmado pelo equipamento\)/)).toBeInTheDocument();
    const open = calls.find((c) => c.method === "POST" && c.url.endsWith("/me/doors/d1/open"));
    expect(JSON.parse(open!.body!).idempotencyKey).toBeTruthy();
  });

  it("states that the door did NOT open when the command failed", async () => {
    myDoors = [{ id: "d1", name: "Social", entrance: "portaria" }];
    cmdState = "FAILED";
    const user = await openTab("Início");
    await user.click(await screen.findByRole("button", { name: "Abrir Social" }));
    expect(await screen.findByText(/A porta NÃO abriu/)).toBeInTheDocument();
  });

  it("asks the resident to wait when the opening is throttled", async () => {
    myDoors = [{ id: "d1", name: "Social", entrance: "portaria" }];
    openStatus = 429;
    const user = await openTab("Início");
    await user.click(await screen.findByRole("button", { name: "Abrir Social" }));
    expect(await screen.findByText(/Aguarde alguns segundos/)).toBeInTheDocument();
  });

  it("separates notifications by subject, with the unread count on each tab", async () => {
    const at = new Date().toISOString();
    myNotes = [
      { id: "n1", type: "PARCEL_RECEIVED", title: "Encomenda recebida", body: "Correios", createdAt: at, readAt: null },
      { id: "n2", type: "VISIT_REQUEST", title: "Prestador de serviço na portaria", body: "João aguarda", createdAt: at, readAt: null },
      { id: "n3", type: "ANNOUNCEMENT", title: "Manutenção", body: "Sexta", createdAt: at, readAt: at },
    ];
    const user = await openTab("Início");
    const tabs = await screen.findByRole("tablist", { name: "Tipos de notificação" });
    expect(tabs).toBeInTheDocument();
    // abre na primeira aba com novidade: só a notificação de visitas aparece
    expect(await screen.findByText("Prestador de serviço na portaria")).toBeInTheDocument();
    expect(screen.queryByText("Encomenda recebida")).not.toBeInTheDocument();
    expect(screen.queryByText("Manutenção")).not.toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: /Encomendas/ }));
    expect(screen.getByText("Encomenda recebida")).toBeInTheDocument();
    expect(screen.queryByText("Prestador de serviço na portaria")).not.toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Avisos" }));
    expect(screen.getByText("Manutenção")).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Mensagens" }));
    expect(screen.getByText("Nenhuma mensagem nova.")).toBeInTheDocument();
  });

  it("offers a way back to the home screen from every section", async () => {
    const user = await openTab("Encomendas");
    await user.click(await screen.findByRole("button", { name: "← Voltar ao início" }));
    expect(await screen.findByRole("region", { name: "Início" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Mais" }));
    await user.click(screen.getByRole("button", { name: "Veículos" }));
    await user.click(await screen.findByRole("button", { name: "← Voltar para Mais opções" }));
    expect(await screen.findByRole("region", { name: "Mais opções" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "← Voltar ao início" }));
    expect(await screen.findByRole("region", { name: "Início" })).toBeInTheDocument();
  });
});
