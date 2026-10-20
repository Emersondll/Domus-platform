import { useEffect, useState } from "react";
import { ApiError, request } from "../../shared/src/api";
import { OUTCOMES, SENSITIVE_OUTCOMES, type Call, type CallEvent, type OnDuty } from "./types";

/** Atendimento selecionado: histórico, desfecho com justificativa, transferência e (futuro) abertura de porta. */
export function CallWorkbench({ token, call, onChanged }: { token: string; call: Call; onChanged: () => void }) {
  const base = `/api/v1/condominiums/${call.condominiumId}/calls/${call.id}`;
  const [events, setEvents] = useState<CallEvent[]>([]);
  const [operators, setOperators] = useState<OnDuty[]>([]);
  const [outcome, setOutcome] = useState(OUTCOMES[0]);
  const [justification, setJustification] = useState("");
  const [target, setTarget] = useState("");
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const needsJustification = SENSITIVE_OUTCOMES.includes(outcome);
  const closeBlocked = needsJustification && justification.trim().length < 5;

  useEffect(() => {
    request<CallEvent[]>(token, "GET", `${base}/events`).then((e) => setEvents(e ?? []));
    request<OnDuty[]>(token, "GET", `/api/v1/condominiums/${call.condominiumId}/operators/on-duty`).then((o) =>
      setOperators(o ?? []),
    );
  }, [token, base, call.condominiumId]);

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
      <h2>Atendimento {call.callerName ?? "(sem nome)"}</h2>
      <p>
        Origem: {call.source} · Status: {call.status}
      </p>

      <button disabled title="Door control integration is not implemented yet">
        Abrir porta
      </button>
      <span className="badge pending"> Controle de porta não integrado (Fase 6)</span>

      <h3>Desfecho</h3>
      <label htmlFor="outcome">Desfecho</label>
      <select id="outcome" value={outcome} onChange={(e) => setOutcome(e.target.value)}>
        {OUTCOMES.map((o) => (
          <option key={o}>{o}</option>
        ))}
      </select>
      <label htmlFor="justification">Justificativa{needsJustification ? " (obrigatória)" : ""}</label>
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
