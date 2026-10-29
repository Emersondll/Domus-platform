import { useCallback, useEffect, useState } from "react";
import { request, type Page } from "../../shared/src/api";
import { AdminAgents } from "./AdminAgents";
import type { AdminScope, Company, CondoRow } from "./types";
import { errorMessage } from "./useCondoList";

const SECTIONS = ["Empresas", "Condomínios", "Agentes e sincronização"] as const;

/**
 * Administração da plataforma e da empresa operadora. O servidor decide o que cada papel pode fazer; a tela só mostra o que
 * o usuário administra (`/me/admin-scope`): o administrador da plataforma vê Empresas, o da empresa vê as próprias.
 */
export function AdminArea({ token, scope }: { token: string; scope: AdminScope }) {
  const [section, setSection] = useState<(typeof SECTIONS)[number]>(scope.platformAdmin ? "Empresas" : "Condomínios");
  const sections = SECTIONS.filter((s) => s !== "Empresas" || scope.platformAdmin);
  return (
    <section aria-label="Administração" className="admin-area">
      <h2>Administração</h2>
      <p className="hint">
        {scope.platformAdmin ? "Administrador da plataforma: todas as empresas e condomínios." :
          `Administrador da empresa: ${scope.companies.map((c) => c.name).join(", ")}.`}
      </p>
      <div role="tablist" aria-label="Seções da administração">
        {sections.map((s) => (
          <button key={s} role="tab" aria-selected={section === s} onClick={() => setSection(s)}>{s}</button>
        ))}
      </div>
      {section === "Empresas" && scope.platformAdmin && <Companies token={token} />}
      {section === "Condomínios" && <Condominiums token={token} companies={scope.companies} platformAdmin={scope.platformAdmin} />}
      {section === "Agentes e sincronização" && <AdminAgents token={token} />}
    </section>
  );
}

