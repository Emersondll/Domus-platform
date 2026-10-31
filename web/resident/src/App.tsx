import { useEffect, useState } from "react";
import { ApiError, devLogin, request, setUnauthorizedHandler } from "../../shared/src/api";
import { useTokenRefresh } from "../../shared/src/useTokenRefresh";
import { handleCallback, loadOidcConfig, logout, startLogin, type OidcConfig } from "../../shared/src/oidc";
import { Home } from "./Home";
import { IconCamera, IconHome, IconMenu, IconMessage, IconMore, IconParcel, IconVisits } from "./Icons";
import { Visits } from "./Visits";
import { Invitations } from "./Invitations";
import { Parcels, Vehicles } from "./Lists";
import { ProfilePage } from "./Profile";
import { JoinCondominium } from "./JoinCondominium";
import { Household } from "./Household";
import { Messages } from "./Messages";
import { Notices } from "./Notices";
import { Reservations } from "./Reservations";
import { MyAccesses } from "./MyAccesses";
import { ResidentCameras } from "./ResidentCameras";
import { Staff } from "./Staff";
import type { Residency } from "./types";

/** Barra inferior com o que se usa todo dia; o resto fica em "Mais" para caber em tela de celular. */
const PRIMARY = ["Início", "Visitas", "Encomendas", "Mensagens"] as const;
const MORE = ["Avisos", "Meus acessos", "Câmeras", "Convites", "Reservas", "Veículos", "Minha casa", "Equipe", "Perfil"] as const;
const NAV_ICON = { "Início": <IconHome />, Visitas: <IconVisits />, Encomendas: <IconParcel />, Mensagens: <IconMessage /> } as const;
type Tab = (typeof PRIMARY)[number] | (typeof MORE)[number] | "Mais";
const isMore = (t: Tab) => t === "Mais" || (MORE as readonly string[]).includes(t);

