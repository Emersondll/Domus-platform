import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import { errorMessage } from "./useCondoList";

type Status = { configured: boolean; updatedAt: string | null };

/**
 * Usuário e senha da câmera, só de gravação: a API informa se existem, mas nunca devolve o valor. Os campos ficam vazios depois
 * de salvar, o navegador não os guarda (autocomplete desligado) e nada vai para localStorage. A senha é cifrada no servidor.
 */
export function CameraCredentials({ token, base, cameraId, cameraName }: { token: string; base: string; cameraId: string; cameraName: string }) {
  const url = `${base}/${cameraId}/credentials`;
  const [status, setStatus] = useState<Status | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let alive = true;
    request<Status>(token, "GET", url).then((s) => alive && setStatus(s)).catch((e) => alive && setError(errorMessage(e)));
    return () => { alive = false; };
  }, [token, url]);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      setStatus(await request<Status>(token, "PUT", url, { username: username.trim(), password }));
      setUsername("");
      setPassword("");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function clear() {
    setBusy(true);
    setError(null);
    try {
      setStatus(await request<Status>(token, "DELETE", url));
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label={`Credenciais de ${cameraName}`}>
      <h4>Usuário e senha da câmera</h4>
      <p role="status" className="hint">
        {status == null ? "Consultando…" : status.configured
          ? `Credenciais cadastradas (trocadas em ${status.updatedAt ? new Date(status.updatedAt).toLocaleString() : "—"}). A senha não pode ser consultada.`
          : "Nenhuma credencial cadastrada."}
      </p>
      <label htmlFor="cred-user">Usuário da câmera</label>
      <input id="cred-user" maxLength={64} autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} />
      <label htmlFor="cred-pass">Senha da câmera</label>
      <input id="cred-pass" type="password" maxLength={128} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
      <button disabled={busy || !username.trim() || !password} onClick={save}>{status?.configured ? "Trocar credenciais" : "Salvar credenciais"}</button>{" "}
      {status?.configured && <button disabled={busy} onClick={clear}>Remover credenciais</button>}
      <p className="hint">A senha é cifrada no servidor, vai só ao servidor de vídeo e nunca volta para esta tela. Trocar e remover ficam na auditoria.</p>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}
