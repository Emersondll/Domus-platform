import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const hlsLoaded: string[] = [];
vi.mock("hls.js", () => {
  class FakeHls {
    static Events = { MANIFEST_PARSED: "manifest", ERROR: "error" };
    static isSupported() { return true; }
    on() {}
    loadSource(url: string) { hlsLoaded.push(url); }
    attachMedia() {}
    destroy() {}
  }
  return { default: FakeHls };
});

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
let myAccesses: unknown[] = [];
let myCameras: unknown[] = [];
let lastAccess: unknown = null;
let photoStatus: Record<string, unknown> = { enabled: false, textVersion: "v1", text: "", consentId: null, hasPhoto: false, photoCapturedAt: null };
let openStatus = 202;
let cmdState = "CONFIRMED";

/** Substitui apenas a rede (fetch); UI, validações do cliente e geração do QR rodam de verdade. */
function fakeApi() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body as string | undefined });
    const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status });
    if (url.startsWith("/dev/token")) return json({ access_token: "t" });
    if (url === "/api/v1/me/residencies") {
      return notRegistered ? json({ detail: "User is not registered or is inactive" }, 403) : json(residency);
    }
    if (url.endsWith("/me/messages/unread")) return json({ unread: unreadMessages });
    if (url === "/api/v1/me/association-requests") return json(myRequests);
    if (url === "/api/v1/association-requests" && method === "POST") return json({ id: "ar1", condominiumName: "Residencial Sol", status: "PENDING", createdAt: new Date().toISOString() }, 201);
    if (url.endsWith("/me/notifications/unread-count")) return json({ unread: unreadCount });
    if (url.endsWith("/members")) return json([{ id: "s1", role: "OPERATOR", displayName: "Maria da Portaria" }, { id: "s2", role: "CONDO_MANAGER", displayName: "Carlos Síndico" }]);
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
    if (url.endsWith("/me/pets/p1") && method === "PUT") return json({ id: "p1" });
    if (url.includes("/residents/") && method === "GET") return json({ id: "r1", unitId: "u1", fullName: "Fulano", email: null, phone: null, residentType: "OWNER", active: true });
    if (url.endsWith("/me/pets")) return json([{ id: "p1", unitId: "un1", name: "Rex", species: "DOG", breed: null, notes: null }]);
    if (url.endsWith("/me/panic")) return json({ id: "c9", source: "PANIC", status: "QUEUED" }, 201);
    if (url.endsWith("/approve") || url.endsWith("/deny")) return json({ id: "vr1" });
    if (url.endsWith("/me/visit-requests/vr1/snapshot")) return new Response(new Blob(["jpeg"]), { status: 200 });
    if (url.endsWith("/me/visit-requests")) return json(page(myVisitRequests));
    if (url.endsWith("/me/calls") && method === "POST") return json({ id: "c1" }, 201);
    if (url.endsWith("/me/calls")) return json(page(myCalls));
    if (url.includes("/me/parcels")) {
      const wanted = new URL(url, "http://x").searchParams.get("status");
      return json(page((myParcels as { status: string }[]).filter((p) => !wanted || p.status === wanted)));
    }
    if (url.endsWith("/me/cameras")) return json(myCameras);
    if (url.endsWith("/me/cameras/k1/stream-session")) return json({ hlsUrl: "http://media.test:8888/cam-k1/index.m3u8?token=9999999999.sig", expiresAt: new Date(Date.now() + 600000).toISOString() });
    if (url.endsWith("/me/accesses/policy")) return json({ retentionDays: 45 });
    if (url.endsWith("/me/accesses/last")) return json({ last: lastAccess });
    if (url.includes("/me/accesses?")) return json(page(myAccesses));
    if (url.endsWith("/me/photo/status")) return json(photoStatus);
    if (url.endsWith("/me/consents") && method === "POST") return json({ id: "co1", textVersion: "v1", grantedAt: new Date().toISOString() }, 201);
    if (url.endsWith("/me/consents/co1/revoke")) return new Response(null, { status: 204 });
    if (url.endsWith("/me/photo") && method === "PUT") return json(photoStatus);
    if (url.endsWith("/me/photo") && method === "DELETE") return new Response(null, { status: 204 });
    if (url.endsWith("/me/photo")) return new Response(new Blob(["jpeg"]), { status: 200 });
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
  if (["Avisos", "Meus acessos", "Convites", "Reservas", "Veículos", "Minha casa", "Equipe", "Perfil"].includes(name)) {
    await user.click(screen.getByRole("button", { name: "Mais" }));
  }
  await user.click(screen.getByRole("button", { name }));
  return user;
}

let unreadCount = 0;
let notRegistered = false;
let unreadMessages = 0;
let myRequests: unknown[] = [];

