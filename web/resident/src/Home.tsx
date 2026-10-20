import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Notification } from "./types";
import { errorText, useList } from "./useApi";

export function Home({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const { items, reload } = useList<Notification>(token, `${base}/notifications`);
  const [msg, setMsg] = useState<string | null>(null);

  async function callCentral() {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/calls`, { callerName: null });
      setMsg("Pedido enviado. Um operador da central irá atender.");
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function markRead(id: string) {
    await request(token, "POST", `${base}/notifications/${id}/read`);
    reload();
  }

  return (
    <section aria-label="Início">
      <h2>Início</h2>
      <button onClick={callCentral}>Chamar a central</button>
      {msg && <p role="status">{msg}</p>}
      <h3>Notificações</h3>
      <ul>
        {items.map((n) => (
          <li key={n.id} className={n.readAt ? "" : "unread"}>
            <strong>{n.title}</strong> {n.body} <small>{new Date(n.createdAt).toLocaleString()}</small>{" "}
            {!n.readAt && <button onClick={() => markRead(n.id)}>Marcar como lida</button>}
          </li>
        ))}
        {items.length === 0 && <li>Nada por aqui.</li>}
      </ul>
      <p className="hint">Notificações push, e-mail e WhatsApp ainda não estão integrados; por ora os avisos aparecem aqui.</p>
    </section>
  );
}
