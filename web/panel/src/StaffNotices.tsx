import { useState } from "react";
import { request } from "../../shared/src/api";
import { errorMessage, useCondoList } from "./useCondoList";

type StaffNotice = { id: string; type: string; title: string; body: string | null; createdAt: string; readAt: string | null };

/** Caixa de avisos da equipe do condomínio (por exemplo, uma chamada devolvida à fila por falta de sinal do operador). */
export function StaffNotices({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/staff/notifications`;
  const list = useCondoList<StaffNotice>(token, `${base}?size=20`, 15000);
  const [error, setError] = useState<string | null>(null);
  const unread = list.items.filter((n) => !n.readAt);
  if (unread.length === 0 && !error) return null;

  async function markRead(id: string) {
    setError(null);
    try {
      await request(token, "POST", `${base}/${id}/read`);
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <section aria-label="Avisos da equipe" className="staff-notices">
      <h3>Avisos da equipe ({unread.length})</h3>
      <ul>
        {unread.map((n) => (
          <li key={n.id}>
            <strong>{n.title}</strong> <small>{new Date(n.createdAt).toLocaleString()}</small>
            {n.body && <p>{n.body}</p>}
            <button aria-label={`Marcar como lido: ${n.title}`} onClick={() => void markRead(n.id)}>Marcar como lido</button>
          </li>
        ))}
      </ul>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}
