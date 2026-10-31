import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import { AuthImage } from "../../shared/src/AuthImage";
import type { Invitation, MyAccess, MyCall, Notification, Parcel, VisitRequest } from "./types";
import { NotificationTabs } from "./NotificationTabs";
import { DIRECTION_LABEL } from "./MyAccesses";
import { RemoteDoors } from "./RemoteDoors";
import { errorText, useList } from "./useApi";

const POLL_MS = 5000;

const OUTCOME_TEXT: Record<string, string> = {
  RESOLVED: "Atendimento concluído.",
  ENTRY_GRANTED: "Entrada liberada pela portaria.",
  ENTRY_DENIED: "Entrada não autorizada pela portaria.",
  NO_ANSWER: "A portaria não conseguiu contato.",
  FALSE_ALARM: "Chamado encerrado sem ocorrência.",
};

/** Texto de andamento da chamada mais recente da unidade. */
export function callProgress(call: MyCall): { label: string; done: boolean } {
  if (call.status === "QUEUED") return { label: "Aguardando um operador da central…", done: false };
  if (call.status === "ASSIGNED") return { label: "Em atendimento pela central.", done: false };
  return { label: OUTCOME_TEXT[call.outcome ?? ""] ?? "Chamada encerrada.", done: true };
}

export function isExpected(i: Invitation, now = Date.now()): boolean {
  return i.status === "ACTIVE" && i.useCount < i.maxUses && new Date(i.validUntil).getTime() > now;
}

export function Home({ token, condo, onNavigate }: { token: string; condo: string; onNavigate: (tab: "Encomendas" | "Visitas" | "Meus acessos") => void }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const notes = useList<Notification>(token, `${base}/notifications`, POLL_MS);
  const calls = useList<MyCall>(token, `${base}/calls`, POLL_MS);
  const parcels = useList<Parcel>(token, `${base}/parcels?status=RECEIVED&size=100`, POLL_MS);
  const invitations = useList<Invitation>(token, `${base}/invitations`, POLL_MS);
  const visitRequests = useList<VisitRequest>(token, `${base}/visit-requests`, POLL_MS);
  const [msg, setMsg] = useState<string | null>(null);
  const [confirmingPanic, setConfirmingPanic] = useState(false);

  // Uma só consulta ao abrir a tela (sem polling): cada consulta ao histórico pessoal fica na auditoria.
  const [lastAccess, setLastAccess] = useState<MyAccess | null | undefined>(undefined);
  useEffect(() => {
    request<{ last: MyAccess | null }>(token, "GET", `${base}/accesses/last`)
      .then((r) => setLastAccess(r?.last ?? null))
      .catch(() => setLastAccess(null));
  }, [token, base]);

  const latest = calls.items[0];
  const progress = latest ? callProgress(latest) : null;
  const pendingParcels = parcels.items.filter((p) => p.status === "RECEIVED").length;
  const expected = invitations.items.filter((i) => isExpected(i)).length;
  const callActive = latest !== undefined && !progress?.done;
  const panicActive = callActive && latest.source === "PANIC";
  const waiting = visitRequests.items.filter((r) => r.status === "PENDING");

  async function callCentral() {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/calls`, { callerName: null });
      calls.reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function answer(id: string, decision: "approve" | "deny") {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/visit-requests/${id}/${decision}`);
      visitRequests.reload();
    } catch (err) {
      setMsg(errorText(err));
      visitRequests.reload();
    }
  }

  async function panic() {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/panic`);
      setConfirmingPanic(false);
      calls.reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function markRead(id: string) {
    await request(token, "POST", `${base}/notifications/${id}/read`);
    notes.reload();
  }

  return (
    <section aria-label="Início">
      <h2>Início</h2>
      {waiting.map((r) => (
        <div key={r.id} className="visit-request" role="alert">
          <strong>{r.visitorName}</strong> {r.kind === "SERVICE_PROVIDER" ? "(prestador) " : ""}está na portaria e aguarda a sua autorização.
          {r.hasSnapshot ? (
            <figure>
              <AuthImage token={token} path={`${base}/visit-requests/${r.id}/snapshot`} alt={`Imagem da entrada: ${r.visitorName}`} />
              <figcaption><small>Imagem da câmera da portaria, apagada depois do prazo de retenção.</small></figcaption>
            </figure>
          ) : (
            <small>Sem imagem da entrada.</small>
          )}
          <div className="actions">
            <button onClick={() => answer(r.id, "approve")}>Autorizar</button>
            <button className="danger" onClick={() => answer(r.id, "deny")}>Negar</button>
          </div>
          <small>Autorizar avisa a portaria; quem libera a entrada é o operador.</small>
        </div>
      ))}
      <div className="summary">
        <button className="card" onClick={() => onNavigate("Encomendas")} aria-label="Encomendas a retirar">
          <strong>{pendingParcels}</strong>
          <span>{pendingParcels === 1 ? "encomenda a retirar" : "encomendas a retirar"}</span>
        </button>
        <button className="card" onClick={() => onNavigate("Visitas")} aria-label="Visitas esperadas">
          <strong>{expected}</strong>
          <span>{expected === 1 ? "visita esperada" : "visitas esperadas"}</span>
        </button>
      </div>

      <button className="card" onClick={() => onNavigate("Meus acessos")} aria-label="Último acesso">
        <strong>Último acesso</strong>
        <span>
          {lastAccess === undefined ? <span className="skeleton line" role="status" aria-label="Carregando último acesso" /> : lastAccess === null ? "Ainda sem registros" :
            `${DIRECTION_LABEL[lastAccess.direction]} · ${new Date(lastAccess.occurredAt).toLocaleString([], { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" })} · ${lastAccess.entrance}`}
        </span>
      </button>

      <button className="call-action" onClick={callCentral} disabled={callActive}>Chamar a central</button>
      {confirmingPanic ? (
        <div className="panic-confirm" role="group" aria-label="Confirmar alerta de pânico">
          <p><strong>Enviar alerta de pânico para a central?</strong> Use apenas em emergência.</p>
          <button className="danger" onClick={panic}>Confirmar pânico</button>
          <button onClick={() => setConfirmingPanic(false)}>Cancelar</button>
        </div>
      ) : (
        <button className="danger panic-action" onClick={() => setConfirmingPanic(true)} disabled={panicActive}>Alerta de pânico</button>
      )}
      <RemoteDoors token={token} condo={condo} />
      {msg && <p role="alert" className="error">{msg}</p>}
      {latest && progress && (
        <p role="status" className={progress.done ? "call-status done" : "call-status"}>
          <strong>{latest.source === "PANIC" ? "Seu alerta de pânico" : "Sua chamada"} · {new Date(latest.createdAt).toLocaleTimeString()}</strong> {progress.label}
        </p>
      )}

      <h3>Notificações</h3>
      <NotificationTabs items={notes.items} onRead={markRead} />
      <p className="hint">Notificações push, e-mail e WhatsApp ainda não estão integrados; por ora os avisos aparecem aqui e a tela se atualiza sozinha a cada poucos segundos.</p>
    </section>
  );
}
