import { useEffect, useState } from "react";
import { devLogin, request, setUnauthorizedHandler } from "../../shared/src/api";
import { handleCallback, loadOidcConfig, logout, startLogin, type OidcConfig } from "../../shared/src/oidc";
import { Home } from "./Home";
import { Invitations } from "./Invitations";
import { Parcels, Vehicles } from "./Lists";
import { ProfilePage } from "./Profile";
import { Reservations } from "./Reservations";
import type { Residency } from "./types";

const TABS = ["Início", "Convites", "Encomendas", "Reservas", "Veículos", "Perfil"] as const;
type Tab = (typeof TABS)[number];

export function App() {
  const [token, setToken] = useState<string | null>(null);
  const [residencies, setResidencies] = useState<Residency[]>([]);
  const [condo, setCondo] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("Início");
  const [error, setError] = useState<string | null>(null);
  const [oidc, setOidc] = useState<OidcConfig | null | undefined>(undefined); // undefined = carregando

  useEffect(() => {
    (async () => {
      const cfg = await loadOidcConfig();
      if (cfg) {
        try {
          const t = await handleCallback(cfg);
          if (t) setToken(t);
        } catch {
          setError("Não foi possível concluir o login. Tente novamente.");
        }
      }
      setOidc(cfg);
    })();
  }, []);

  // 401 da API: sessão expirada, volta à tela de entrada.
  useEffect(() => {
    setUnauthorizedHandler(() => setToken(null));
    return () => setUnauthorizedHandler(null);
  }, []);

  async function login(e: React.FormEvent) {
    e.preventDefault();
    try {
      setToken(await devLogin("resident-sol"));
    } catch {
      setError("Dev login unavailable. Is the API running with the dev profile?");
    }
  }

  useEffect(() => {
    if (!token) return;
    request<Residency[]>(token, "GET", "/api/v1/me/residencies").then((r) => {
      setResidencies(r ?? []);
      setCondo(r?.[0]?.condominiumId ?? null);
    });
  }, [token]);

  if (oidc === undefined) {
    return <main className="login"><p>Carregando…</p></main>;
  }
  if (!token) {
    return (
      <main className="login">
        <h1>Domus</h1>
        {oidc ? (
          <button onClick={() => startLogin(oidc)}>Entrar</button>
        ) : import.meta.env.DEV ? (
          <>
            <p className="badge warn">DEV login (morador de teste) — só em desenvolvimento; em produção o login é do Keycloak</p>
            <form onSubmit={login}>
              <button type="submit">Entrar como morador de teste</button>
            </form>
          </>
        ) : (
          <p role="alert" className="error">Login não configurado (/config.json ausente).</p>
        )}
        {error && <p role="alert" className="error">{error}</p>}
      </main>
    );
  }

  const current = residencies.find((r) => r.condominiumId === condo);

  return (
    <div className="app">
      <header>
        <strong>{current ? `${current.condominiumName} · Unidade ${current.unitNumber}` : "Domus"}</strong>
        <button onClick={() => (oidc ? logout(oidc) : setToken(null))} aria-label="Sair">Sair</button>
        {residencies.length > 1 && (
          <select aria-label="Condomínio" value={condo ?? ""} onChange={(e) => setCondo(e.target.value)}>
            {residencies.map((r) => (
              <option key={r.condominiumId} value={r.condominiumId}>{r.condominiumName}</option>
            ))}
          </select>
        )}
      </header>
      <main>
        {!condo && <p>Nenhuma residência vinculada a este usuário.</p>}
        {condo && tab === "Início" && <Home token={token} condo={condo} />}
        {condo && tab === "Convites" && <Invitations token={token} condo={condo} />}
        {condo && tab === "Encomendas" && <Parcels token={token} condo={condo} />}
        {condo && tab === "Reservas" && <Reservations token={token} condo={condo} />}
        {condo && tab === "Veículos" && <Vehicles token={token} condo={condo} />}
        {condo && tab === "Perfil" && <ProfilePage token={token} condo={condo} />}
      </main>
      <nav aria-label="Seções">
        {TABS.map((t) => (
          <button key={t} aria-current={tab === t} onClick={() => setTab(t)}>{t}</button>
        ))}
      </nav>
    </div>
  );
}
