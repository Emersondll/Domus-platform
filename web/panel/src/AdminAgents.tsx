import { useCallback, useEffect, useState } from "react";
import { request, type Page } from "../../shared/src/api";
import type { AgentRow, CondoRow, SyncConflict } from "./types";
import { errorMessage } from "./useCondoList";

const KIND_TEXT: Record<string, string> = {
  REVOKED_BEFORE_USE: "credencial revogada antes do uso", QUOTA_EXCEEDED: "limite de uso excedido",
  UNKNOWN_CREDENTIAL: "credencial desconhecida", EXPIRED_AT_USE: "vencida no momento do uso",
  UNCERTAIN_ENTRY: "entrada incerta",
};

/** Agentes locais (cadastro, estado, revogação) e conflitos de sincronização de um condomínio. */
export function AdminAgents({ token }: { token: string }) {
  const [condos, setCondos] = useState<CondoRow[]>([]);
  const [condo, setCondo] = useState("");
  const [agents, setAgents] = useState<AgentRow[]>([]);
  const [conflicts, setConflicts] = useState<SyncConflict[]>([]);
  const [name, setName] = useState("");
  const [secrets, setSecrets] = useState<{ name: string; apiKey: string; policySecret: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    request<Page<CondoRow>>(token, "GET", "/api/v1/condominiums?size=100")
      .then((p) => { const items = p?.items ?? []; setCondos(items); setCondo((c) => c || items[0]?.id || ""); })
      .catch((e) => setError(errorMessage(e)));
  }, [token]);

  const load = useCallback(async () => {
    if (!condo) return;
    setError(null);
    try {
      const base = `/api/v1/condominiums/${condo}`;
      setAgents((await request<AgentRow[]>(token, "GET", `${base}/agents`)) ?? []);
      setConflicts((await request<Page<SyncConflict>>(token, "GET", `${base}/sync-conflicts?size=50`))?.items ?? []);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [token, condo]);
  useEffect(() => { setSecrets(null); void load(); }, [load]);

  async function run(fn: () => Promise<void>) {
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  const base = `/api/v1/condominiums/${condo}`;
  return (
    <div aria-label="Agentes e sincronização">
      <label htmlFor="ag-condo">Condomínio</label>
      <select id="ag-condo" value={condo} onChange={(e) => setCondo(e.target.value)}>
        {condos.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>

      <h3>Agentes locais</h3>
      <ul>
        {agents.map((a) => (
          <li key={a.id}>
            {a.name} · {a.status === "REVOKED" ? "revogado" : a.online ? "online" : "offline"}
            {a.lastSeenAt ? ` · visto ${new Date(a.lastSeenAt).toLocaleString()}` : " · nunca visto"}
            {` · política ${a.lastPolicyVersion ?? "—"} · pendentes ${a.backlog}`}{" "}
            {a.status !== "REVOKED" && (
              <button aria-label={`Revogar agente ${a.name}`}
                onClick={() => run(() => request(token, "POST", `${base}/agents/${a.id}/revoke`).then(() => undefined))}>Revogar</button>
            )}
          </li>
        ))}
        {agents.length === 0 && <li>Nenhum agente cadastrado.</li>}
      </ul>
      <label htmlFor="ag-name">Nome do novo agente</label>
      <input id="ag-name" maxLength={80} placeholder="Servidor da portaria" value={name} onChange={(e) => setName(e.target.value)} />
      <button disabled={!condo || !name.trim()} onClick={() => run(async () => {
        const r = await request<{ name: string; apiKey: string; policySecret: string }>(token, "POST", `${base}/agents`, { name: name.trim() });
        if (r) setSecrets(r);
        setName("");
      })}>Cadastrar agente</button>
      {secrets && (
        <div role="status" className="hint">
          <p>Credenciais do agente "{secrets.name}" — aparecem <strong>só agora</strong>; guarde em local seguro (não ficam registradas).</p>
          <p>Chave de API: <code>{secrets.apiKey}</code></p>
          <p>Segredo da política: <code>{secrets.policySecret}</code></p>
          <button onClick={() => setSecrets(null)}>Já guardei</button>
        </div>
      )}

      <h3>Conflitos de sincronização</h3>
      <p className="hint">Divergências entre o que o agente decidiu offline e o estado do servidor. Resolver só marca como tratado.</p>
      <ul>
        {conflicts.map((c) => (
          <li key={c.id}>
            {KIND_TEXT[c.kind] ?? c.kind} · {new Date(c.createdAt).toLocaleString()} · {c.detail}{" "}
            {c.resolvedAt ? <span className="badge">resolvido</span> : (
              <button aria-label={`Resolver conflito ${c.id.slice(0, 8)}`}
                onClick={() => run(() => request(token, "POST", `${base}/sync-conflicts/${c.id}/resolve`).then(() => undefined))}>Marcar como resolvido</button>
            )}
          </li>
        ))}
        {conflicts.length === 0 && <li>Nenhum conflito.</li>}
      </ul>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
