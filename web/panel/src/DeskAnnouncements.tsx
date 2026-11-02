import { MissingHint } from "./MissingHint";
import { useEffect, useState } from "react";
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
  // O servidor decide; a tela só esconde a ação para quem ele recusaria (síndico ou administração).
  const [canManage, setCanManage] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ id: string; title: string; body: string } | null>(null);
  useEffect(() => {
    request<{ canManage: boolean }>(token, "GET", `/api/v1/condominiums/${condominiumId}/devices/permissions`)
      .then((p) => setCanManage(p?.canManage === true)).catch(() => setCanManage(false));
  }, [token, condominiumId]);

  async function remove(id: string) {
    setError(null);
    try {
      await request(token, "DELETE", `${base}/${id}`);
      setRemoving(null);
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function saveEdit() {
    if (!editing) return;
    setError(null);
    try {
      await request(token, "PUT", `${base}/${editing.id}`, { title: editing.title.trim(), body: editing.body.trim() });
      setEditing(null);
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

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
      <MissingHint missing={[!title.trim() && "o título", !body.trim() && "a mensagem"].filter(Boolean) as string[]} />
      <button disabled={!title.trim() || !body.trim()} onClick={publish}>Publicar para todos os moradores</button>
      {sent && <p role="status">Comunicado publicado.</p>}
      {error && <p role="alert" className="error">{error}</p>}
      <h3>Publicados</h3>
      <ul>
        {list.items.map((a) => (
          <li key={a.id}>
            {editing?.id === a.id ? (
              <div role="group" aria-label={`Corrigir comunicado ${a.title}`}>
                <label htmlFor={`ann-edit-title-${a.id}`}>Título</label>
                <input id={`ann-edit-title-${a.id}`} maxLength={120} value={editing.title}
                  onChange={(e) => setEditing({ ...editing, title: e.target.value })} />
                <label htmlFor={`ann-edit-body-${a.id}`}>Mensagem</label>
                <textarea id={`ann-edit-body-${a.id}`} maxLength={2000} rows={4} value={editing.body}
                  onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
                <p className="hint">Corrige o texto na lista. Quem já recebeu o aviso continua com o texto antigo, e ninguém é avisado de novo.</p>
                <button disabled={!editing.title.trim() || !editing.body.trim()} onClick={saveEdit}>Salvar correção</button>{" "}
                <button onClick={() => setEditing(null)}>Cancelar</button>
              </div>
            ) : (
              <>
                <strong>{a.title}</strong> <small>{new Date(a.createdAt).toLocaleString()}</small>
                <p>{a.body}</p>
                {canManage && removing !== a.id && (
                  <button aria-label={`Corrigir comunicado ${a.title}`} onClick={() => setEditing({ id: a.id, title: a.title, body: a.body })}>Corrigir</button>
                )}{" "}
              </>
            )}
            {canManage && !editing && removing !== a.id && <button aria-label={`Apagar comunicado ${a.title}`} onClick={() => setRemoving(a.id)}>Apagar</button>}
            {removing === a.id && (
              <>
                <span>Apagar este comunicado? Quem já recebeu o aviso continua com ele. </span>
                <button onClick={() => remove(a.id)}>Confirmar</button>{" "}
                <button onClick={() => setRemoving(null)}>Cancelar</button>
              </>
            )}
          </li>
        ))}
        {list.items.length === 0 && <li>Nenhum comunicado.</li>}
      </ul>
    </div>
  );
}
