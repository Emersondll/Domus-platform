import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const hlsInstances: { loaded: string[]; destroyed: boolean }[] = [];
vi.mock("hls.js", () => {
  class FakeHls {
    static Events = { MANIFEST_PARSED: "manifest", ERROR: "error" };
    static isSupported() { return true; }
    state = { loaded: [] as string[], destroyed: false };
    constructor() { hlsInstances.push(this.state); }
    on() {}
    loadSource(url: string) { this.state.loaded.push(url); }
    attachMedia() {}
    destroy() { this.state.destroyed = true; }
  }
  return { default: FakeHls };
});

const overview = [
  { condominiumId: "c1", name: "Residencial Sol", queuedCalls: 2, assignedCalls: 1, openIncidents: 0, queuedPanics: 1,
    deviceHealth: "NOT_IMPLEMENTED", connectivity: "NOT_IMPLEMENTED" },
];
let commandState = "SENT";
let parcelList: unknown[] = [];
let posted: { url: string; body: unknown }[] = [];
let visitStatus = "PENDING";
let remoteEnabled = false;
let videoDown = false;
let panics: unknown[] = [];
let claimed: Record<string, unknown> | null = null;
const sirenStarts: number[] = [];
const call = { id: "k1", condominiumId: "c1", unitId: null, source: "MANUAL", callerName: "Entregador",
  status: "ASSIGNED", assignedUserId: "u1", createdAt: new Date().toISOString(), outcome: null };

