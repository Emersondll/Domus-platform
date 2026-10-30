import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Call } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";
import { useUnitLabels } from "./useUnitLabels";

const STATUS_TEXT: Record<string, string> = { QUEUED: "na fila", ASSIGNED: "em atendimento", CLOSED: "encerrada" };
const SOURCE_TEXT: Record<string, string> = { RESIDENT: "morador", PANIC: "pânico", OPERATOR: "portaria", INTERCOM: "interfone" };

/** Chamadas do condomínio: todas as da lista e o registro de uma chamada que chegou por fora (balcão, interfone). */
export function DeskCalls({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const calls = useCondoList<Call>(token, `${base}/calls?size=50&sort=createdAt,desc`, 10000);
  const { units, label, labelOf } = useUnitLabels(token, condominiumId);
  const [unitId, setUnitId] = useState("");
  const [callerName, setCallerName] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function register() {
    setError(null);
    try {
      await request(token, "POST", `${base}/calls`, { unitId: unitId || null, callerName: callerName.trim() || null });
      setCallerName("");
      setUnitId("");
      await calls.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Chamadas">
      <h3>Chamadas do condomínio</h3>
      <p className="hint">Todas as chamadas deste condomínio. A fila geral e o atendimento ficam na tela inicial da central.</p>
      <ul>
        {calls.items.map((c) => (
          <li key={c.id}>
            {new Date(c.createdAt).toLocaleString()} · {SOURCE_TEXT[c.source] ?? c.source}
            {c.callerName ? ` · ${c.callerName}` : ""}{c.unitId ? ` · unidade ${c.unitLabel ?? labelOf(c.unitId)}` : ""} ·{" "}
            <span className={c.status === "CLOSED" ? "badge ok" : "badge pending"}>{STATUS_TEXT[c.status] ?? c.status}</span>
            {c.outcome ? ` · ${c.outcome}` : ""}
          </li>
        ))}
        {calls.items.length === 0 && <li>Nenhuma chamada.</li>}
      </ul>
      <h3>Registrar uma chamada</h3>
      <p className="hint">Para quem chegou ou ligou por fora do app. A chamada entra na fila da central.</p>
      <label htmlFor="call-unit">Unidade (opcional)</label>
      <select id="call-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
        <option value="">Sem unidade</option>
        {units.map((u) => <option key={u.id} value={u.id}>{label(u)}</option>)}
      </select>
      <label htmlFor="call-name">Nome de quem chamou (opcional)</label>
      <input id="call-name" maxLength={160} value={callerName} onChange={(e) => setCallerName(e.target.value)} />
      <button onClick={register}>Registrar chamada</button>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
