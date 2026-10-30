import { useCallback, useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import { errorText } from "./useApi";

type MyRequest = { id: string; condominiumName: string; status: "PENDING" | "APPROVED" | "REJECTED"; createdAt: string };

const STATUS_TEXT: Record<MyRequest["status"], string> = {
  PENDING: "aguardando o síndico", APPROVED: "aprovado", REJECTED: "recusado",
};

/** Quem ainda não tem vínculo pede para entrar no condomínio com o código dado pelo síndico; o síndico aprova. */
export function JoinCondominium({ token, onApproved }: { token: string; onApproved: () => void }) {
  const [mine, setMine] = useState<MyRequest[]>([]);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [type, setType] = useState("OWNER");
  const [unitText, setUnitText] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setMine((await request<MyRequest[]>(token, "GET", "/api/v1/me/association-requests")) ?? []);
    } catch {
      setMine([]);
    }
  }, [token]);
  useEffect(() => { void reload(); }, [reload]);

  const pending = mine.some((r) => r.status === "PENDING");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setMessage(null);
    try {
      await request(token, "POST", "/api/v1/association-requests",
        { code: code.trim(), fullName: name.trim(), email: email.trim(), phone: phone.trim() || null, type, unitText: unitText.trim() });
      setMessage("Pedido enviado. Assim que o síndico aprovar, o acesso é liberado.");
      setCode("");
      await reload();
    } catch (err) {
      setError(errorText(err));
    }
  }

  return (
    <section aria-label="Pedir acesso ao condomínio">
      <h2>Pedir acesso ao condomínio</h2>
      <p className="hint">Peça o código de adesão ao síndico. O pedido só vira acesso depois que ele aprovar.</p>
      {mine.length > 0 && (
        <ul aria-label="Meus pedidos">
          {mine.map((r) => (
            <li key={r.id}>{r.condominiumName} · {STATUS_TEXT[r.status]} · {new Date(r.createdAt).toLocaleDateString()}</li>
          ))}
        </ul>
      )}
      {mine.some((r) => r.status === "APPROVED") && <button onClick={onApproved}>Já fui aprovado: entrar</button>}
      {!pending && (
        <form onSubmit={submit}>
          <label htmlFor="join-code">Código de adesão</label>
          <input id="join-code" maxLength={20} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off" />
          <label htmlFor="join-name">Nome completo</label>
          <input id="join-name" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} />
          <label htmlFor="join-email">E-mail</label>
          <input id="join-email" type="email" maxLength={190} value={email} onChange={(e) => setEmail(e.target.value)} />
          <label htmlFor="join-phone">Telefone (opcional)</label>
          <input id="join-phone" maxLength={30} value={phone} onChange={(e) => setPhone(e.target.value)} />
          <label htmlFor="join-type">Vínculo</label>
          <select id="join-type" value={type} onChange={(e) => setType(e.target.value)}>
            <option value="OWNER">Proprietário</option>
            <option value="TENANT">Locatário</option>
          </select>
          <label htmlFor="join-unit">Unidade (ex.: Bloco A, 101)</label>
          <input id="join-unit" maxLength={60} value={unitText} onChange={(e) => setUnitText(e.target.value)} />
          <button type="submit" disabled={!code.trim() || !name.trim() || !email.trim() || !unitText.trim()}>Enviar pedido</button>
        </form>
      )}
      {message && <p role="status">{message}</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}
