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
export function TemporaryPassword({ email, password, onClose, reset = false }: { email: string; password: string; onClose: () => void; reset?: boolean }) {
  return (
    <div role="status" className="hint" aria-label="Senha provisória">
      <p>
        {reset ? "Nova senha provisória gerada para " : "Conta criada para "}<strong>{email}</strong>. Entregue a senha provisória a ela por um canal seguro <strong>agora</strong>: ela não será
        mostrada de novo.{reset ? " As sessões abertas dessa conta foram encerradas e a senha anterior deixou de valer." : ""}
      </p>
      <p><code>{password}</code></p>
      <p>{reset ? "No próximo acesso a pessoa troca a senha (o autenticador já configurado continua valendo)." : "No primeiro acesso a pessoa configura o autenticador (código OTP) e troca a senha."}</p>
      <button onClick={onClose}>Já entreguei, fechar</button>
    </div>
  );
}
