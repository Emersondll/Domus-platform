import { useCallback, useEffect, useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";
import { CallWorkbench } from "./CallWorkbench";
import { IncidentsPanel } from "./IncidentsPanel";
import { Login } from "./Login";
import { OverviewPanel } from "./OverviewPanel";
import type { Call, Overview, Shift } from "./types";

const POLL_MS = 5000;

export function App() {
  const [session, setSession] = useState<{ token: string; subject: string } | null>(null);
  const [overview, setOverview] = useState<Overview[]>([]);
  const [calls, setCalls] = useState<Call[]>([]);
  const [shift, setShift] = useState<Shift | null>(null);
  const [selectedCondo, setSelectedCondo] = useState<string | null>(null);
  const [selectedCall, setSelectedCall] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const token = session?.token;

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      setOverview((await request<Overview[]>(token, "GET", "/api/v1/operator/overview")) ?? []);
      setCalls((await request<Page<Call>>(token, "GET", "/api/v1/operator/calls/mine"))?.items ?? []);
      setShift(await request<Shift>(token, "GET", "/api/v1/operator/shifts/current"));
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

  if (!session) {
    return <Login onLogin={(t, s) => setSession({ token: t, subject: s })} />;
  }

  const call = calls.find((c) => c.id === selectedCall) ?? null;

  return (
    <div className="layout">
      <header>
        <strong>Central de Portaria</strong>
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
        <button onClick={() => setSession(null)}>Sair</button>
      </header>

      {message && <p role="status" className="message">{message}</p>}

      <OverviewPanel items={overview} selected={selectedCondo} onSelect={setSelectedCondo} />

      <section aria-label="Meus atendimentos">
        <h2>Meus atendimentos</h2>
        <ul>
          {calls.map((c) => (
            <li key={c.id}>
              <button onClick={() => setSelectedCall(c.id)}>
                {c.callerName ?? "(sem nome)"} · {c.source}
              </button>
            </li>
          ))}
          {calls.length === 0 && <li>Nenhum atendimento em andamento.</li>}
        </ul>
      </section>

      {call && <CallWorkbench key={call.id} token={session.token} call={call} onChanged={refresh} />}
      {selectedCondo && <IncidentsPanel key={selectedCondo} token={session.token} condominiumId={selectedCondo} />}
    </div>
  );
}
