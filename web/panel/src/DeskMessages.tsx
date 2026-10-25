import { useCallback, useEffect, useState } from "react";
import { request, type Page } from "../../shared/src/api";
import type { DirectMessage, MessageThread } from "./types";
import { errorMessage } from "./useCondoList";

/** Caixa de mensagens individuais dos moradores com a portaria. */
export function DeskMessages({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/messages/threads`;
  const [threads, setThreads] = useState<MessageThread[]>([]);
  const [open, setOpen] = useState<MessageThread | null>(null);
  const [messages, setMessages] = useState<DirectMessage[]>([]);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  const loadThreads = useCallback(async () => {
    try {
      setThreads((await request<MessageThread[]>(token, "GET", base)) ?? []);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [token, base]);

  const loadThread = useCallback(async (t: MessageThread) => {
    try {
      setMessages((await request<Page<DirectMessage>>(token, "GET", `${base}/${t.residentUserId}`))?.items ?? []);
      await loadThreads();
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [token, base, loadThreads]);

  useEffect(() => {
    loadThreads();
    const id = setInterval(loadThreads, 10000);
    return () => clearInterval(id);
  }, [loadThreads]);

  async function reply() {
    if (!open) return;
    setError(null);
    try {
      await request(token, "POST", `${base}/${open.residentUserId}`, { text: text.trim() });
      setText("");
      await loadThread(open);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Mensagens">
      <h3>Conversas</h3>
      <ul>
        {threads.map((t) => (
          <li key={t.residentUserId}>
            <button onClick={() => { setOpen(t); loadThread(t); }}>{t.residentName}</button>{" "}
            {t.unread > 0 && <span className="badge pending">{t.unread} nova(s)</span>}{" "}
            <small>{new Date(t.lastAt).toLocaleString()}</small>
          </li>
        ))}
        {threads.length === 0 && <li>Nenhuma conversa.</li>}
      </ul>
      {open && (
        <section aria-label={`Conversa com ${open.residentName}`}>
          <h3>{open.residentName}</h3>
          <ul>
            {[...messages].reverse().map((m) => (
              <li key={m.id}>
                <strong>{m.fromResident ? open.residentName : "Portaria"}</strong>{" "}
                <small>{new Date(m.createdAt).toLocaleString()}</small>
                <p>{m.text}</p>
              </li>
            ))}
          </ul>
          <label htmlFor="reply-text">Resposta</label>
          <textarea id="reply-text" maxLength={1000} rows={3} value={text} onChange={(e) => setText(e.target.value)} />
          <button disabled={!text.trim()} onClick={reply}>Responder</button>
        </section>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
