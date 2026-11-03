import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";

/** Pergunta uma vez se esta instalação cria as contas direto no provedor de login (senão a gestão usa o identificador à mão). */
export function useProvisioningEnabled(token: string): boolean {
  const [enabled, setEnabled] = useState(false);
  useEffect(() => {
    let alive = true;
    request<{ enabled: boolean }>(token, "GET", "/api/v1/identity/provisioning")
      .then((r) => { if (alive) setEnabled(r?.enabled === true); })
      .catch(() => { if (alive) setEnabled(false); });
    return () => { alive = false; };
  }, [token]);
  return enabled;
}

/** Senha provisória: aparece só nesta resposta e some ao fechar; a pessoa a troca no primeiro acesso. */
export function TemporaryPassword({ email, password, onClose }: { email: string; password: string; onClose: () => void }) {
  return (
    <div role="status" className="hint" aria-label="Senha provisória">
      <p>
        Conta criada para <strong>{email}</strong>. Entregue a senha provisória a ela por um canal seguro <strong>agora</strong>: ela não será
        mostrada de novo.
      </p>
      <p><code>{password}</code></p>
      <p>No primeiro acesso a pessoa configura o autenticador (código OTP) e troca a senha.</p>
      <button onClick={onClose}>Já entreguei, fechar</button>
    </div>
  );
}
