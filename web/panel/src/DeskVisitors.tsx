import { useState } from "react";
import { request } from "../../shared/src/api";
import type { AccessRow, Attempt, VisitRequest, Visitor } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";
import { useUnitLabels } from "./useUnitLabels";

/** Visitantes cadastrados e as últimas tentativas de entrada validadas na portaria. */
export function DeskVisitors({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const visitors = useCondoList<Visitor>(token, `${base}/visitors?size=50&sort=createdAt,desc`, 10000);
  const attempts = useCondoList<Attempt>(token, `${base}/access/attempts?size=20&sort=occurredAt,desc`, 10000);
  const { units, label, labelOf: unitNumber } = useUnitLabels(token, condominiumId);
  const onSite = useCondoList<AccessRow>(token, `${base}/access/on-site?size=100`, 10000);
  const requests = useCondoList<VisitRequest>(token, `${base}/visit-requests?size=15`, 5000);
  const [reqUnit, setReqUnit] = useState("");
  const [reqName, setReqName] = useState("");
  const [reqCompany, setReqCompany] = useState("");
  const [reqKind, setReqKind] = useState("VISITOR");
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

  /** Prestador chega com a empresa ou o serviço entre parênteses, para o morador reconhecer quem é. */
  function visitorLabel() {
    const company = reqCompany.trim();
    return company ? `${reqName.trim()} (${company})` : reqName.trim();
  }

  async function askResident() {
    setError(null);
    try {
      await request(token, "POST", `${base}/visit-requests`, { unitId: reqUnit, visitorName: visitorLabel(), kind: reqKind, callId: null });
      setReqName("");
      setReqCompany("");
      await requests.reload();
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
      <h3>Avisar chegada de visita ou prestador</h3>
      <p className="hint">
        O morador recebe a notificação com o nome de quem chegou, na aba Visitas do app, e autoriza ou nega. Vale para visita de pessoa
        conhecida e para prestador de serviço. A resposta do morador não abre a porta: quem libera é a portaria.
      </p>
      <label htmlFor="rq-unit">Unidade visitada</label>
      <select id="rq-unit" value={reqUnit} onChange={(e) => setReqUnit(e.target.value)}>
        <option value="">Selecione</option>
        {units.map((u) => <option key={u.id} value={u.id}>{label(u)}</option>)}
      </select>
      <label htmlFor="rq-name">Nome do visitante</label>
      <input id="rq-name" maxLength={160} value={reqName} onChange={(e) => setReqName(e.target.value)} />
      <label htmlFor="rq-kind">Tipo</label>
      <select id="rq-kind" value={reqKind} onChange={(e) => setReqKind(e.target.value)}>
        <option value="VISITOR">Visita (pessoa conhecida)</option>
        <option value="SERVICE_PROVIDER">Prestador de serviço</option>
      </select>
      {reqKind === "SERVICE_PROVIDER" && (
        <>
          <label htmlFor="rq-company">Empresa ou serviço (opcional)</label>
          <input id="rq-company" maxLength={80} placeholder="Ex.: Net, encanador, entrega de gás" value={reqCompany}
            onChange={(e) => setReqCompany(e.target.value)} />
        </>
      )}
      <button disabled={!reqUnit || !reqName.trim()} onClick={askResident}>Pedir autorização ao morador</button>
      <h4>Pedidos recentes</h4>
      <ul>
        {requests.items.map((r) => (
          <li key={r.id}>
            {r.visitorName} · {r.kind === "SERVICE_PROVIDER" ? "prestador" : "visita"} · unidade {unitNumber(r.unitId)} ·{" "}
            <span className={r.status === "APPROVED" ? "badge ok" : r.status === "PENDING" ? "badge pending" : "badge warn"}>
              {{ PENDING: "aguardando morador", APPROVED: "autorizado", DENIED: "negado", EXPIRED: "sem resposta" }[r.status] ?? r.status}
            </span>
          </li>
        ))}
        {requests.items.length === 0 && <li>Nenhum pedido de autorização.</li>}
      </ul>
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
