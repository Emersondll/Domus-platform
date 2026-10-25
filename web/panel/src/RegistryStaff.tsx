import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Member } from "./types";
import { errorMessage } from "./useCondoList";

const ROLE_TEXT: Record<string, string> = { OPERATOR: "operador", CONDO_MANAGER: "síndico", RESIDENT: "morador" };

/** Equipe do condomínio: operadores e síndico. */
export function RegistryStaff({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/members`;
  const [members, setMembers] = useState<Member[] | null>(null);
  const [role, setRole] = useState("OPERATOR");
  const [subject, setSubject] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      setMembers((await request<Member[]>(token, "GET", base)) ?? []);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Equipe">
      <h3>Equipe do condomínio</h3>
      <button onClick={load}>Carregar equipe</button>
      <ul>
        {(members ?? []).filter((m) => m.role !== "RESIDENT").map((m) => (
          <li key={m.id}>
            {ROLE_TEXT[m.role] ?? m.role} · {m.userId.slice(0, 8)}{" "}
            <button aria-label={`Revogar ${m.userId.slice(0, 8)}`} onClick={async () => {
              try {
                await request(token, "POST", `${base}/${m.id}/revoke`);
                await load();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}>Revogar acesso</button>
          </li>
        ))}
        {members && members.filter((m) => m.role !== "RESIDENT").length === 0 && <li>Ninguém na equipe.</li>}
      </ul>
      <h3>Adicionar à equipe</h3>
      <p className="hint">Informe o identificador do usuário no provedor de login (Keycloak). Só o síndico ou a administração faz isso.</p>
      <label htmlFor="staff-role">Papel</label>
      <select id="staff-role" value={role} onChange={(e) => setRole(e.target.value)}>
        <option value="OPERATOR">Operador</option>
        <option value="CONDO_MANAGER">Síndico</option>
      </select>
      <label htmlFor="staff-subject">Identificador do usuário</label>
      <input id="staff-subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
      <label htmlFor="staff-email">E-mail</label>
      <input id="staff-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      <label htmlFor="staff-name">Nome</label>
      <input id="staff-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
      <button disabled={!subject.trim() || !email.trim() || !displayName.trim()} onClick={async () => {
        setError(null);
        try {
          await request(token, "POST", base, { role, account: { externalSubject: subject.trim(), email: email.trim(), displayName: displayName.trim() } });
          setSubject("");
          setEmail("");
          setDisplayName("");
          await load();
        } catch (e) {
          setError(errorMessage(e));
        }
      }}>Adicionar</button>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
