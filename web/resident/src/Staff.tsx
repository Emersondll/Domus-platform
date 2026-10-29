import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import { errorText } from "./useApi";

type StaffMember = { id: string; role: string; displayName?: string | null };

const ROLE_TEXT: Record<string, string> = {
  CONDO_MANAGER: "Síndico / administração do condomínio",
  OPERATOR: "Portaria remota",
};

/** Quem faz parte da equipe do condomínio (por segurança, o morador sabe quem atende a portaria). Só nome e função. */
export function Staff({ token, condo }: { token: string; condo: string }) {
  const [team, setTeam] = useState<StaffMember[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    request<StaffMember[]>(token, "GET", `/api/v1/condominiums/${condo}/members`)
      .then((r) => setTeam(r ?? []))
      .catch((e) => setError(errorText(e)));
  }, [token, condo]);
  return (
    <section aria-label="Equipe">
      <h2>Equipe da portaria</h2>
      <p className="hint">Estas são as pessoas autorizadas a atender e administrar o seu condomínio.</p>
      {error && <p role="alert" className="error">{error}</p>}
      <ul>
        {(team ?? []).map((m) => (
          <li key={m.id}><strong>{m.displayName || "—"}</strong> · {ROLE_TEXT[m.role] ?? m.role}</li>
        ))}
        {team !== null && team.length === 0 && <li>Nenhuma pessoa cadastrada na equipe.</li>}
      </ul>
    </section>
  );
}