describe("resident app", () => {
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => "blob:entrada");
    URL.revokeObjectURL = vi.fn();
    calls = [];
    reservationStatus = 201;
    myCalls = [];
    myParcels = [];
    myNotes = [];
    unreadCount = 0;
    notRegistered = false;
    unreadMessages = 0;
    myRequests = [];
    myInvitations = [];
    myHistory = [];
    myVisitRequests = [];
    myDoors = [];
    myAccesses = [];
    lastAccess = null;
    photoStatus = { enabled: false, textVersion: "v1", text: "", consentId: null, hasPhoto: false, photoCapturedAt: null };
    openStatus = 202;
    cmdState = "CONFIRMED";
    vi.stubGlobal("fetch", fakeApi());
  });
  afterEach(() => vi.unstubAllGlobals());

  it("shows who is on the staff by name and role", async () => {
    await openTab("Equipe");
    expect(await screen.findByText("Maria da Portaria")).toBeInTheDocument();
    expect(screen.getByText(/Portaria remota/)).toBeInTheDocument();
    expect(screen.getByText("Carlos Síndico")).toBeInTheDocument();
    expect(screen.getByText(/Síndico \/ administração/)).toBeInTheDocument();
  });

  it("shows the unread notices count on the home button", async () => {
    unreadCount = 3;
    render(<App />);
    await userEvent.setup().click(await screen.findByRole("button", { name: /Entrar/ }));
    expect(await screen.findByTitle("3 avisos não lidos")).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith("/me/notifications/unread-count"))).toBe(true);
  });

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

  it("shows the visitor name and the real state of each invitation, revoking only the ones still valid", async () => {
    myInvitations = [
      { id: "i1", visitorName: "Tia Maria", validFrom: new Date().toISOString(), validUntil: new Date(Date.now() + 3600_000).toISOString(), maxUses: 1, useCount: 0, status: "ACTIVE", token: null },
      { id: "i2", visitorName: "Seu João", validFrom: new Date().toISOString(), validUntil: new Date(Date.now() + 3600_000).toISOString(), maxUses: 1, useCount: 1, status: "ACTIVE", token: null },
    ];
    await openTab("Convites");
    const used = (await screen.findByText("Seu João")).closest("li")!;
    expect(within(used).getByText(/Usado/)).toBeInTheDocument();
    expect(within(used).queryByRole("button", { name: "Revogar" })).not.toBeInTheDocument();
    const valid = screen.getByText("Tia Maria").closest("li")!;
    expect(within(valid).getByText(/Ativo/)).toBeInTheDocument();
    expect(within(valid).getByRole("button", { name: "Revogar" })).toBeInTheDocument();
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

  it("lets a person without a link ask the síndico for access with the join code", async () => {
    notRegistered = true;
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: /Entrar/ }));
    await user.type(await screen.findByLabelText("Código de adesão"), "abcd234567");
    await user.type(screen.getByLabelText("Nome completo"), "Ana Teste");
    await user.type(screen.getByLabelText("E-mail"), "ana@example.invalid");
    await user.selectOptions(screen.getByLabelText("Vínculo"), "TENANT");
    await user.type(screen.getByLabelText(/Unidade \(ex/), "Bloco A, 101");
    await user.click(screen.getByRole("button", { name: "Enviar pedido" }));
    await waitFor(() => expect(JSON.parse(calls.find((c) => c.url === "/api/v1/association-requests" && c.method === "POST")!.body!))
      .toEqual({ code: "abcd234567", fullName: "Ana Teste", email: "ana@example.invalid", phone: null, type: "TENANT", unitText: "Bloco A, 101" }));
    expect(await screen.findByText(/Pedido enviado/)).toBeInTheDocument();
  });

  it("shows the state of an earlier request and hides the form while it is pending", async () => {
    notRegistered = true;
    myRequests = [{ id: "ar1", condominiumName: "Residencial Sol", status: "PENDING", createdAt: new Date().toISOString() }];
    const user = userEvent.setup();
    render(<App />);
    await user.click(await screen.findByRole("button", { name: /Entrar/ }));
    expect(await screen.findByText(/aguardando o síndico/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Enviar pedido" })).not.toBeInTheDocument();
  });

  it("shows how many messages from the front desk are unread", async () => {
    unreadMessages = 3;
    await openTab("Início");
    expect(await screen.findByRole("button", { name: /Mensagens/ })).toHaveAttribute("title", "3 mensagens não lidas");
  });

  it("edits a pet of the unit", async () => {
    const user = await openTab("Minha casa");
    await user.click(await screen.findByRole("button", { name: "Editar Rex" }));
    expect(screen.getByLabelText("Nome do animal")).toHaveValue("Rex");
    await user.clear(screen.getByLabelText("Nome do animal"));
    await user.type(screen.getByLabelText("Nome do animal"), "Rex II");
    await user.click(screen.getByRole("button", { name: "Salvar alterações do animal" }));
    await waitFor(() => expect(calls.find((c) => c.url.endsWith("/me/pets/p1") && c.method === "PUT")?.body)
      .toBe(JSON.stringify({ name: "Rex II", species: "DOG", breed: null, notes: null })));
  });

  it("shows the own registration on the profile", async () => {
    await openTab("Perfil");
    expect(await screen.findByLabelText("Meu cadastro")).toHaveTextContent("proprietário · ativo");
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
    expect(await screen.findByText(/Aguarde um minuto/)).toBeInTheDocument();
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
    await user.click(await screen.findByRole("button", { name: "← Voltar ao início" }));
    expect(await screen.findByRole("region", { name: "Início" })).toBeInTheDocument();
  });

  it("opens the options menu from the header icon button", async () => {
    const user = await openTab("Início");
    await user.click(screen.getByRole("button", { name: "Abrir menu de opções" }));
    const menu = await screen.findByRole("region", { name: "Mais opções" });
    for (const t of ["Avisos", "Meus acessos", "Câmeras", "Convites", "Reservas", "Veículos", "Minha casa", "Equipe", "Perfil"]) {
      expect(within(menu).getByRole("button", { name: t })).toBeInTheDocument();
    }
    await user.click(within(menu).getByRole("button", { name: "Reservas" }));
    expect(await screen.findByRole("region", { name: "Reservas" })).toBeInTheDocument();
  });

  it("keeps the home shortcut always visible in the header", async () => {
    const user = await openTab("Início");
    expect(screen.getByRole("button", { name: "Ir para o início" })).toHaveAttribute("aria-current", "true");
    await user.click(screen.getByRole("button", { name: "Mais" }));
    await user.click(screen.getByRole("button", { name: "Veículos" }));
    const home = screen.getByRole("button", { name: "Ir para o início" });
    expect(home).toHaveAttribute("aria-current", "false");
    await user.click(home);
    expect(await screen.findByRole("region", { name: "Início" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ir para o início" })).toBeInTheDocument();
  });

  it("opens the cameras from the header icon on any screen", async () => {
    myCameras = [{ id: "k1", name: "Câmera portaria", entrance: "portaria" }];
    const user = await openTab("Início");
    await user.click(screen.getByRole("button", { name: "Ver câmeras da entrada" }));
    expect(await screen.findByRole("region", { name: "Câmeras da entrada" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ver câmeras da entrada" })).toHaveAttribute("aria-current", "true");
    await user.click(screen.getByRole("button", { name: "Ir para o início" }));
    expect(await screen.findByRole("region", { name: "Início" })).toBeInTheDocument();
  });

  it("lets the resident watch only the entrance cameras the manager released", async () => {
    myCameras = [{ id: "k1", name: "Câmera portaria", entrance: "portaria" }];
    hlsLoaded.length = 0;
    const user = await openTab("Mais");
    await user.click(screen.getByRole("button", { name: "Câmeras" }));
    expect(await screen.findByText("Câmera portaria")).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith("/stream-session"))).toBe(false); // nada abre sozinho
    expect(screen.getByRole("button", { name: "Ver Câmera portaria" })).toBeDisabled(); // antes da ciência
    await user.click(screen.getByRole("checkbox", { name: /as imagens mostram outras pessoas/ }));
    await user.click(screen.getByRole("button", { name: "Ver Câmera portaria" }));
    expect(await screen.findByRole("region", { name: "Vídeo de Câmera portaria" })).toBeInTheDocument();
    await waitFor(() => expect(hlsLoaded).toEqual(["http://media.test:8888/cam-k1/index.m3u8?token=9999999999.sig"]));
    await user.click(screen.getByRole("button", { name: "Fechar vídeo Câmera portaria" }));
    expect(screen.queryByRole("region", { name: "Vídeo de Câmera portaria" })).not.toBeInTheDocument();
  });

  it("says so when no camera was released to residents", async () => {
    myCameras = [];
    const user = await openTab("Mais");
    await user.click(screen.getByRole("button", { name: "Câmeras" }));
    expect(await screen.findByText(/Nenhuma câmera foi liberada/)).toBeInTheDocument();
  });

  it("shows the entrance image with the visitor request, or says there is none", async () => {
    const at = new Date().toISOString();
    myVisitRequests = [
      { id: "vr1", unitId: "u1", visitorName: "Moço da entrega", kind: "VISITOR", status: "PENDING", createdAt: at, expiresAt: new Date(Date.now() + 300000).toISOString(), decidedAt: null, hasSnapshot: true },
      { id: "vr2", unitId: "u1", visitorName: "Sem foto", kind: "VISITOR", status: "PENDING", createdAt: at, expiresAt: new Date(Date.now() + 300000).toISOString(), decidedAt: null, hasSnapshot: false },
    ];
    await openTab("Início");
    expect(await screen.findByRole("img", { name: "Imagem da entrada: Moço da entrega" })).toBeInTheDocument();
    expect(screen.getByText("Sem imagem da entrada.")).toBeInTheDocument();
    expect(calls.some((c) => c.url.endsWith("/me/visit-requests/vr1/snapshot"))).toBe(true);
    expect(calls.some((c) => c.url.endsWith("/me/visit-requests/vr2/snapshot"))).toBe(false);
  });

  it("lists only my accesses by day, says 'passagem' when the direction is unknown and never polls", async () => {
    const at = (h: number) => new Date(2030, 0, 2, h, 0).toISOString();
    myAccesses = [
      { id: "a1", occurredAt: at(18), direction: "IN", entrance: "Portaria", method: "CARD", result: "GRANTED" },
      { id: "a2", occurredAt: at(8), direction: "OUT", entrance: "Garagem", method: "TERMINAL_USER", result: "GRANTED" },
      { id: "a3", occurredAt: at(7), direction: "UNKNOWN", entrance: "Portaria", method: "CARD", result: "DENIED" },
    ];
    const user = await openTab("Meus acessos");
    expect(await screen.findByText(/Só você vê esta lista/)).toBeInTheDocument();
    expect(await screen.findByText(/Entrada · Portaria · cartão/)).toBeInTheDocument();
    expect(screen.getByText(/Saída · Garagem · terminal/)).toBeInTheDocument();
    expect(screen.getByText(/Passagem · Portaria · cartão/)).toBeInTheDocument();
    expect(await screen.findByText(/apagados depois de 45 dias/)).toBeInTheDocument();
    expect(screen.getByText("acesso negado")).toBeInTheDocument();
    const queries = () => calls.filter((c) => c.url.includes("/me/accesses?"));
    expect(queries()).toHaveLength(1);
    expect(new URL(queries()[0].url, "http://x").searchParams.get("from")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "30 dias" }));
    await waitFor(() => expect(queries()).toHaveLength(2));
  });

  it("explains an empty history honestly", async () => {
    await openTab("Meus acessos");
    expect(await screen.findByText(/Nenhum acesso registrado neste período/)).toBeInTheDocument();
  });

  it("shows the last access on the home screen, or that there is none", async () => {
    await openTab("Início");
    expect(await screen.findByText("Ainda sem registros")).toBeInTheDocument();
  });

  it("shows the last access with its direction", async () => {
    lastAccess = { id: "a1", occurredAt: new Date().toISOString(), direction: "IN", entrance: "Portaria", method: "CARD", result: "GRANTED" };
    await openTab("Início");
    expect(await screen.findByText(/Entrada · .* · Portaria/)).toBeInTheDocument();
  });

  it("hides the registration photo section when the feature is off", async () => {
    await openTab("Meus acessos");
    await screen.findByText(/Nenhum acesso registrado/);
    expect(screen.queryByRole("region", { name: "Sua foto de cadastro" })).not.toBeInTheDocument();
  });

  it("asks for a specific consent before anything can be sent, with the transparency text", async () => {
    photoStatus = { enabled: true, textVersion: "v1", text: "Autorizo o uso da minha foto somente para o controle de acesso.", consentId: null, hasPhoto: false, photoCapturedAt: null };
    const user = await openTab("Meus acessos");
    expect(await screen.findByText(/somente para o controle de acesso/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/Enviar foto/)).not.toBeInTheDocument();
    const button = screen.getByRole("button", { name: "Autorizar" });
    expect(button).toBeDisabled();
    await user.click(screen.getByRole("checkbox"));
    await user.click(button);
    await waitFor(() => {
      const post = calls.find((c) => c.method === "POST" && c.url.endsWith("/me/consents"));
      expect(JSON.parse(post!.body!)).toEqual({ textVersion: "v1" });
    });
  });

  it("shows the photo, lets the resident remove it and revoke the consent", async () => {
    photoStatus = { enabled: true, textVersion: "v1", text: "t", consentId: "co1", hasPhoto: true, photoCapturedAt: new Date().toISOString() };
    const user = await openTab("Meus acessos");
    expect(await screen.findByRole("img", { name: "Sua foto de cadastro" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remover minha foto" }));
    await waitFor(() => expect(calls.some((c) => c.method === "DELETE" && c.url.endsWith("/me/photo"))).toBe(true));
    await user.click(screen.getByRole("button", { name: "Revogar consentimento" }));
    await waitFor(() => expect(calls.some((c) => c.method === "POST" && c.url.endsWith("/me/consents/co1/revoke"))).toBe(true));
  });
});