/** Substitui apenas a rede (fetch): a UI e a lógica do cliente rodam de verdade. */
function fakeApi() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
    const pg = (items: unknown[]) => json({ items, page: 0, size: 50, totalElements: items.length });
    if (init?.method === "POST" || init?.method === "PUT") posted.push({ url, body: init.body ? JSON.parse(init.body as string) : null });
    if (url.includes("/access/on-site?")) return pg([{ attemptId: "e1", occurredAt: new Date().toISOString(), entrance: "portaria", result: "GRANTED", reason: "OK", unitId: "un1", unitLabel: "A / 101", visitorName: "Dona Rosa", exitedAt: null }]);
    if (url.includes("/visit-requests?size=15")) return pg([{ id: "vr7", unitId: "un1", callId: null, visitorName: "Tia Balcão", kind: "VISITOR", status: "PENDING", createdAt: new Date().toISOString(), expiresAt: new Date().toISOString(), decidedAt: null },
      { id: "vr10", unitId: "un1", callId: null, visitorName: "João Autorizado", kind: "SERVICE_PROVIDER", status: "APPROVED", createdAt: new Date().toISOString(), expiresAt: new Date().toISOString(), decidedAt: new Date().toISOString(), entered: false, hasSnapshot: true },
      { id: "vr11", unitId: "un1", callId: null, visitorName: "Maria Já Entrou", kind: "VISITOR", status: "APPROVED", createdAt: new Date().toISOString(), expiresAt: new Date().toISOString(), decidedAt: new Date().toISOString(), entered: true }]);
    if (url.includes("/visit-requests/") && url.endsWith("/entry")) return json({ attemptId: "e50" });
    if (url.endsWith("/visit-requests") && init?.method === "POST") return json({ id: "vr8" });
    if (url.endsWith("/access/validate")) return json({ granted: true, reason: "OK", unitId: "un1", visitorName: "Dona Rosa", attemptId: "e9" });
    if (url.endsWith("/exit") && init?.method === "POST") return json({ attemptId: "e1" });
    if (url.includes("/access/report?")) return pg([{ attemptId: "e2", occurredAt: new Date().toISOString(), entrance: "garagem", result: "GRANTED", reason: "OK", unitId: "un1", unitLabel: "A / 101", visitorName: "Seu Jorge", exitedAt: null }]);
    if (url.endsWith("/remote-open") && init?.method === "PUT") { remoteEnabled = JSON.parse(init.body as string).enabled; return json({ enabled: remoteEnabled }); }
    if (url.endsWith("/remote-open")) return json({ enabled: remoteEnabled });
    if (url.endsWith("/resident-openable")) return json({ id: "d1", residentOpenable: true });
    if (url.endsWith("/entrance-snapshots/availability")) return json({ enabled: true, entrances: ["portaria"] });
    if (url.endsWith("/entrance-snapshots") && init?.method === "POST") return json({ id: "s1", expiresAt: new Date().toISOString(), cameraName: "Câmera hall" });
    if (url.includes("/entrance-snapshots/") && url.endsWith("/image")) return new Response(new Blob(["jpeg"]), { status: 200 });
    if (url.endsWith("/visit-requests/vr10/snapshot")) return new Response(new Blob(["jpeg"]), { status: 200 });
    if (/\/devices\/d\d$/.test(url) && init?.method === "PUT") return json({ id: "d2" });
    if (url.endsWith("/active") || url.endsWith("/visitor-entrance") || url.endsWith("/resident-viewable")) return json({ id: "d2" });
    if (url.endsWith("/devices/d3/stream-session")) return json({ hlsUrl: "http://media.test:8888/cam-d3/index.m3u8?token=9999999999.sig", expiresAt: new Date(Date.now() + 600000).toISOString() });
    if (url.endsWith("/devices/d2/stream-session") && videoDown) return new Response(JSON.stringify({ detail: "Media server unavailable" }), { status: 409 });
    if (url.endsWith("/devices/d2/stream-session")) return json({ hlsUrl: "http://media.test:8888/cam-d2/index.m3u8?token=9999999999.sig", expiresAt: new Date(Date.now() + 600000).toISOString() });
    if (url.endsWith("/messages/threads")) return json([{ residentUserId: "ru1", residentName: "Maria Teste", lastAt: new Date().toISOString(), unread: 2 }]);
    if (url.endsWith("/messages/threads/ru1") && init?.method === "POST") return json({ id: "m9" });
    if (url.endsWith("/messages/threads/ru1")) return pg([{ id: "m1", fromResident: true, text: "Preciso de ajuda", createdAt: new Date().toISOString(), read: false }]);
    if (url.includes("/announcements?")) return pg([{ id: "an1", title: "Manutenção", body: "Caixa d'água na sexta", createdAt: new Date().toISOString() }]);
    if (url.endsWith("/announcements") && init?.method === "POST") return json({ id: "an2" });
    if (url.includes("/shift-log?")) return pg([{ id: "sl1", shiftId: null, kind: "HANDOVER", text: "Portão social instável", createdAt: new Date().toISOString() }]);
    if (url.endsWith("/shift-log") && init?.method === "POST") return json({ id: "sl2" });
    if (url.includes("/visit-requests?callId=")) return pg([{ id: "vr1", unitId: "un1", callId: "k1", visitorName: "Tia Maria", kind: "VISITOR", status: visitStatus, createdAt: new Date().toISOString(), expiresAt: new Date().toISOString(), decidedAt: null }]);
    if (url.endsWith("/visit-requests") && init?.method === "POST") return json({ id: "vr2" });
    if (url.includes("/blocks?")) return pg([{ id: "b1", name: "Bloco A" }]);
    if (url.endsWith("/residents/r1/access-identities") && init?.method === "POST") return json({ id: "ai1", kind: "CARD", active: true, createdAt: new Date().toISOString(), revokedAt: null }, 201);
    if (url.endsWith("/residents/r1/access-identities")) return json([{ id: "ai0", kind: "TERMINAL_USER", active: true, createdAt: new Date().toISOString(), revokedAt: null }]);
    if (url.endsWith("/residents/r1/access-identities/ai0/revoke")) return json({ id: "ai0", kind: "TERMINAL_USER", active: false, createdAt: new Date().toISOString(), revokedAt: new Date().toISOString() });
    if (url.endsWith("/residents/r1/photo-status")) return json({ hasPhoto: true });
    if (url.includes("/residents?unitId=")) return pg([{ id: "r1", unitId: "un1", fullName: "Joana Teste", email: null, phone: "+55 11 90000-0000", residentType: "OWNER", active: true }]);
    if (url.endsWith("/residents") && init?.method === "POST") return json({ id: "r2" });
    if (url.endsWith("/residents/r1") && init?.method === "PUT") return json({ id: "r1" });
    if (url.includes("/pets?")) return pg([{ id: "p1", unitId: "un1", name: "Rex", species: "DOG", breed: null, notes: "sai com guia" }]);
    if (url.endsWith("/pets") && init?.method === "POST") return json({ id: "p2" });
    if (url.includes("/units?")) return pg([{ id: "un1", blockId: "b1", number: "101" }]);
    if (url.endsWith("/parcels") && init?.method === "POST") return json({ id: "p9" });
    if (url.includes("/parcels/") && url.endsWith("/pickup")) return json({ id: "p1" });
    if (url.includes("/parcels?")) return pg(parcelList);
    if (url.includes("/visitors?")) return pg([{ id: "v1", unitId: "un1", fullName: "Tia Maria", kind: "VISITOR" }]);
    if (url.includes("/access/attempts?")) return pg([{ id: "a1", credentialId: null, entrance: "portaria", result: "DENIED", reason: "EXPIRED", occurredAt: new Date().toISOString() }]);
    if (url.includes("/commands?")) return pg([{ id: "cmd0", deviceId: "d1", state: "SENT", failureReason: null, requestedAt: new Date().toISOString() }]);
    if (url.includes("/devices?")) return pg([
      { id: "d1", name: "Portão principal", kind: "ACCESS_CONTROLLER", adapter: "SIMULATOR", entrance: "portaria", status: "ONLINE", lastSeenAt: null },
      { id: "d2", name: "Câmera garagem", kind: "CAMERA", adapter: "ONVIF", entrance: "garagem", status: "OFFLINE", lastSeenAt: null, liveVideo: true, active: true, visitorEntrance: false, config: { rtspUrl: "rtsp://10.0.0.5/garagem" } },
      { id: "d3", name: "Câmera hall", kind: "CAMERA", adapter: "ONVIF", entrance: "portaria", status: "ONLINE", lastSeenAt: null, liveVideo: true, active: true, visitorEntrance: true, config: {} },
      { id: "d4", name: "Câmera velha", kind: "CAMERA", adapter: "ONVIF", entrance: "fundos", status: "UNKNOWN", lastSeenAt: null, liveVideo: false, active: false, visitorEntrance: false, config: {} },
    ]);
    if (url.startsWith("/dev/token")) return json({ access_token: "t", token_type: "Bearer" });
    if (url === "/api/v1/operator/panics") return json(panics);
    if (url === "/api/v1/operator/calls/next") { panics = []; claimed = { ...call, id: "k9", source: "PANIC", callerName: null, unitLabel: "A / 101" }; return json(claimed); }
    if (url.endsWith("/remind")) return json({ id: "p1" });
    if (url === "/api/v1/operator/overview") return json(overview);
    if (url === "/api/v1/operator/calls/mine") return json({ items: claimed ? [call, claimed] : [call], page: 0, size: 50, totalElements: 1 });
    if (url === "/api/v1/operator/shifts/current")
      return json({ id: "s1", startedAt: new Date().toISOString(), endedAt: null });
    if (url.endsWith("/events")) return json([{ type: "CLAIMED", detail: null, actorUserId: "u1", occurredAt: new Date().toISOString() }]);
    if (url.endsWith("/operators/on-duty")) return json([{ id: "u2", displayName: "Operador 2" }]);
    if (url.endsWith("/devices")) return json({ items: [{ id: "d1", name: "Portão principal", kind: "ACCESS_CONTROLLER", entrance: "portaria", status: "ONLINE" }], page: 0, size: 50, totalElements: 1 });
    if (url.endsWith("/devices/d1/open")) return json({ id: "cmd1", state: "SENT", failureReason: null, evidence: null });
    if (url.endsWith("/commands/cmd1")) return json({ id: "cmd1", state: commandState, failureReason: null, evidence: "DEVICE_EVENT:DOOR_OPENED" });
    if (url.endsWith("/incidents")) return json({ items: [], page: 0, size: 50, totalElements: 0 });
    return new Response("{}", { status: 404 });
  });
}

