import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Invitation, MyCall, Notification, Parcel } from "./types";
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

export function Home({ token, condo, onNavigate }: { token: string; condo: string; onNavigate: (tab: "Encomendas" | "Visitas") => void }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const notes = useList<Notification>(token, `${base}/notifications`, POLL_MS);
  const calls = useList<MyCall>(token, `${base}/calls`, POLL_MS);
  const parcels = useList<Parcel>(token, `${base}/parcels`, POLL_MS);
  const invitations = useList<Invitation>(token, `${base}/invitations`, POLL_MS);
  const [msg, setMsg] = useState<string | null>(null);

  const latest = calls.items[0];
  const progress = latest ? callProgress(latest) : null;
  const pendingParcels = parcels.items.filter((p) => p.status === "RECEIVED").length;
  const expected = invitations.items.filter((i) => isExpected(i)).length;
  const callActive = latest !== undefined && !progress?.done;

  async function callCentral() {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/calls`, { callerName: null });
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

      <button onClick={callCentral} disabled={callActive}>Chamar a central</button>
      {msg && <p role="alert" className="error">{msg}</p>}
      {latest && progress && (
        <p role="status" className={progress.done ? "call-status done" : "call-status"}>
          <strong>Sua chamada · {new Date(latest.createdAt).toLocaleTimeString()}</strong> {progress.label}
        </p>
      )}

      <h3>Notificações</h3>
      <ul>
        {notes.items.map((n) => (
          <li key={n.id} className={n.readAt ? "" : "unread"}>
            <strong>{n.title}</strong> {n.body} <small>{new Date(n.createdAt).toLocaleString()}</small>{" "}
            {!n.readAt && <button onClick={() => markRead(n.id)}>Marcar como lida</button>}
          </li>
        ))}
        {notes.items.length === 0 && <li>Nada por aqui.</li>}
      </ul>
      <p className="hint">Notificações push, e-mail e WhatsApp ainda não estão integrados; por ora os avisos aparecem aqui e a tela se atualiza sozinha a cada poucos segundos.</p>
    </section>
  );
}