export function App() {
  const [token, setToken] = useState<string | null>(null);
  const [residencies, setResidencies] = useState<Residency[]>([]);
  const [condo, setCondo] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("Início");
  const [error, setError] = useState<string | null>(null);
  const [needsJoin, setNeedsJoin] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
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

  useTokenRefresh(oidc, token !== null, setToken);

  async function login(e: React.FormEvent) {
    e.preventDefault();
    try {
      // só no build de desenvolvimento: ?devSubject=<usuário> escolhe outro morador de teste (ex.: quem ainda não tem vínculo)
      setToken(await devLogin(new URLSearchParams(window.location.search).get("devSubject") || "resident-sol"));
    } catch {
      setError("Dev login unavailable. Is the API running with the dev profile?");
    }
  }

  // contador de avisos não lidos no botão Início (consulta leve, sem trazer a lista)
  const [unread, setUnread] = useState(0);
  useEffect(() => {
    if (!token || !condo) return;
    let alive = true;
    const load = () => request<{ unread: number }>(token, "GET", `/api/v1/condominiums/${condo}/me/notifications/unread-count`)
      .then((r) => { if (alive) setUnread(r?.unread ?? 0); }).catch(() => undefined);
    void load();
    const id = setInterval(load, 30000);
    return () => { alive = false; clearInterval(id); };
  }, [token, condo]);

  const [unreadMessages, setUnreadMessages] = useState(0);
  useEffect(() => {
    if (!token || !condo) return;
    let alive = true;
    const load = () => request<{ unread: number }>(token, "GET", `/api/v1/condominiums/${condo}/me/messages/unread`)
      .then((r) => { if (alive) setUnreadMessages(r?.unread ?? 0); }).catch(() => undefined);
    void load();
    const id = setInterval(load, 30000);
    return () => { alive = false; clearInterval(id); };
  }, [token, condo, tab]);

  useEffect(() => {
    if (!token) return;
    request<Residency[]>(token, "GET", "/api/v1/me/residencies").then((r) => {
      setResidencies(r ?? []);
      setCondo(r?.[0]?.condominiumId ?? null);
      setNeedsJoin((r ?? []).length === 0);
      setError(null);
    }).catch((e) => {
      // 403: o condomínio ou a empresa operadora está suspensa (os registros ficam; só o acesso é desligado)
      // 403 "não cadastrado": ainda não há vínculo; a pessoa pode pedir acesso com o código do síndico
      const notRegistered = e instanceof ApiError && e.status === 403 && /not registered/i.test(e.detail);
      setError(notRegistered ? null : e instanceof ApiError && e.status === 403
        ? "O acesso ao seu condomínio está suspenso. Fale com a administração."
        : "Não foi possível carregar a sua residência. Tente novamente.");
      setNeedsJoin(notRegistered);
    });
  }, [token, reloadKey]);

  if (oidc === undefined) {
    return <main className="login" aria-busy="true"><span className="skeleton line" role="status" aria-label="Carregando" /><span className="skeleton line" /></main>;
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
        <button className="sign-out" onClick={() => (oidc ? logout(oidc) : setToken(null))} aria-label="Sair">Sair</button>
        {condo && (
          <div className="header-actions">
            <button className="home-shortcut" onClick={() => setTab("Início")} aria-label="Ir para o início" title="Início"
              aria-current={tab === "Início"}><IconHome /></button>
            <button className="home-shortcut" onClick={() => setTab("Mais")} aria-label="Abrir menu de opções"
              title="Menu de opções" aria-current={tab === "Mais"}><IconMenu /></button>
            <button className="home-shortcut" onClick={() => setTab("Câmeras")} aria-label="Ver câmeras da entrada"
              title="Câmeras da entrada" aria-current={tab === "Câmeras"}><IconCamera /></button>
          </div>
        )}
        {residencies.length > 1 && (
          <select aria-label="Condomínio" value={condo ?? ""} onChange={(e) => setCondo(e.target.value)}>
            {residencies.map((r) => (
              <option key={r.condominiumId} value={r.condominiumId}>{r.condominiumName}</option>
            ))}
          </select>
        )}
      </header>
      <main>
        {condo && <h1 className="sr-only">Domus, app do morador</h1>}
        {!condo && error && <p role="alert" className="error">{error}</p>}
        {!condo && !error && needsJoin && <JoinCondominium token={token} onApproved={() => setReloadKey((k) => k + 1)} />}
        {!condo && !error && !needsJoin && <p>Nenhuma residência vinculada a este usuário.</p>}
        {condo && tab === "Mais" && (
          <section aria-label="Mais opções">
            <h2>Mais opções</h2>
            <div className="more-menu">
              {MORE.map((t) => (
                <button key={t} className="card" onClick={() => setTab(t)}>{t}</button>
              ))}
            </div>
          </section>
        )}
        {condo && tab !== "Início" && (
          <button className="back" onClick={() => setTab("Início")}>← Voltar ao início</button>
        )}
        {condo && tab === "Início" && <Home token={token} condo={condo} onNavigate={setTab} />}
        {condo && tab === "Avisos" && <Notices token={token} condo={condo} />}
        {condo && tab === "Mensagens" && <Messages token={token} condo={condo} />}
        {condo && tab === "Visitas" && <Visits token={token} condo={condo} />}
        {condo && tab === "Câmeras" && <ResidentCameras token={token} condo={condo} />}
        {condo && tab === "Meus acessos" && <MyAccesses token={token} condo={condo} />}
        {condo && tab === "Convites" && <Invitations token={token} condo={condo} />}
        {condo && tab === "Encomendas" && <Parcels token={token} condo={condo} />}
        {condo && tab === "Reservas" && <Reservations token={token} condo={condo} />}
        {condo && tab === "Veículos" && <Vehicles token={token} condo={condo} />}
        {condo && tab === "Minha casa" && <Household token={token} condo={condo} />}
        {condo && tab === "Equipe" && <Staff token={token} condo={condo} />}
        {condo && tab === "Perfil" && <ProfilePage token={token} condo={condo} residentId={current?.residentId} />}
      </main>
      <nav aria-label="Seções">
        {PRIMARY.map((t) => (
          <button key={t} aria-current={tab === t} onClick={() => setTab(t)}
            title={t === "Início" && unread > 0 ? `${unread} avisos não lidos`
              : t === "Mensagens" && unreadMessages > 0 ? `${unreadMessages} mensagens não lidas` : undefined}>
            <span className="nav-icon">{NAV_ICON[t]}</span>
            <span className="nav-label">{t}</span>
            {t === "Início" && unread > 0 && <span className="count" aria-hidden="true">{unread}</span>}
            {t === "Mensagens" && unreadMessages > 0 && <span className="count" aria-hidden="true">{unreadMessages}</span>}
          </button>
        ))}
        <button aria-current={isMore(tab)} onClick={() => setTab("Mais")}>
          <span className="nav-icon"><IconMore /></span><span className="nav-label">Mais</span>
        </button>
      </nav>
    </div>
  );
}
