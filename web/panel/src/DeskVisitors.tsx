import { useState } from "react";
import { request } from "../../shared/src/api";
import type { AccessRow, Attempt, Unit, Visitor } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

/** Visitantes cadastrados e as últimas tentativas de entrada validadas na portaria. */
export function DeskVisitors({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const visitors = useCondoList<Visitor>(token, `${base}/visitors?size=50&sort=createdAt,desc`, 10000);
  const attempts = useCondoList<Attempt>(token, `${base}/access/attempts?size=20&sort=occurredAt,desc`, 10000);
  const units = useCondoList<Unit>(token, `${base}/units?size=200`);
  const unitNumber = (id: string) => units.items.find((u) => u.id === id)?.number ?? "?";
  const onSite = useCondoList<AccessRow>(token, `${base}/access/on-site?size=100`, 10000);
  const [error, setError] = useState<string | null>(null);
  const [token_, setToken] = useState("");
  const [entrance, setEntrance] = useState("portaria");
  const [verdict, setVerdict] = useState<{ granted: boolean; reason: string; visitorName: string | null } | null>(null);

  async function validate() {
    setError(null);
    setVerdict(null);
    try {
      const v = await request<{ granted: boolean; reason: string; visitorName: string | null }>(token, "POST",
        `${base}/access/validate`, { token: token_.trim(), entrance: entrance.trim() });
      setVerdict(v ?? null);
      setToken("");
      await onSite.reload();
      await attempts.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function registerExit(attemptId: string) {
    setError(null);
    try {
      await request(token, "POST", `${base}/access/entries/${attemptId}/exit`);
      await onSite.reload();
      await attempts.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Visitantes">
      <h3>Validar convite</h3>
      <p className="hint">Cole o código do convite apresentado pelo visitante. A validação registra a entrada; ela não abre nenhuma porta.</p>
      <label htmlFor="inv-token">Código do convite</label>
      <input id="inv-token" value={token_} onChange={(e) => setToken(e.target.value)} />
      <label htmlFor="inv-entrance">Entrada</label>
      <input id="inv-entrance" maxLength={60} value={entrance} onChange={(e) => setEntrance(e.target.value)} />
      <button disabled={!token_.trim() || !entrance.trim()} onClick={validate}>Validar</button>
      {verdict && (
        <p role="status" className={verdict.granted ? "badge ok" : "badge warn"}>
          {verdict.granted ? `Entrada liberada${verdict.visitorName ? `: ${verdict.visitorName}` : ""} (a porta não é aberta por esta ação)` : `Entrada negada (${verdict.reason})`}
        </p>
      )}
      <h3>No local agora ({onSite.items.length})</h3>
      {error && <p role="alert" className="error">{error}</p>}
      <ul>
        {onSite.items.map((r) => (
          <li key={r.attemptId}>
            {r.visitorName ?? "Visitante"}{r.unitLabel ? ` · unidade ${r.unitLabel}` : ""} · entrou {new Date(r.occurredAt).toLocaleTimeString()} · {r.entrance}{" "}
            <button onClick={() => registerExit(r.attemptId)}>Registrar saída</button>
          </li>
        ))}
        {onSite.items.length === 0 && <li>Ninguém no local.</li>}
      </ul>
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
