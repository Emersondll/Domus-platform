import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Announcement } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

/** Comunicados para todos os moradores do condomínio (chegam como aviso no app). */
export function DeskAnnouncements({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/announcements`;
  const list = useCondoList<Announcement>(token, `${base}?size=30`, 15000);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  async function publish() {
    setError(null);
    setSent(false);
    try {
      await request(token, "POST", base, { title: title.trim(), body: body.trim() });
      setTitle("");
      setBody("");
      setSent(true);
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Comunicados">
      <h3>Novo comunicado</h3>
      <label htmlFor="ann-title">Título</label>
      <input id="ann-title" maxLength={120} value={title} onChange={(e) => setTitle(e.target.value)} />
      <label htmlFor="ann-body">Mensagem</label>
      <textarea id="ann-body" maxLength={2000} rows={4} value={body} onChange={(e) => setBody(e.target.value)} />
      <button disabled={!title.trim() || !body.trim()} onClick={publish}>Publicar para todos os moradores</button>
      {sent && <p role="status">Comunicado publicado.</p>}
      {error && <p role="alert" className="error">{error}</p>}
      <h3>Publicados</h3>
      <ul>
        {list.items.map((a) => (
          <li key={a.id}>
            <strong>{a.title}</strong> <small>{new Date(a.createdAt).toLocaleString()}</small>
            <p>{a.body}</p>
          </li>
        ))}
        {list.items.length === 0 && <li>Nenhum comunicado.</li>}
      </ul>
    </div>
  );
}
