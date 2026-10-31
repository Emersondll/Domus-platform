import { AuthImage } from "../../shared/src/AuthImage";
import type { Attempt, Invitation, VisitRequest } from "./types";
import { isExpected } from "./Home";
import { useList } from "./useApi";

const POLL_MS = 5000;

/** Visitas da unidade: esperadas, já chegaram e histórico de entradas na portaria. */
export function Visits({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const invitations = useList<Invitation>(token, `${base}/invitations`, POLL_MS);
  const history = useList<Attempt>(token, `${base}/access-history`, POLL_MS);
  const asked = useList<VisitRequest>(token, `${base}/visit-requests`, POLL_MS);
  const STATUS: Record<string, string> = { PENDING: "aguardando a sua resposta", APPROVED: "autorizado", DENIED: "negado", EXPIRED: "sem resposta" };

  const expected = invitations.items.filter((i) => isExpected(i));
  const arrived = invitations.items.filter((i) => i.useCount > 0);
  const nameOf = new Map(invitations.items.map((i) => [i.id, i.visitorName ?? "Visitante"]));

  return (
    <section aria-label="Visitas">
      <h2>Visitas</h2>
      <h3>Avisos da portaria</h3>
      <ul>
        {asked.items.map((r) => (
          <li key={r.id}>
            <strong>{r.visitorName}</strong> · {r.kind === "SERVICE_PROVIDER" ? "prestador" : "visita"} · {STATUS[r.status] ?? r.status} ·{" "}
            {new Date(r.createdAt).toLocaleString()}
            {r.hasSnapshot && <AuthImage token={token} path={`${base}/visit-requests/${r.id}/snapshot`} alt={`Imagem da entrada: ${r.visitorName}`} />}
          </li>
        ))}
        {asked.items.length === 0 && <li className="empty">Nenhum aviso da portaria.</li>}
      </ul>
      <h3>Esperadas</h3>
      <ul>
        {expected.map((i) => (
          <li key={i.id}>
            <strong>{i.visitorName ?? "Visitante"}</strong> · até {new Date(i.validUntil).toLocaleString()} ·{" "}
            {i.maxUses - i.useCount} {i.maxUses - i.useCount === 1 ? "entrada restante" : "entradas restantes"}
          </li>
        ))}
        {expected.length === 0 && <li className="empty">Nenhuma visita esperada. Crie um convite na aba Convites.</li>}
      </ul>
      <h3>Já chegaram</h3>
      <ul>
        {arrived.map((i) => (
          <li key={i.id}>
            <strong>{i.visitorName ?? "Visitante"}</strong> · {i.useCount} {i.useCount === 1 ? "entrada" : "entradas"} registrada
            {i.useCount === 1 ? "" : "s"}
          </li>
        ))}
        {arrived.length === 0 && <li>Ninguém chegou ainda.</li>}
      </ul>
      <h3>Histórico de entradas</h3>
      <ul>
        {history.items.map((a) => (
          <li key={a.id}>
            {new Date(a.occurredAt).toLocaleString()} · {a.credentialId ? nameOf.get(a.credentialId) ?? "Visitante" : "Acesso"} ·{" "}
            {a.entrance} · {a.result === "GRANTED" ? "liberado" : "negado"}
          </li>
        ))}
        {history.items.length === 0 && <li>Sem registros.</li>}
      </ul>
    </section>
  );
}
