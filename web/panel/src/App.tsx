import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, request, setUnauthorizedHandler, type Page } from "../../shared/src/api";
import { useTokenRefresh } from "../../shared/src/useTokenRefresh";
import { displayNameFrom, handleCallback, loadOidcConfig, logout, type OidcConfig } from "../../shared/src/oidc";
import { AdminArea } from "./AdminArea";
import { CallWorkbench } from "./CallWorkbench";
import { CameraMonitor } from "./CameraMonitor";
import { CondoDesk } from "./CondoDesk";
import { Login } from "./Login";
import { OverviewPanel } from "./OverviewPanel";
import { PanicAlerts } from "./PanicAlerts";
import type { AdminScope, Call, Overview, Shift } from "./types";

const POLL_MS = 5000;

export function App() {
  const [session, setSession] = useState<{ token: string; subject: string } | null>(null);
  const [overview, setOverview] = useState<Overview[]>([]);
  const [calls, setCalls] = useState<Call[]>([]);
  const [shift, setShift] = useState<Shift | null>(null);
  const [selectedCondo, setSelectedCondo] = useState<string | null>(null);
  const [selectedCall, setSelectedCall] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [oidc, setOidc] = useState<OidcConfig | null | undefined>(undefined); // undefined = carregando
  const [adminScope, setAdminScope] = useState<AdminScope | null>(null);
  const [adminOpen, setAdminOpen] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);

  // Descobre se há OIDC e, se a URL for um retorno do Keycloak, conclui o login.
  useEffect(() => {
    (async () => {
      const cfg = await loadOidcConfig();
      if (cfg) {
        try {
          const t = await handleCallback(cfg);
          if (t) setSession({ token: t, subject: displayNameFrom(t) });
        } catch {
          setAuthError("Não foi possível concluir o login. Tente novamente.");
        }
      }
      setOidc(cfg);
    })();
  }, []);

  // 401 da API (token expirado/inválido): volta à tela de entrada (o SSO do Keycloak renova sem pedir senha).
  useEffect(() => {
    setUnauthorizedHandler(() => setSession(null));
    return () => setUnauthorizedHandler(null);
  }, []);

  useTokenRefresh(oidc, session !== null, (t) => setSession((s) => (s ? { ...s, token: t } : s)));

  const token = session?.token;

  useEffect(() => {
    if (!token) { setAdminScope(null); setAdminOpen(false); return; }
    request<AdminScope>(token, "GET", "/api/v1/me/admin-scope").then((a) => setAdminScope(a)).catch(() => setAdminScope(null));
  }, [token]);

  const staffOnly = useRef(false);
  useEffect(() => { if (!session) staffOnly.current = false; }, [session]);
  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      setOverview((await request<Overview[]>(token, "GET", "/api/v1/operator/overview")) ?? []);
      // síndico e administração usam o painel sem ser operadores: atendimentos e turno são só de operador (403), e isso
      // não é erro. Depois da primeira recusa a tela deixa de pedir.
      if (!staffOnly.current) {
        try {
          setCalls((await request<Page<Call>>(token, "GET", "/api/v1/operator/calls/mine"))?.items ?? []);
          setShift(await request<Shift>(token, "GET", "/api/v1/operator/shifts/current"));
        } catch (e) {
          if (!(e instanceof ApiError && e.status === 403)) throw e;
          staffOnly.current = true;
          setCalls([]);
          setShift(null);
        }
      }
    } catch (e) {
      setMessage(e instanceof ApiError ? e.detail : "Unexpected error");
    }
  }, [token]);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, POLL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  async function act(fn: () => Promise<unknown>) {
    setMessage(null);
    try {
      await fn();
    } catch (e) {
      setMessage(e instanceof ApiError ? e.detail : "Unexpected error");
    }
    await refresh();
  }

  if (oidc === undefined) {
    return <main className="login"><p>Carregando…</p></main>;
  }
  if (!session) {
    return <Login oidc={oidc} authError={authError} onLogin={(t, s) => setSession({ token: t, subject: s })} />;
  }

  const call = calls.find((c) => c.id === selectedCall) ?? null;
  const monitoredCondo = call?.condominiumId ?? selectedCondo; // câmeras do condomínio em foco, ao lado da mesa/atendimento

  /** Decisão do operador diante de um alerta de pânico: assumir o alerta mais antigo (abre o turno se preciso). */
  async function attendPanic() {
    if (!shift) await request(session!.token, "POST", "/api/v1/operator/shifts/start");
    const next = await request<Call>(session!.token, "POST", "/api/v1/operator/calls/next");
    if (next) {
      setSelectedCondo(next.condominiumId);
      setSelectedCall(next.id);
    }
    await refresh();
  }

  return (
    <div className="layout">
      <PanicAlerts token={session.token} onAttend={attendPanic} />
      <header>
        <strong>Domus Central</strong>
        <button onClick={() => { setSelectedCondo(null); setSelectedCall(null); setMessage(null); }}>Início</button>
        {adminScope && (adminScope.platformAdmin || adminScope.companies.length > 0) && (
          <button aria-pressed={adminOpen} onClick={() => setAdminOpen((v) => !v)}>Administração</button>
        )}
        <span>{session.subject}</span>
        <span>{shift ? `Turno aberto desde ${new Date(shift.startedAt).toLocaleTimeString()}` : "Sem turno"}</span>
        {shift ? (
          <button onClick={() => act(() => request(session.token, "POST", "/api/v1/operator/shifts/end"))}>
            Encerrar turno
          </button>
        ) : (
          <button onClick={() => act(() => request(session.token, "POST", "/api/v1/operator/shifts/start"))}>
            Iniciar turno
          </button>
        )}
        <button
          disabled={!shift}
          onClick={() =>
            act(async () => {
              const next = await request<Call>(session.token, "POST", "/api/v1/operator/calls/next");
              if (next) setSelectedCall(next.id);
              else setMessage("Fila vazia");
            })
          }
        >
          Atender próxima
        </button>
        <button onClick={() => (oidc ? logout(oidc) : setSession(null))}>Sair</button>
      </header>

      {message && <p role="status" className="message">{message}</p>}

      {adminOpen && adminScope ? <AdminArea token={session.token} scope={adminScope} /> : (<>
      <OverviewPanel items={overview} selected={selectedCondo} onSelect={setSelectedCondo} />

      <div className="col-main">
        <section aria-label="Meus atendimentos">
          <h2>Meus atendimentos</h2>
          <ul>
            {calls.map((c) => (
              <li key={c.id}>
                <button onClick={() => setSelectedCall(c.id)}>
                  {c.source === "PANIC" ? <span className="badge err">PÂNICO</span> : null} {c.callerName ?? c.unitLabel ?? "(sem nome)"} · {c.source}{c.unitLabel && c.callerName ? ` · ${c.unitLabel}` : ""}
                </button>
              </li>
            ))}
            {calls.length === 0 && <li>Nenhum atendimento em andamento.</li>}
          </ul>
        </section>

        {call && <CallWorkbench key={call.id} token={session.token} call={call} onChanged={refresh}
          onBack={() => setSelectedCall(null)} />}
        {selectedCondo && (
          <CondoDesk key={selectedCondo} token={session.token} condominiumId={selectedCondo}
            name={overview.find((o) => o.condominiumId === selectedCondo)?.name ?? "Condomínio"}
            onBack={() => setSelectedCondo(null)} />
        )}
      </div>
      {monitoredCondo && (
        <aside className="col-cameras" aria-label="Câmeras do condomínio em monitoramento">
          <CameraMonitor key={monitoredCondo} token={session.token} condominiumId={monitoredCondo} />
        </aside>
      )}
      </>)}
    </div>
  );
}
