import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const overview = [
  { condominiumId: "c1", name: "Residencial Sol", queuedCalls: 2, assignedCalls: 1, openIncidents: 0,
    deviceHealth: "NOT_IMPLEMENTED", connectivity: "NOT_IMPLEMENTED" },
];
let commandState = "SENT";
const call = { id: "k1", condominiumId: "c1", unitId: null, source: "MANUAL", callerName: "Entregador",
  status: "ASSIGNED", assignedUserId: "u1", createdAt: new Date().toISOString(), outcome: null };

/** Substitui apenas a rede (fetch): a UI e a lógica do cliente rodam de verdade. */
function fakeApi() {
  return vi.fn(async (url: string) => {
    const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
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
});
