import { MissingHint } from "./MissingHint";
import { useState } from "react";
import { request } from "../../shared/src/api";
import { AuthImage } from "../../shared/src/AuthImage";
import type { AccessRow, Attempt, VisitRequest, Visitor } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";
import { useUnitLabels } from "./useUnitLabels";
import { VisitRequestForm } from "./VisitRequestForm";

/** Visitantes cadastrados e as últimas tentativas de entrada validadas na portaria. */
export function DeskVisitors({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const visitors = useCondoList<Visitor>(token, `${base}/visitors?size=50&sort=createdAt,desc`, 10000);
  const attempts = useCondoList<Attempt>(token, `${base}/access/attempts?size=20&sort=occurredAt,desc`, 10000);
  const { labelOf: unitNumber } = useUnitLabels(token, condominiumId);
  const onSite = useCondoList<AccessRow>(token, `${base}/access/on-site?size=100`, 10000);
  const requests = useCondoList<VisitRequest>(token, `${base}/visit-requests?size=15`, 5000);
  const [seeing, setSeeing] = useState<string | null>(null);
  const [issuedTicket, setIssuedTicket] = useState<{ visitor: string; ticket: number } | null>(null);
  const [ticketInput, setTicketInput] = useState("");
  const [ticketMsg, setTicketMsg] = useState<string | null>(null);
  const [view, setView] = useState<"now" | "history">("now");

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

  async function registerEntry(requestId: string) {
    setError(null);
    try {
      const entry = await request<{ attemptId: string; exitTicket: number | null }>(token, "POST",
        `${base}/visit-requests/${requestId}/entry`, { entrance: entrance.trim() || "portaria" });
      const who = requests.items.find((q) => q.id === requestId)?.visitorName ?? "Visitante";
      setIssuedTicket(entry?.exitTicket != null ? { visitor: who, ticket: entry.exitTicket } : null);
      await Promise.all([requests.reload(), onSite.reload(), attempts.reload()]);
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

  async function validateTicket(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setTicketMsg(null);
    try {
      const row = await request<AccessRow>(token, "POST", `${base}/access/exit-tickets/${encodeURIComponent(ticketInput.trim())}/validate`);
      setTicketMsg(`Saída registrada: ${row?.visitorName ?? "visitante"} (ticket ${ticketInput.trim()}).`);
      setTicketInput("");
      await Promise.all([onSite.reload(), attempts.reload()]);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div aria-label="Visitantes">
      <div className="tabs subtabs" role="group" aria-label="Visão dos visitantes">
        <button aria-pressed={view === "now"} onClick={() => setView("now")}>Agora</button>
        <button aria-pressed={view === "history"} onClick={() => setView("history")}>Histórico</button>
      </div>
      {view === "now" && <>
      <h3>No local agora ({onSite.items.length})</h3>
      {error && <p role="alert" className="error">{error}</p>}
      {issuedTicket && (
        <p role="status" className="badge ok">Entrega o ticket nº {issuedTicket.ticket} a {issuedTicket.visitor}: ele é validado na saída.</p>
      )}
      <form onSubmit={validateTicket} aria-label="Validar ticket de saída">
        <label htmlFor="exit-ticket">Ticket de saída</label>
        <input id="exit-ticket" inputMode="numeric" pattern="[0-9]*" maxLength={9} value={ticketInput}
          onChange={(e) => setTicketInput(e.target.value)} />
        <button type="submit" disabled={!/^\d+$/.test(ticketInput.trim())}>Validar ticket</button>
      </form>
      {ticketMsg && <p role="status">{ticketMsg}</p>}
      <p className="hint">Quem entrou e ainda não saiu. Registre a saída quando a pessoa for embora; assim o número no resumo volta a refletir quem está no condomínio.</p>
      <ul>
        {onSite.items.map((r) => {
          const hours = (Date.now() - new Date(r.occurredAt).getTime()) / 3_600_000;
          return (
            <li key={r.attemptId}>
              {r.exitTicket != null && <strong>Ticket {r.exitTicket} · </strong>}{r.visitorName ?? "Visitante"}{r.unitLabel ? ` · unidade ${r.unitLabel}` : ""} · entrou {new Date(r.occurredAt).toLocaleTimeString()} · {r.entrance}{" "}
              {hours >= 12 && <span className="badge warn">no local há mais de 12 h: confirme</span>}{" "}
              <button aria-label={`Registrar saída de ${r.visitorName ?? "visitante"}`} onClick={() => registerExit(r.attemptId)}>Registrar saída</button>
            </li>
          );
        })}
        {onSite.items.length === 0 && <li>Ninguém no local.</li>}
      </ul>
      <h3>Avisar chegada de visita ou prestador</h3>
      <p className="hint">
        O morador recebe a notificação com o nome de quem chegou, na aba Visitas do app, e autoriza ou nega. Vale para visita de pessoa
        conhecida e para prestador de serviço. A resposta do morador não abre a porta: quem libera é a portaria.
      </p>
      <VisitRequestForm token={token} condominiumId={condominiumId} onAsked={() => requests.reload()} />
      <h4>Pedidos recentes</h4>
      <ul>
        {requests.items.map((r) => (
          <li key={r.id}>
            {r.visitorName} · {r.kind === "SERVICE_PROVIDER" ? "prestador" : "visita"} · unidade {unitNumber(r.unitId)} ·{" "}
            <span className={r.status === "APPROVED" ? "badge ok" : r.status === "PENDING" ? "badge pending" : "badge warn"}>
              {{ PENDING: "aguardando morador", APPROVED: "autorizado", DENIED: "negado", EXPIRED: "sem resposta" }[r.status] ?? r.status}
            </span>
            {r.status === "APPROVED" && !r.entered && (
              <button aria-label={`Registrar entrada de ${r.visitorName}`} onClick={() => registerEntry(r.id)}>Registrar entrada</button>
            )}
            {r.entered && <small> · entrada registrada</small>}
            {r.hasSnapshot && (
              <>
                {" "}<span className="badge ok">com imagem</span>{" "}
                <button onClick={() => setSeeing(seeing === r.id ? null : r.id)} aria-label={`Ver imagem de ${r.visitorName}`}>
                  {seeing === r.id ? "Ocultar imagem" : "Ver imagem"}
                </button>
                {seeing === r.id && <AuthImage token={token} path={`${base}/visit-requests/${r.id}/snapshot`} alt={`Imagem da entrada de ${r.visitorName}`} />}
              </>
            )}
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
      <MissingHint missing={[!token_.trim() && "o código do convite", !entrance.trim() && "a entrada"].filter(Boolean) as string[]} />
      <button disabled={!token_.trim() || !entrance.trim()} onClick={validate}>Validar</button>
      {verdict && (
        <p role="status" className={verdict.granted ? "badge ok" : "badge warn"}>
          {verdict.granted ? `Entrada liberada${verdict.visitorName ? `: ${verdict.visitorName}` : ""} (a porta não é aberta por esta ação)` : `Entrada negada (${verdict.reason})`}
        </p>
      )}
      </>}
      {view === "history" && <>
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
      </>}
    </div>
  );
}
