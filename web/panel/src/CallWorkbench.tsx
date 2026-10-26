import { useEffect, useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";
import { VisitAuthorization } from "./VisitAuthorization";
import { OUTCOMES, SENSITIVE_OUTCOMES, type Call, type CallEvent, type Command, type Device, type OnDuty } from "./types";

/** Atendimento selecionado: histórico, desfecho com justificativa, transferência e (futuro) abertura de porta. */
export function CallWorkbench({ token, call, onChanged, onBack }:
  { token: string; call: Call; onChanged: () => void; onBack: () => void }) {
  const base = `/api/v1/condominiums/${call.condominiumId}/calls/${call.id}`;
  const [events, setEvents] = useState<CallEvent[]>([]);
  const [operators, setOperators] = useState<OnDuty[]>([]);
  const [outcome, setOutcome] = useState(OUTCOMES[0]);
  const [justification, setJustification] = useState("");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [doorReason, setDoorReason] = useState("");
  const [command, setCommand] = useState<Command | null>(null);

  const needsJustification = SENSITIVE_OUTCOMES.includes(outcome);
  const closeBlocked = needsJustification && justification.trim().length < 5;

  useEffect(() => {
    request<CallEvent[]>(token, "GET", `${base}/events`).then((e) => setEvents(e ?? []));
    request<OnDuty[]>(token, "GET", `/api/v1/condominiums/${call.condominiumId}/operators/on-duty`).then((o) =>
      setOperators(o ?? []),
    );
    request<Page<Device>>(token, "GET", `/api/v1/condominiums/${call.condominiumId}/devices`).then((p) =>
      setDevices((p?.items ?? []).filter((d) => d.kind === "ACCESS_CONTROLLER")),
    );
  }, [token, base, call.condominiumId]);

  // Acompanha o resultado do comando até um estado final. Só CONFIRMED significa porta aberta.
  useEffect(() => {
    if (!command || ["CONFIRMED", "FAILED", "UNKNOWN"].includes(command.state)) return;
    const id = setInterval(async () => {
      const next = await request<Command>(token, "GET", `/api/v1/condominiums/${call.condominiumId}/commands/${command.id}`);
      if (next) setCommand(next);
    }, 1000);
    return () => clearInterval(id);
  }, [command, token, call.condominiumId]);

  async function openDoor() {
    setError(null);
    try {
      const sent = await request<Command>(
        token,
        "POST",
        `/api/v1/condominiums/${call.condominiumId}/devices/${deviceId}/open`,
        { callId: call.id, justification: doorReason, idempotencyKey: crypto.randomUUID() },
      );
      setCommand(sent);
    } catch (e) {
      setError(e instanceof ApiError ? e.detail : "Unexpected error");
    }
  }

  async function act(path: string, body: unknown) {
    setError(null);
    try {
      await request(token, "POST", `${base}/${path}`, body);
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.detail : "Unexpected error");
    }
  }

  return (
    <section aria-label="Atendimento">
      <button className="back" onClick={onBack}>← Voltar (o atendimento continua em andamento)</button>
      <h2>Atendimento {call.source === "PANIC" ? "de PÂNICO" : (call.callerName ?? "(sem nome)")}</h2>
      {call.unitLabel && <p><strong>Unidade: {call.unitLabel}</strong></p>}
      {call.source === "PANIC" && (
        <p role="alert" className="badge err">
          Alerta de pânico acionado pelo morador. Contate a unidade e acione o socorro necessário antes de encerrar.
        </p>
      )}
      <p>
        Origem: {call.source} · Status: {call.status}
      </p>

      <VisitAuthorization token={token} call={call}
        onApproved={(visitor) => setDoorReason((r) => r || `Morador autorizou pelo app (visitante: ${visitor})`)} />

      <h3>Abrir porta</h3>
      <label htmlFor="door-device">Dispositivo</label>
      <select id="door-device" value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
        <option value="">Selecione</option>
        {devices.map((d) => (
          <option key={d.id} value={d.id}>
            {d.name} ({d.entrance}) — {d.status}
          </option>
        ))}
      </select>
      <label htmlFor="door-reason">Justificativa da abertura</label>
      <input id="door-reason" value={doorReason} onChange={(e) => setDoorReason(e.target.value)} />
      <button disabled={!deviceId || doorReason.trim().length < 5} onClick={openDoor}>
        Abrir porta
      </button>
      {command && (
        <p role="status" className={command.state === "CONFIRMED" ? "" : "hint"}>
          {command.state === "CONFIRMED" && `Porta aberta — confirmado (${command.evidence}).`}
          {(command.state === "PENDING" || command.state === "SENT") && "Comando enviado. Aguardando confirmação do dispositivo…"}
          {command.state === "FAILED" && `Falhou: ${command.failureReason}. A porta NÃO foi aberta.`}
          {command.state === "UNKNOWN" &&
            "Sem confirmação — estado desconhecido. Verifique no local antes de tentar novamente."}
        </p>
      )}

      <h3>Desfecho</h3>
      <label htmlFor="outcome">Desfecho</label>
      <select id="outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
        {OUTCOMES.map((o) => (
          <option key={o}>{o}</option>
        ))}
      </select>
      <label htmlFor="justification">Justificativa do desfecho{needsJustification ? " (obrigatória)" : ""}</label>
      <textarea id="justification" value={justification} onChange={(e) => setJustification(e.target.value)} />
      {closeBlocked && <p className="hint">Informe a justificativa (mín. 5 caracteres) para este desfecho.</p>}
      <button disabled={closeBlocked} onClick={() => act("close", { outcome, justification: justification || null })}>
        Encerrar atendimento
      </button>

      <h3>Transferir</h3>
      <label htmlFor="target">Operador de plantão</label>
      <select id="target" value={target} onChange={(e) => setTarget(e.target.value)}>
        <option value="">Selecione</option>
        {operators.map((o) => (
          <option key={o.id} value={o.id}>
            {o.displayName}
          </option>
        ))}
      </select>
      <label htmlFor="reason">Motivo</label>
      <input id="reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      <button disabled={!target || !reason.trim()} onClick={() => act("transfer", { toUserId: target, reason })}>
        Transferir
      </button>

      {error && <p role="alert" className="error">{error}</p>}

      <h3>Histórico</h3>
      <ol>
        {events.map((e, i) => (
          <li key={i}>
            {new Date(e.occurredAt).toLocaleTimeString()} — {e.type}
            {e.detail ? `: ${e.detail}` : ""}
          </li>
        ))}
      </ol>
    </section>
  );
}
