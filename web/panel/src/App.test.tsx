import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const overview = [
  { condominiumId: "c1", name: "Residencial Sol", queuedCalls: 2, assignedCalls: 1, openIncidents: 0, queuedPanics: 1,
    deviceHealth: "NOT_IMPLEMENTED", connectivity: "NOT_IMPLEMENTED" },
];
let commandState = "SENT";
let parcelList: unknown[] = [];
let posted: { url: string; body: unknown }[] = [];
let visitStatus = "PENDING";
let remoteEnabled = false;
const call = { id: "k1", condominiumId: "c1", unitId: null, source: "MANUAL", callerName: "Entregador",
  status: "ASSIGNED", assignedUserId: "u1", createdAt: new Date().toISOString(), outcome: null };

/** Substitui apenas a rede (fetch): a UI e a lógica do cliente rodam de verdade. */
function fakeApi() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
    const pg = (items: unknown[]) => json({ items, page: 0, size: 50, totalElements: items.length });
    if (init?.method === "POST" || init?.method === "PUT") posted.push({ url, body: init.body ? JSON.parse(init.body as string) : null });
    if (url.includes("/access/on-site?")) return pg([{ attemptId: "e1", occurredAt: new Date().toISOString(), entrance: "portaria", result: "GRANTED", reason: "OK", unitId: "un1", unitLabel: "A / 101", visitorName: "Dona Rosa", exitedAt: null }]);
    if (url.endsWith("/exit") && init?.method === "POST") return json({ attemptId: "e1" });
    if (url.includes("/access/report?")) return pg([{ attemptId: "e2", occurredAt: new Date().toISOString(), entrance: "garagem", result: "GRANTED", reason: "OK", unitId: "un1", unitLabel: "A / 101", visitorName: "Seu Jorge", exitedAt: null }]);
    if (url.endsWith("/remote-open") && init?.method === "PUT") { remoteEnabled = JSON.parse(init.body as string).enabled; return json({ enabled: remoteEnabled }); }
    if (url.endsWith("/remote-open")) return json({ enabled: remoteEnabled });
    if (url.endsWith("/resident-openable")) return json({ id: "d1", residentOpenable: true });
    if (url.includes("/announcements?")) return pg([{ id: "an1", title: "Manutenção", body: "Caixa d'água na sexta", createdAt: new Date().toISOString() }]);
    if (url.endsWith("/announcements") && init?.method === "POST") return json({ id: "an2" });
    if (url.includes("/shift-log?")) return pg([{ id: "sl1", shiftId: null, kind: "HANDOVER", text: "Portão social instável", createdAt: new Date().toISOString() }]);
    if (url.endsWith("/shift-log") && init?.method === "POST") return json({ id: "sl2" });
    if (url.includes("/visit-requests?callId=")) return pg([{ id: "vr1", unitId: "un1", callId: "k1", visitorName: "Tia Maria", kind: "VISITOR", status: visitStatus, createdAt: new Date().toISOString(), expiresAt: new Date().toISOString(), decidedAt: null }]);
    if (url.endsWith("/visit-requests") && init?.method === "POST") return json({ id: "vr2" });
    if (url.includes("/units?")) return pg([{ id: "un1", blockId: "b1", number: "101" }]);
    if (url.endsWith("/parcels") && init?.method === "POST") return json({ id: "p9" });
    if (url.includes("/parcels/") && url.endsWith("/pickup")) return json({ id: "p1" });
    if (url.includes("/parcels?")) return pg(parcelList);
    if (url.includes("/visitors?")) return pg([{ id: "v1", unitId: "un1", fullName: "Tia Maria", kind: "VISITOR" }]);
    if (url.includes("/access/attempts?")) return pg([{ id: "a1", credentialId: null, entrance: "portaria", result: "DENIED", reason: "EXPIRED", occurredAt: new Date().toISOString() }]);
    if (url.includes("/commands?")) return pg([{ id: "cmd0", deviceId: "d1", state: "SENT", failureReason: null, requestedAt: new Date().toISOString() }]);
    if (url.includes("/devices?")) return pg([
      { id: "d1", name: "Portão principal", kind: "ACCESS_CONTROLLER", adapter: "SIMULATOR", entrance: "portaria", status: "ONLINE", lastSeenAt: null },
      { id: "d2", name: "Câmera garagem", kind: "CAMERA", adapter: "ONVIF", entrance: "garagem", status: "OFFLINE", lastSeenAt: null },
    ]);
    if (url.startsWith("/dev/token")) return json({ access_token: "t", token_type: "Bearer" });
    if (url === "/api/v1/operator/overview") return json(overview);
    if (url === "/api/v1/operator/calls/mine") return json({ items: [call], page: 0, size: 50, totalElements: 1 });
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
    remoteEnabled = false;
    parcelList = [];
    vi.stubGlobal("fetch", fakeApi());
  });
  afterEach(() => vi.unstubAllGlobals());

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

  it("states that live video is not integrated and lists only cameras", async () => {
    const user = await login();
    await user.click(await screen.findByRole("button", { name: /Residencial Sol/ }));
    await user.click(await screen.findByRole("tab", { name: "Câmeras" }));
    expect(await screen.findByText(/Vídeo ao vivo não integrado/)).toBeInTheDocument();
    expect(await screen.findByText("Câmera garagem")).toBeInTheDocument();
    expect(screen.queryByText("Portão principal")).not.toBeInTheDocument();
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
    await user.click(screen.getByRole("button", { name: "Registrar saída" }));
    await waitFor(() => expect(posted.some((p) => p.url.endsWith("/access/entries/e1/exit"))).toBe(true));
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
});
