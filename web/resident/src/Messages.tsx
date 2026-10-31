import { useState } from "react";
import { ApiError, request } from "../../shared/src/api";
import type { DirectMessage } from "./types";
import { errorText, useList } from "./useApi";

/** Conversa individual do morador com a portaria. */
export function Messages({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me/messages`;
  const { items, reload } = useList<DirectMessage>(token, base, 8000);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function send() {
    setError(null);
    try {
      await request(token, "POST", base, { text: text.trim() });
      setText("");
      await reload();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 429
        ? "Muitas mensagens seguidas. Tente de novo em um minuto."
        : errorText(e));
    }
  }

  return (
    <section aria-label="Mensagens">
      <h2>Mensagens com a portaria</h2>
      <p className="hint">Não envie senhas nem dados de pagamento. Em emergência, use o botão de pânico na tela inicial.</p>
      <ul>
        {[...items].reverse().map((m) => (
          <li key={m.id}>
            <strong>{m.fromResident ? "Você" : "Portaria"}</strong>{" "}
            <small>{new Date(m.createdAt).toLocaleString()}</small>
            <p>{m.text}</p>
          </li>
        ))}
        {items.length === 0 && <li className="empty">Nenhuma mensagem ainda.</li>}
      </ul>
      <label htmlFor="msg-text">Nova mensagem</label>
      <textarea id="msg-text" maxLength={1000} rows={3} value={text} onChange={(e) => setText(e.target.value)} />
      <button disabled={!text.trim()} onClick={send}>Enviar</button>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}
