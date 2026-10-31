import type { Announcement } from "./types";
import { useList } from "./useApi";

/** Comunicados da portaria e do síndico para todos os moradores. */
export function Notices({ token, condo }: { token: string; condo: string }) {
  const { items } = useList<Announcement>(token, `/api/v1/condominiums/${condo}/me/announcements`, 15000);
  return (
    <section aria-label="Avisos">
      <h2>Avisos do condomínio</h2>
      <ul>
        {items.map((a) => (
          <li key={a.id}>
            <strong>{a.title}</strong> <small>{new Date(a.createdAt).toLocaleString()}</small>
            <p>{a.body}</p>
          </li>
        ))}
        {items.length === 0 && <li className="empty">Nenhum aviso publicado.</li>}
      </ul>
    </section>
  );
}
