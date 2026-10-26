import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import { errorMessage } from "./useCondoList";

type Identity = { id: string; kind: string; active: boolean; createdAt: string; revokedAt: string | null };

const KIND_TEXT: Record<string, string> = { CARD: "cartão", QR: "QR Code", TERMINAL_USER: "usuário do terminal", APP: "app" };

/**
 * Meios de acesso de um morador (cartão, usuário do terminal...). A referência é guardada cifrada e nunca volta para a tela:
 * quem cadastra a digita uma vez. O histórico de entradas e saídas é só do morador; aqui não há nenhum acesso a ele.
 */
export function RegistryAccessIdentities({ token, condominiumId, resident }: { token: string; condominiumId: string; resident: { id: string; fullName: string } }) {
  const base = `/api/v1/condominiums/${condominiumId}/residents/${resident.id}`;
  const [items, setItems] = useState<Identity[]>([]);
  const [hasPhoto, setHasPhoto] = useState<boolean | null>(null);
  const [kind, setKind] = useState("CARD");
  const [ref, setRef] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function load() {
    try {
      setItems((await request<Identity[]>(token, "GET", `${base}/access-identities`)) ?? []);
      setHasPhoto((await request<{ hasPhoto: boolean }>(token, "GET", `${base}/photo-status`))?.hasPhoto ?? false);
    } catch (e) {
      setError(errorMessage(e));
    }
  }
  useEffect(() => { load(); }, [base, token]);

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <section aria-label={`Meios de acesso de ${resident.fullName}`}>
      <h3>Meios de acesso de {resident.fullName}</h3>
      <p className="hint">
        Foto de cadastro: {hasPhoto === null ? "…" : hasPhoto ? "cadastrada" : "não cadastrada"} (a imagem é vista só pelo próprio morador).
        O morador só passa a ter “Meus acessos” depois que um meio é cadastrado aqui.
      </p>
      <ul>
        {items.map((i) => (
          <li key={i.id}>
            {KIND_TEXT[i.kind] ?? i.kind} · desde {new Date(i.createdAt).toLocaleDateString()}{" "}
            {i.active ? <button onClick={() => run(() => request(token, "POST", `${base}/access-identities/${i.id}/revoke`))}
              aria-label={`Revogar ${KIND_TEXT[i.kind] ?? i.kind} de ${resident.fullName}`}>Revogar</button> : <span className="badge warn">revogado</span>}
          </li>
        ))}
        {items.length === 0 && <li>Nenhum meio de acesso cadastrado.</li>}
      </ul>
      <label htmlFor="ai-kind">Meio</label>
      <select id="ai-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
        {Object.entries(KIND_TEXT).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
      </select>
      <label htmlFor="ai-ref">Número ou referência no equipamento</label>
      <input id="ai-ref" maxLength={120} value={ref} onChange={(e) => setRef(e.target.value)} autoComplete="off" />
      <button disabled={!ref.trim()} onClick={() => run(async () => {
        await request(token, "POST", `${base}/access-identities`, { kind, ref: ref.trim() });
        setRef("");
      })}>Cadastrar meio de acesso</button>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}
