import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import type { Call, Unit, VisitRequest } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

const STATUS_TEXT: Record<string, string> = {
  PENDING: "aguardando resposta do morador",
  APPROVED: "AUTORIZADO pelo morador",
  DENIED: "NEGADO pelo morador",
  EXPIRED: "sem resposta no prazo",
};

/** Pede ao morador da unidade que autorize quem está na portaria. A resposta não abre a porta: abrir é decisão do operador. */
export function VisitAuthorization({ token, call, onApproved }: { token: string; call: Call; onApproved: (visitor: string) => void }) {
  const base = `/api/v1/condominiums/${call.condominiumId}`;
  const requests = useCondoList<VisitRequest>(token, `${base}/visit-requests?callId=${call.id}&size=5`, 4000);
  const units = useCondoList<Unit>(token, call.unitId ? "" : `${base}/units?size=200`);
  const [unitId, setUnitId] = useState(call.unitId ?? "");
  const [name, setName] = useState("");
  const [kind, setKind] = useState("VISITOR");
  const [error, setError] = useState<string | null>(null);

  const latest = requests.items[0];
  useEffect(() => {
    if (latest?.status === "APPROVED") onApproved(latest.visitorName);
    // avisa só quando o último pedido muda para aprovado (onApproved é recriado a cada render)
  }, [latest?.id, latest?.status]);

  async function ask() {
    setError(null);
    try {
      await request(token, "POST", `${base}/visit-requests`, { unitId, visitorName: name, kind, callId: call.id });
      setName("");
      await requests.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Autorização do morador">
      <h3>Autorização do morador</h3>
      {!call.unitId && (
        <>
          <label htmlFor="visit-unit">Unidade visitada</label>
          <select id="visit-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
            <option value="">Selecione</option>
            {units.items.map((u) => <option key={u.id} value={u.id}>{u.number}</option>)}
          </select>
        </>
      )}
      <label htmlFor="visit-name">Nome do visitante</label>
      <input id="visit-name" value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="visit-kind">Tipo</label>
      <select id="visit-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
        <option value="VISITOR">Visitante</option>
        <option value="SERVICE_PROVIDER">Prestador</option>
      </select>
      <button disabled={!unitId || !name.trim()} onClick={ask}>Pedir autorização ao morador</button>
      {error && <p role="alert" className="error">{error}</p>}
      <ul>
        {requests.items.map((r) => (
          <li key={r.id}>
            {r.visitorName} · <span className={r.status === "APPROVED" ? "badge ok" : r.status === "PENDING" ? "badge pending" : "badge warn"}>{STATUS_TEXT[r.status] ?? r.status}</span>
          </li>
        ))}
      </ul>
      <p className="hint">A autorização do morador não abre a porta sozinha: abra pelo botão "Abrir porta", com a justificativa.</p>
    </div>
  );
}