function Companies({ token }: { token: string }) {
  const [companies, setCompanies] = useState<Company[]>([]);
  const [name, setName] = useState("");
  const [taxId, setTaxId] = useState("");
  const [adminCompany, setAdminCompany] = useState("");
  const [subject, setSubject] = useState("");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [admins, setAdmins] = useState<{ membershipId: string; displayName: string }[]>([]);
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      if (adminCompany) {
        setAdmins((await request<{ membershipId: string; displayName: string }[]>(token, "GET", `/api/v1/companies/${adminCompany}/admins`)) ?? []);
      } else {
        setAdmins([]);
      }
      setCompanies((await request<Page<Company>>(token, "GET", "/api/v1/companies?size=100"))?.items ?? []);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [token, adminCompany]);
  useEffect(() => { void load(); }, [load]);

  async function run(fn: () => Promise<void>, ok: string) {
    setError(null);
    setDone(null);
    try {
      await fn();
      setDone(ok);
      await load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Empresas operadoras">
      <h3>Empresas operadoras</h3>
      <ul>
        {companies.map((c) => <li key={c.id}>{c.name} · CNPJ {c.taxId}</li>)}
        {companies.length === 0 && <li>Nenhuma empresa cadastrada.</li>}
      </ul>
      <h3>Cadastrar empresa</h3>
      <label htmlFor="co-name">Nome da empresa</label>
      <input id="co-name" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="co-tax">CNPJ (só dígitos)</label>
      <input id="co-tax" maxLength={20} value={taxId} onChange={(e) => setTaxId(e.target.value)} />
      <button disabled={!name.trim() || !taxId.trim()} onClick={() => run(async () => {
        await request(token, "POST", "/api/v1/companies", { name: name.trim(), taxId: taxId.trim() });
        setName("");
        setTaxId("");
      }, "Empresa cadastrada.")}>Cadastrar empresa</button>
      <h3>Adicionar administrador a uma empresa</h3>
      <p className="hint">Informe o identificador do usuário no provedor de login (Keycloak).</p>
      <label htmlFor="ad-company">Empresa</label>
      <select id="ad-company" value={adminCompany} onChange={(e) => setAdminCompany(e.target.value)}>
        <option value="">Escolha…</option>
        {companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <label htmlFor="ad-subject">Identificador do usuário</label>
      <input id="ad-subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
      <label htmlFor="ad-email">E-mail</label>
      <input id="ad-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
      <label htmlFor="ad-name">Nome</label>
      <input id="ad-name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
      <button disabled={!adminCompany || !subject.trim() || !email.trim() || !displayName.trim()} onClick={() => run(async () => {
        await request(token, "POST", `/api/v1/companies/${adminCompany}/admins`,
          { externalSubject: subject.trim(), email: email.trim(), displayName: displayName.trim() });
        setSubject("");
        setEmail("");
        setDisplayName("");
      }, "Administrador adicionado.")}>Adicionar administrador</button>
      {adminCompany && (
        <ul aria-label="Administradores da empresa">
          {admins.map((a) => (
            <li key={a.membershipId}>{a.displayName}{" "}
              <button className="danger" aria-label={`Revogar administrador ${a.displayName}`} onClick={() => {
                if (window.confirm(`Revogar o acesso de administrador de ${a.displayName}?`)) {
                  void run(async () => { await request(token, "POST", `/api/v1/companies/${adminCompany}/admins/${a.membershipId}/revoke`); },
                    "Administrador revogado.");
                }
              }}>Revogar</button>
            </li>
          ))}
          {admins.length === 0 && <li>Nenhum administrador nesta empresa.</li>}
        </ul>
      )}
      {done && <p role="status" className="hint">{done}</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}

function Condominiums({ token, companies, platformAdmin }: { token: string; companies: Company[]; platformAdmin: boolean }) {
  const [condos, setCondos] = useState<CondoRow[]>([]);
  const [allCompanies, setAllCompanies] = useState<Company[]>(companies);
  const [companyId, setCompanyId] = useState(companies[0]?.id ?? "");
  const [name, setName] = useState("");
  const [taxId, setTaxId] = useState("");
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setCondos((await request<Page<CondoRow>>(token, "GET", "/api/v1/condominiums?size=100"))?.items ?? []);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [token]);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => { setAllCompanies(companies); if (!companyId && companies[0]) setCompanyId(companies[0].id); }, [companies, companyId]);

  const companyName = (id: string) => allCompanies.find((c) => c.id === id)?.name ?? "—";
  return (
    <div aria-label="Condomínios da administração">
      <h3>Condomínios</h3>
      <ul>
        {condos.map((c) => (
          <li key={c.id}>{c.name}{platformAdmin ? ` · ${companyName(c.operatorCompanyId)}` : ""} · {c.status}</li>
        ))}
        {condos.length === 0 && <li>Nenhum condomínio.</li>}
      </ul>
      <h3>Criar condomínio</h3>
      <p className="hint">Blocos, unidades e a equipe se cadastram na mesa do condomínio (aba Cadastros).</p>
      <label htmlFor="cd-company">Empresa operadora</label>
      <select id="cd-company" value={companyId} onChange={(e) => setCompanyId(e.target.value)}>
        {allCompanies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <label htmlFor="cd-name">Nome do condomínio</label>
      <input id="cd-name" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="cd-tax">CNPJ do condomínio (opcional)</label>
      <input id="cd-tax" maxLength={20} value={taxId} onChange={(e) => setTaxId(e.target.value)} />
      <button disabled={!companyId || !name.trim()} onClick={async () => {
        setError(null);
        setDone(null);
        try {
          await request(token, "POST", "/api/v1/condominiums",
            { operatorCompanyId: companyId, name: name.trim(), taxId: taxId.trim() || null });
          setName("");
          setTaxId("");
          setDone("Condomínio criado.");
          await load();
        } catch (e) {
          setError(errorMessage(e));
        }
      }}>Criar condomínio</button>
      {done && <p role="status" className="hint">{done}</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
