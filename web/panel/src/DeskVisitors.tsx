import type { Attempt, Unit, Visitor } from "./types";
import { useCondoList } from "./useCondoList";

/** Visitantes cadastrados e as últimas tentativas de entrada validadas na portaria. */
export function DeskVisitors({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const visitors = useCondoList<Visitor>(token, `${base}/visitors?size=50&sort=createdAt,desc`, 10000);
  const attempts = useCondoList<Attempt>(token, `${base}/access/attempts?size=20&sort=occurredAt,desc`, 10000);
  const units = useCondoList<Unit>(token, `${base}/units?size=200`);
  const unitNumber = (id: string) => units.items.find((u) => u.id === id)?.number ?? "?";

  return (
    <div aria-label="Visitantes">
      <h3>Entradas recentes</h3>
      <ul>
        {attempts.items.map((a) => (
          <li key={a.id}>
            {new Date(a.occurredAt).toLocaleString()} · {a.entrance} ·{" "}
            <span className={a.result === "GRANTED" ? "badge ok" : "badge warn"}>{a.result === "GRANTED" ? "liberada" : "negada"}</span>
            {a.result !== "GRANTED" && ` (${a.reason})`}
          </li>
        ))}
        {attempts.items.length === 0 && <li>Nenhuma tentativa registrada.</li>}
      </ul>
      <h3>Visitantes cadastrados</h3>
      <ul>
        {visitors.items.map((v) => (
          <li key={v.id}>
            {v.fullName} · {v.kind === "SERVICE_PROVIDER" ? "prestador" : "visitante"} · unidade {unitNumber(v.unitId)}
          </li>
        ))}
        {visitors.items.length === 0 && <li>Nenhum visitante cadastrado.</li>}
      </ul>
    </div>
  );
}
