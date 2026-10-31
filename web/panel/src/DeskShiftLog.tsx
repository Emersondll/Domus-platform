import { MissingHint } from "./MissingHint";
import { useState } from "react";
import { request } from "../../shared/src/api";
import type { ShiftLogEntry } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

/** Livro de turno do condomínio: registros da portaria e passagens de turno. */
export function DeskShiftLog({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/shift-log`;
  const list = useCondoList<ShiftLogEntry>(token, `${base}?size=30`, 15000);
  const [kind, setKind] = useState("NOTE");
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function write() {
    setError(null);
    try {
      await request(token, "POST", base, { kind, text: text.trim() });
      setText("");
      await list.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Livro de turno">
      <h3>Novo registro</h3>
      <label htmlFor="log-kind">Tipo</label>
      <select id="log-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
        <option value="NOTE">Registro</option>
        <option value="HANDOVER">Passagem de turno</option>
      </select>
      <label htmlFor="log-text">Texto</label>
      <textarea id="log-text" maxLength={1000} rows={3} value={text} onChange={(e) => setText(e.target.value)} />
      <p className="hint">Registre fatos. Evite dados pessoais além do necessário: o texto fica cifrado, mas é lido por quem opera o condomínio.</p>
      <MissingHint missing={text.trim() ? [] : ["o texto"]} />
      <button disabled={!text.trim()} onClick={write}>Registrar</button>
      {error && <p role="alert" className="error">{error}</p>}
      <h3>Histórico</h3>
      <ul>
        {list.items.map((e) => (
          <li key={e.id}>
            <span className={e.kind === "HANDOVER" ? "badge pending" : "badge ok"}>{e.kind === "HANDOVER" ? "passagem" : "registro"}</span>{" "}
            <small>{new Date(e.createdAt).toLocaleString()}</small>
            <p>{e.text}</p>
          </li>
        ))}
        {list.items.length === 0 && <li>Nenhum registro.</li>}
      </ul>
    </div>
  );
}
