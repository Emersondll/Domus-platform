import { useState } from "react";
import { devLogin } from "../../shared/src/api";

const DEV_USERS = ["operator-sol", "operator-multi", "manager-sol", "alpha-admin"];

/** Login de desenvolvimento. Em produção este componente é substituído pelo fluxo OIDC (Keycloak) com MFA. */
export function Login({ onLogin }: { onLogin: (token: string, subject: string) => void }) {
  const [subject, setSubject] = useState(DEV_USERS[0]);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    try {
      onLogin(await devLogin(subject), subject);
    } catch {
      setError("Dev login unavailable. Is the API running with the dev profile?");
    }
  }

  return (
    <main className="login">
      <h1>Central de Portaria</h1>
      <p className="badge warn">DEV login — não existe em produção (Keycloak + MFA pendente)</p>
      <form onSubmit={submit}>
        <label htmlFor="subject">Usuário de teste</label>
        <select id="subject" value={subject} onChange={(e) => setSubject(e.target.value)}>
          {DEV_USERS.map((u) => (
            <option key={u}>{u}</option>
          ))}
        </select>
        <button type="submit">Entrar</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
    </main>
  );
}
