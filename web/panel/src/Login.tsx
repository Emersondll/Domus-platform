import { useState } from "react";
import { devLogin } from "../../shared/src/api";
import { startLogin, type OidcConfig } from "../../shared/src/oidc";

const DEV_USERS = ["operator-sol", "operator-multi", "manager-sol", "alpha-admin", "platform-admin"];

/**
 * Tela de entrada. Com OIDC configurado (/config.json do proxy) o login é o do Keycloak (MFA obrigatório para equipe).
 * O login de desenvolvimento só existe em build de desenvolvimento e nunca em produção.
 */
export function Login({ oidc, authError, onLogin }: { oidc: OidcConfig | null; authError: string | null;
  onLogin: (token: string, subject: string) => void }) {
  const [subject, setSubject] = useState(DEV_USERS[0]);
  const [error, setError] = useState<string | null>(authError);

  async function submitDev(e: React.FormEvent) {
    e.preventDefault();
    try {
      onLogin(await devLogin(subject), subject);
    } catch {
      setError("Dev login unavailable. Is the API running with the dev profile?");
    }
  }

  return (
    <main className="login">
      <h1>Domus Central</h1>
      {oidc ? (
        <button onClick={() => startLogin(oidc)}>Entrar</button>
      ) : import.meta.env.DEV ? (
        <>
          <p className="badge warn">DEV login — só em desenvolvimento; em produção o login é do Keycloak</p>
          <form onSubmit={submitDev}>
            <label htmlFor="subject">Usuário de teste</label>
            <select id="subject" value={subject} onChange={(e) => setSubject(e.target.value)}>
              {DEV_USERS.map((u) => (
                <option key={u}>{u}</option>
              ))}
            </select>
            <button type="submit">Entrar</button>
          </form>
        </>
      ) : (
        <p role="alert" className="error">Login não configurado (/config.json ausente).</p>
      )}
      {error && <p role="alert" className="error">{error}</p>}
    </main>
  );
}
