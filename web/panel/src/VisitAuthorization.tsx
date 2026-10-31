import { useEffect } from "react";
import type { Call, VisitRequest } from "./types";
import { useCondoList } from "./useCondoList";
import { VisitRequestForm } from "./VisitRequestForm";

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

  const latest = requests.items[0];
  useEffect(() => {
    if (latest?.status === "APPROVED") onApproved(latest.visitorName);
    // avisa só quando o último pedido muda para aprovado (onApproved é recriado a cada render)
  }, [latest?.id, latest?.status]);

  return (
    <div aria-label="Autorização do morador">
      <h3>Autorização do morador</h3>
      <VisitRequestForm token={token} condominiumId={call.condominiumId} callId={call.id} fixedUnitId={call.unitId}
        onAsked={() => requests.reload()} />
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
