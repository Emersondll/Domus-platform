import { useState } from "react";
import type { Member } from "./types";
import { ApiError, request } from "../../shared/src/api";
import { errorMessage } from "./useCondoList";
import { TemporaryPassword, useProvisioningEnabled } from "./provisioning";

const ADMIN_ONLY = "Só o administrador da empresa operadora altera a equipe.";
const staffError = (e: unknown) => (e instanceof ApiError && e.status === 403 ? ADMIN_ONLY : errorMessage(e));

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
  const provisioning = useProvisioningEnabled(token);
  const [manual, setManual] = useState(false);
  const [created, setCreated] = useState<{ email: string; password: string } | null>(null);
  const direct = provisioning && !manual;

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
            {m.displayName || m.userId.slice(0, 8)} · {ROLE_TEXT[m.role] ?? m.role}{" "}
            <button aria-label={`Revogar ${m.displayName || m.userId.slice(0, 8)}`} onClick={async () => {
              try {
                await request(token, "POST", `${base}/${m.id}/revoke`);
                await load();
              } catch (e) {
                setError(staffError(e));
              }
            }}>Revogar acesso</button>
          </li>
        ))}
        {members && members.filter((m) => m.role !== "RESIDENT").length === 0 && <li>Ninguém na equipe.</li>}
      </ul>
      <h3>Adicionar à equipe</h3>
      {direct ? (
        <p className="hint">A conta é criada no provedor de login já com o papel que obriga o código do autenticador (OTP) e a pessoa recebe uma senha provisória. Só o administrador da empresa operadora adiciona ou revoga equipe; o síndico apenas consulta.</p>
      ) : (
        <p className="hint">Informe o identificador do usuário no provedor de login (Keycloak). <strong>Dê também a essa pessoa o papel correspondente no Keycloak</strong> (operator ou condo-manager): é ele que obriga o código do autenticador (OTP) no login, e sem o papel a API recusa o acesso. Só o administrador da empresa operadora adiciona ou revoga equipe; o síndico apenas consulta.</p>
      )}
      <label htmlFor="staff-role">Papel</label>
      <select id="staff-role" value={role} onChange={(e) => setRole(e.target.value)}>
        <option value="OPERATOR">Operador</option>
        <option value="CONDO_MANAGER">Síndico</option>
      </select>
      {!direct && (
        <>
          <label htmlFor="staff-subject">Identificador do usuário</label>
          <input id="staff-subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
        </>
      )}
      <label htmlFor="staff-email">E-mail</label>
      <input id="staff-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      <label htmlFor="staff-name">Nome</label>
      <input id="staff-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
      <button disabled={(!direct && !subject.trim()) || !email.trim() || !displayName.trim()} onClick={async () => {
        setError(null);
        setCreated(null);
        try {
          if (direct) {
            const r = await request<{ temporaryPassword: string }>(token, "POST", `${base}/provision`,
              { role, account: { email: email.trim(), displayName: displayName.trim() } });
            if (r) setCreated({ email: email.trim(), password: r.temporaryPassword });
          } else {
            await request(token, "POST", base, { role, account: { externalSubject: subject.trim(), email: email.trim(), displayName: displayName.trim() } });
          }
          setSubject("");
          setEmail("");
          setDisplayName("");
          await load();
        } catch (e) {
          setError(staffError(e));
        }
      }}>{direct ? "Criar conta e adicionar" : "Adicionar"}</button>
      {provisioning && (
        <button className="link" onClick={() => setManual(!manual)}>
          {manual ? "Criar a conta pela tela" : "A conta já existe no Keycloak: vincular pelo identificador"}
        </button>
      )}
      {created && <TemporaryPassword email={created.email} password={created.password} onClose={() => setCreated(null)} />}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