async function login() {
  const user = userEvent.setup();
  render(<App />);
  await user.click(await screen.findByRole("button", { name: "Entrar" }));
  return user;
}

describe("operator panel", () => {
  beforeEach(() => {
    commandState = "SENT";
    posted = [];
    visitStatus = "PENDING";
    videoDown = false;
    panics = [];
    claimed = null;
    sirenStarts.length = 0;
    remoteEnabled = false;
    parcelList = [];
    vi.stubGlobal("fetch", fakeApi());
  });
  afterEach(() => vi.unstubAllGlobals());
  beforeEach(() => {
    URL.createObjectURL = vi.fn(() => "blob:preview");
    URL.revokeObjectURL = vi.fn();
  });

  function stubAudio() {
    class FakeAudio {
      state = "running";
      currentTime = 0;
      destination = {};
      resume() { return Promise.resolve(); }
      createGain() { return { gain: { value: 0 }, connect() {} }; }
      createOscillator() { return { type: "", frequency: { value: 0 }, connect() {}, start() { sirenStarts.push(1); }, stop() {} }; }
    }
    vi.stubGlobal("AudioContext", FakeAudio);
  }

  it("shows authorized condominiums and flags unimplemented integrations", async () => {
    await login();
    expect(await screen.findByText("Residencial Sol")).toBeInTheDocument();
    expect(screen.getAllByText(/não integrado/)).toHaveLength(2);
  });

  it("only enables door opening with a device and a justification, and never claims success before confirmation", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    const open = await screen.findByRole("button", { name: "Abrir porta" });
    expect(open).toBeDisabled();
    await user.selectOptions(await screen.findByLabelText("Dispositivo"), "d1");
    await user.type(screen.getByLabelText("Justificativa da abertura"), "Morador autorizou");
    expect(open).toBeEnabled();
    await user.click(open);
    expect(await screen.findByText(/Aguardando confirmação do dispositivo/)).toBeInTheDocument();
    expect(screen.queryByText(/Porta aberta/)).not.toBeInTheDocument();
    commandState = "CONFIRMED";
    expect(await screen.findByText(/Porta aberta — confirmado/, undefined, { timeout: 3000 })).toBeInTheDocument();
  });

  it("tells the operator to check on site when the state is unknown", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    await user.selectOptions(await screen.findByLabelText("Dispositivo"), "d1");
    await user.type(screen.getByLabelText("Justificativa da abertura"), "Morador autorizou");
    commandState = "UNKNOWN";
    await user.click(screen.getByRole("button", { name: "Abrir porta" }));
    expect(await screen.findByText(/estado desconhecido/, undefined, { timeout: 3000 })).toBeInTheDocument();
  });

  it("requires a justification before closing with a sensitive outcome", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    const close = await screen.findByRole("button", { name: "Encerrar atendimento" });
    expect(close).toBeEnabled();
    await user.selectOptions(screen.getByLabelText("Desfecho"), "ENTRY_GRANTED");
    expect(close).toBeDisabled();
    await user.type(screen.getByLabelText(/Justificativa do desfecho/), "Morador confirmou");
    await waitFor(() => expect(close).toBeEnabled());
  });

  it("only allows transferring to an on-duty operator with a reason", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    const transfer = await screen.findByRole("button", { name: "Transferir" });
    expect(transfer).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Operador de plantão"), "u2");
    await user.type(screen.getByLabelText("Motivo"), "fim de turno");
    expect(transfer).toBeEnabled();
  });

  it("registers a parcel for a unit and delivers it to whoever picks it up", async () => {
    parcelList = [{ id: "p1", unitId: "un1", carrier: "Correios", description: null, status: "RECEIVED", receivedAt: new Date().toISOString(), pickedUpAt: null, pickedUpByName: null }];
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.selectOptions(await screen.findByLabelText("Unidade"), "un1");
    await user.type(screen.getByLabelText("Transportadora"), "Loggi");
    await user.click(screen.getByRole("button", { name: "Registrar encomenda" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/parcels"))?.body).toEqual({ unitId: "un1", carrier: "Loggi", description: null }));
    await user.click(await screen.findByRole("button", { name: "Entregar" }));
    const confirm = screen.getByRole("button", { name: "Confirmar entrega" });
    expect(confirm).toBeDisabled();
    await user.type(screen.getByLabelText("Quem retirou"), "Dona Ana");
    await user.click(confirm);
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/pickup"))?.body).toEqual({ pickedUpByName: "Dona Ana" }));
  });

  it("shows visitors, entry attempts and door state without claiming an open door", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    expect(await screen.findByText(/Tia Maria/)).toBeInTheDocument();
    expect(screen.getByText(/negada/)).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Portas" }));
    expect(await screen.findByText("Portão principal")).toBeInTheDocument();
    expect(screen.queryByText("Câmera garagem")).not.toBeInTheDocument();
    expect(await screen.findByText(/aceito pelo equipamento, sem confirmação/)).toBeInTheDocument();
  });

  it("lists only cameras and opens live video through a session, then releases the player", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Câmeras" }));
    expect(await screen.findByText("Câmera garagem")).toBeInTheDocument();
    expect(screen.queryByText("Portão principal")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver ao vivo Câmera garagem" }));
    expect(await screen.findByRole("region", { name: "Vídeo de Câmera garagem" })).toBeInTheDocument();
    await waitFor(() => expect(hlsInstances.at(-1)?.loaded).toEqual(["http://media.test:8888/cam-d2/index.m3u8?token=9999999999.sig"]));
    expect(posted.some((p) => p.url.endsWith("/devices/d2/stream-session"))).toBe(true);
    await user.click(screen.getByRole("button", { name: "Fechar vídeo Câmera garagem" }));
    expect(hlsInstances.at(-1)?.destroyed).toBe(true);
  });

  it("monitors the cameras of the focused condominium beside the desk and the call, only when asked", async () => {
    const user = await login();
    expect(screen.queryByRole("region", { name: "Monitoramento de câmeras" })).not.toBeInTheDocument(); // nada em foco
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    const side = await screen.findByRole("complementary", { name: "Câmeras do condomínio em monitoramento" });
    expect(within(side).queryByRole("button", { name: "Monitorar Câmera velha" })).not.toBeInTheDocument(); // desativada
    expect(posted.some((p) => p.url.endsWith("/stream-session"))).toBe(false); // nada abre sozinho
    await user.click(within(side).getByRole("button", { name: "Monitorar Câmera garagem" }));
    expect(await screen.findByRole("region", { name: "Vídeo de Câmera garagem" })).toBeInTheDocument();
    expect(posted.some((p) => p.url.endsWith("/devices/d2/stream-session"))).toBe(true);
    await user.click(screen.getByRole("button", { name: "Início" }));
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    const desk = await screen.findByRole("complementary", { name: "Câmeras do condomínio em monitoramento" });
    await user.click(within(desk).getByRole("button", { name: "Abrir até 4 câmeras" }));
    const grid = await screen.findByLabelText("Câmeras monitoradas");
    expect(within(grid).getByRole("region", { name: "Vídeo de Câmera garagem" })).toBeInTheDocument();
    expect(within(grid).getByRole("region", { name: "Vídeo de Câmera hall" })).toBeInTheDocument();
  });

  it("shows an explicit error, not a picture, when the media server is down", async () => {
    videoDown = true;
    const hlsBefore = hlsInstances.length;
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Câmeras" }));
    await user.click(await screen.findByRole("button", { name: "Ver ao vivo Câmera garagem" }));
    expect(await screen.findByText(/Vídeo indisponível/)).toBeInTheDocument();
    expect(hlsInstances.length).toBe(hlsBefore);
  });

  it("highlights a panic alert waiting in the queue", async () => {
    await login();
    expect(await screen.findByText(/PÂNICO na fila: 1/)).toBeInTheDocument();
  });

  it("asks the resident to authorize a visitor and never treats the answer as an opened door", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    const ask = await screen.findByRole("button", { name: "Pedir autorização ao morador" });
    expect(ask).toBeDisabled();
    await user.selectOptions(screen.getByLabelText("Unidade visitada"), "un1");
    await user.type(screen.getByLabelText("Nome do visitante"), "Tia Maria");
    await user.click(ask);
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/visit-requests"))?.body).toEqual({ unitId: "un1", visitorName: "Tia Maria", kind: "VISITOR", callId: "k1" }));
    expect(screen.getByText(/não abre a porta sozinha/)).toBeInTheDocument();
    expect(screen.queryByText(/Porta aberta/)).not.toBeInTheDocument();
  });

  it("prefills the door justification when the resident approves, without opening anything", async () => {
    visitStatus = "APPROVED";
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Entregador/ }));
    expect(await screen.findByText(/AUTORIZADO pelo morador/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Justificativa da abertura")).toHaveValue("Morador autorizou pelo app (visitante: Tia Maria)"));
    expect(posted.some((p) => p.url.endsWith("/open"))).toBe(false);
  });

  it("registers the exit of a visitor who is on site", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    expect(await screen.findByText(/Dona Rosa/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Registrar saída de Dona Rosa" }));
    await waitFor(() => expect(posted.some((p) => p.url.endsWith("/access/entries/e1/exit"))).toBe(true));
  });

  it("registers the entry of a walk-in the resident approved, once", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    expect(await screen.findByText(/Tia Balcão/)).toBeInTheDocument();
    // aguardando o morador: ainda não há botão de entrada
    expect(screen.queryByRole("button", { name: "Registrar entrada de Tia Balcão" })).not.toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Registrar entrada de João Autorizado" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Registrar entrada de Maria Já Entrou" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Registrar entrada de João Autorizado" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/visit-requests/vr10/entry"))?.body).toEqual({ entrance: "portaria" }));
  });

  it("keeps remote opening off until the manager enables it, then marks a door for residents", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Portas" }));
    expect(await screen.findByText("desligada")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Liberar abertura pelo morador/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Habilitar" }));
    expect(await screen.findByText("habilitada")).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "Liberar abertura pelo morador" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/devices/d1/resident-openable"))?.body).toEqual({ allowed: true }));
  });

  it("publishes an announcement and writes to the shift log", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Comunicados" }));
    expect(await screen.findByText("Caixa d'água na sexta")).toBeInTheDocument();
    const publish = screen.getByRole("button", { name: /Publicar para todos/ });
    expect(publish).toBeDisabled();
    await user.type(screen.getByLabelText("Título"), "Aviso");
    await user.type(screen.getByLabelText("Mensagem"), "Sem água amanhã");
    await user.click(publish);
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/announcements"))?.body).toEqual({ title: "Aviso", body: "Sem água amanhã" }));

    await user.click(screen.getByRole("tab", { name: "Livro de turno" }));
    expect(await screen.findByText("Portão social instável")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Texto"), "Ronda concluída");
    await user.click(screen.getByRole("button", { name: "Registrar" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/shift-log"))?.body).toEqual({ kind: "NOTE", text: "Ronda concluída" }));
  });

  it("opens a resident conversation and replies", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Mensagens" }));
    expect(await screen.findByText("2 nova(s)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Maria Teste" }));
    expect(await screen.findByText("Preciso de ajuda")).toBeInTheDocument();
    const reply = screen.getByRole("button", { name: "Responder" });
    expect(reply).toBeDisabled();
    await user.type(screen.getByLabelText("Resposta"), "Já libero");
    await user.click(reply);
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/messages/threads/ru1"))?.body).toEqual({ text: "Já libero" }));
  });

  it("asks the resident to authorize a walk-in visitor without a call", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    expect(await screen.findByText(/Tia Balcão/)).toBeInTheDocument();
    expect(screen.getByText("aguardando morador")).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Unidade visitada"), "un1");
    await user.type(screen.getByLabelText("Nome do visitante"), "Seu Zé");
    await user.click(screen.getByRole("button", { name: "Pedir autorização ao morador" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/visit-requests"))?.body).toEqual({ unitId: "un1", visitorName: "Seu Zé", kind: "VISITOR", callId: null }));
  });

  it("validates a resident's invitation at the desk without implying the door opened", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    const validate = screen.getByRole("button", { name: "Validar" });
    expect(validate).toBeDisabled();
    await user.type(screen.getByLabelText("Código do convite"), "abc.def");
    await user.click(validate);
    expect(await screen.findByText(/Entrada liberada: Dona Rosa/)).toBeInTheDocument();
    expect(screen.getByText(/a porta não é aberta/)).toBeInTheDocument();
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/access/validate"))?.body).toEqual({ token: "abc.def", entrance: "portaria" }));
  });

  it("registers blocks, a spouse and a pet from the registry", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Cadastros" }));
    expect(await screen.findByText((_, el) => el?.tagName === "LI" && el.textContent === "Bloco A: 101")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Nome do bloco ou torre"), "Bloco B");
    await user.click(screen.getByRole("button", { name: "Cadastrar bloco" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/blocks"))?.body).toEqual({ name: "Bloco B" }));

    await user.click(screen.getByRole("button", { name: "Moradores" }));
    await user.selectOptions(screen.getByLabelText("Unidade"), "un1");
    expect(await screen.findByText("Joana Teste")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Nome completo"), "Carlos Teste");
    await user.selectOptions(screen.getByLabelText("Vínculo"), "SPOUSE");
    await user.click(screen.getByRole("button", { name: "Cadastrar morador" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/residents"))?.body).toEqual({ unitId: "un1", fullName: "Carlos Teste", email: null, phone: null, residentType: "SPOUSE" }));
    await user.click(screen.getByRole("button", { name: "Desativar Joana Teste" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/residents/r1"))?.body).toMatchObject({ active: false, residentType: "OWNER" }));

    await user.click(screen.getByRole("button", { name: "Animais" }));
    expect(await screen.findByText(/Rex/)).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Unidade"), "un1");
    await user.type(screen.getByLabelText("Nome"), "Mimi");
    await user.selectOptions(screen.getByLabelText("Espécie"), "CAT");
    await user.click(screen.getByRole("button", { name: "Cadastrar animal" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/pets"))?.body).toEqual({ unitId: "un1", name: "Mimi", species: "CAT", breed: null, notes: null }));
  });

  it("groups the sections and lets the manager register a camera", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    for (const g of ["Rotina", "Segurança", "Comunicação", "Gestão"]) expect(screen.getByRole("group", { name: g })).toBeInTheDocument();
    await user.click(await screen.findByRole("tab", { name: "Câmeras" }));
    const register = screen.getByRole("button", { name: "Cadastrar câmera" });
    expect(register).toBeDisabled();
    await user.type(screen.getByLabelText("Nome"), "Câmera hall");
    await user.type(screen.getByLabelText("Entrada"), "portaria");
    await user.type(screen.getByLabelText(/Endereço RTSP/), "rtsp://192.168.0.20:554/stream1");
    await user.type(screen.getByLabelText(/Variável de ambiente/), "CAM_HALL_CRED");
    await user.click(register);
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/devices"))?.body).toEqual({ name: "Câmera hall", kind: "CAMERA", adapter: "ONVIF", entrance: "portaria", config: { rtspUrl: "rtsp://192.168.0.20:554/stream1", rtspCredentialsEnv: "CAM_HALL_CRED" } }));
  });

  it("queries the access report by period and offers the CSV export", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Relatórios" }));
    await user.click(screen.getByRole("button", { name: "Consultar" }));
    expect(await screen.findByText(/Seu Jorge/)).toBeInTheDocument();
    const reportCall = (fetch as unknown as { mock: { calls: [string][] } }).mock.calls.find(([u]) => u.includes("/access/report?"));
    expect(reportCall?.[0]).toMatch(/from=.*&to=/);
    expect(screen.getByRole("button", { name: "Exportar CSV" })).toBeInTheDocument();
    expect(screen.getByText(/dado pessoal/)).toBeInTheDocument();
  });

  it("prints a parcel receipt through the browser", async () => {
    parcelList = [{ id: "p1abcdef12", unitId: "un1", carrier: "Correios", description: null, status: "RECEIVED", receivedAt: new Date().toISOString(), pickedUpAt: null, pickedUpByName: null }];
    const print = vi.fn();
    vi.stubGlobal("print", print);
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("button", { name: "Imprimir comprovante" }));
    await waitFor(() => expect(print).toHaveBeenCalledTimes(1));
  });

  it("raises a full-screen panic alert with the unit, plays the siren and lets the operator attend or silence", async () => {
    stubAudio();
    panics = [{ callId: "k9", condominiumId: "c1", condominiumName: "Residencial Sol", unitId: "un1", unitLabel: "A / 101", createdAt: new Date().toISOString() }];
    const user = await login();
    const dialog = await screen.findByRole("alertdialog", { name: "Alerta de pânico" });
    expect(dialog).toHaveTextContent("A / 101");
    expect(dialog).toHaveTextContent("Residencial Sol");
    await waitFor(() => expect(sirenStarts.length).toBeGreaterThan(0));
    await user.click(screen.getByRole("button", { name: "Silenciar som" }));
    const after = sirenStarts.length;
    await new Promise((r) => setTimeout(r, 1500));
    expect(sirenStarts.length).toBe(after); // silenciado: nenhum tom novo
    expect(screen.getByRole("alertdialog")).toBeInTheDocument(); // o alerta segue na tela até alguém atender
    await user.click(screen.getByRole("button", { name: "Atender agora" }));
    await waitFor(() => expect(posted.some((p) => p.url === "/api/v1/operator/calls/next")).toBe(true));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(), { timeout: 5000 });
    expect(await screen.findByText(/Unidade: A \/ 101/)).toBeInTheDocument();
    expect(screen.getByText(/Alerta de pânico acionado pelo morador/)).toBeInTheDocument();
  });

  it("summarizes parcels to pick up for the desk, lets the doorman remind the resident and go back", async () => {
    parcelList = [{ id: "p1abcdef12", unitId: "un1", carrier: "Correios", description: null, status: "RECEIVED", receivedAt: new Date().toISOString(), pickedUpAt: null, pickedUpByName: null }];
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    expect(await screen.findByRole("tab", { name: "Encomendas" })).toHaveTextContent("Encomendas (1)");
    expect(within(screen.getByRole("status", { name: "Resumo da portaria" })).getByText("encomenda a retirar")).toBeInTheDocument();
    expect(await screen.findByText(/1 encomenda aguardando retirada na portaria/)).toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: /Lembrar morador/ }));
    await waitFor(() => expect(posted.some((p) => p.url.endsWith("/parcels/p1abcdef12/remind"))).toBe(true));
    expect(await screen.findByText(/lembrete enviado/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "← Voltar aos condomínios" }));
    expect(screen.queryByRole("region", { name: "Mesa do condomínio" })).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Condomínios" })).toBeInTheDocument();
  });

  it("announces a service provider with the company and a known visitor as separate kinds", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    await user.selectOptions(await screen.findByLabelText("Unidade visitada"), "un1");
    await user.type(screen.getByLabelText("Nome do visitante"), "João");
    await user.selectOptions(screen.getByLabelText("Tipo"), "SERVICE_PROVIDER");
    await user.type(screen.getByLabelText(/Empresa ou serviço/), "Net");
    await user.click(screen.getByRole("button", { name: "Pedir autorização ao morador" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/visit-requests"))?.body).toEqual({ unitId: "un1", visitorName: "João (Net)", kind: "SERVICE_PROVIDER", callId: null }));
    await user.selectOptions(screen.getByLabelText("Tipo"), "VISITOR");
    await user.type(screen.getByLabelText("Nome do visitante"), "Tia Rosa");
    await user.click(screen.getByRole("button", { name: "Pedir autorização ao morador" }));
    await waitFor(() => expect(posted.filter((p) => p.url.endsWith("/visit-requests")).at(-1)?.body).toEqual({ unitId: "un1", visitorName: "Tia Rosa", kind: "VISITOR", callId: null }));
  });

  it("shows several cameras at once, edits one, deactivates one and marks the visitor entrance camera", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Câmeras" }));
    expect(await screen.findByText(/câmera de entrada de visitas/)).toBeInTheDocument(); // d3 já é a de entrada
    expect(screen.getByText(/Câmera velha/).closest("li")).toHaveTextContent("desativada");
    expect(screen.queryByRole("button", { name: "Ver ao vivo Câmera velha" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver ao vivo Câmera garagem" }));
    await user.click(screen.getByRole("button", { name: "Ver ao vivo Câmera hall" }));
    const grid = await screen.findByLabelText("Câmeras ao vivo");
    expect(within(grid).getByRole("region", { name: "Vídeo de Câmera garagem" })).toBeInTheDocument();
    expect(within(grid).getByRole("region", { name: "Vídeo de Câmera hall" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Tela cheia Câmera hall" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Fechar vídeo Câmera hall" }));
    expect(within(grid).queryByRole("region", { name: "Vídeo de Câmera hall" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Editar Câmera garagem" }));
    await user.clear(screen.getByLabelText("Novo nome"));
    await user.type(screen.getByLabelText("Novo nome"), "Garagem 2");
    await user.click(screen.getByRole("button", { name: "Salvar alterações" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/devices/d2"))?.body).toEqual({ name: "Garagem 2", entrance: "garagem", config: { rtspUrl: "rtsp://10.0.0.5/garagem" } }));

    await user.click(screen.getByRole("button", { name: "Desativar Câmera hall" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/devices/d3/active"))?.body).toEqual({ active: false }));
    await user.click(screen.getByRole("button", { name: "Reativar Câmera velha" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/devices/d4/active"))?.body).toEqual({ active: true }));
    await user.click(screen.getByRole("button", { name: "Liberar Câmera garagem para o morador" }));
    await waitFor(() => expect(posted.some((p) => p.url.endsWith("/devices/d2/resident-viewable") && (p.body as { enabled: boolean }).enabled === true)).toBe(true));
    expect(screen.queryByRole("button", { name: /Câmera velha para o morador/ })).not.toBeInTheDocument(); // desativada
    await user.click(screen.getByRole("button", { name: "Marcar Câmera garagem como câmera de entrada de visitas" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/devices/d2/visitor-entrance"))?.body).toEqual({ enabled: true }));
  });

  it("captures the entrance image, shows the preview and sends it with the request", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    await user.selectOptions(await screen.findByLabelText("Unidade visitada"), "un1");
    await user.type(screen.getByLabelText("Nome do visitante"), "Moço da entrega");
    await user.click(await screen.findByRole("button", { name: "Capturar imagem da entrada" }));
    const preview = await screen.findByRole("group", { name: "Prévia da imagem" });
    expect(await within(preview).findByRole("img", { name: /Prévia da imagem de Câmera hall/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Pedir autorização ao morador (com imagem)" }));
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/visit-requests"))?.body).toEqual({ unitId: "un1", visitorName: "Moço da entrega", kind: "VISITOR", callId: null, snapshotId: "s1" }));
    expect(screen.queryByRole("group", { name: "Prévia da imagem" })).not.toBeInTheDocument(); // imagem consumida
  });

  it("shows the image of a request that carries one", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Visitantes" }));
    await user.click(await screen.findByRole("button", { name: "Ver imagem de João Autorizado" }));
    expect(await screen.findByRole("img", { name: /Imagem da entrada de João Autorizado/ })).toBeInTheDocument();
  });

  it("registers an access means for a resident without ever showing the stored reference or the personal history", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Cadastros" }));
    await user.click(await screen.findByRole("button", { name: "Moradores" }));
    await user.selectOptions(screen.getByLabelText("Unidade"), "un1");
    await user.click(await screen.findByRole("button", { name: "Meios de acesso de Joana Teste" }));
    expect(await screen.findByText(/Foto de cadastro: cadastrada/)).toBeInTheDocument();
    expect(screen.getByText(/vista só pelo próprio morador/)).toBeInTheDocument();
    const register = screen.getByRole("button", { name: "Cadastrar meio de acesso" });
    expect(register).toBeDisabled();
    await user.type(screen.getByLabelText("Número ou referência no equipamento"), "CARD-0001");
    await user.click(register);
    await waitFor(() => expect(posted.find((p) => p.url.endsWith("/residents/r1/access-identities"))?.body).toEqual({ kind: "CARD", ref: "CARD-0001" }));
    await user.click(await screen.findByRole("button", { name: "Revogar usuário do terminal de Joana Teste" }));
    await waitFor(() => expect(posted.some((p) => p.url.endsWith("/access-identities/ai0/revoke"))).toBe(true));
  });
});
