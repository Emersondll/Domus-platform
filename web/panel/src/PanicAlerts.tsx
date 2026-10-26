import { useEffect, useRef, useState } from "react";
import { ApiError, request } from "../../shared/src/api";
import type { PanicAlert } from "./types";

const POLL_MS = 3000;

/** Sirene: dois tons alternados enquanto `active`. Devolve se o navegador bloqueou o som (exige um clique). */
export function useSiren(active: boolean): { blocked: boolean; enable: () => void } {
  const ctx = useRef<AudioContext | null>(null);
  const [blocked, setBlocked] = useState(false);

  function context(): AudioContext | null {
    const Ctor = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return null;
    ctx.current ??= new Ctor();
    return ctx.current;
  }

  function beep(freq: number) {
    const c = context();
    if (!c) return;
    const osc = c.createOscillator();
    const gain = c.createGain();
    osc.type = "square";
    osc.frequency.value = freq;
    gain.gain.value = 0.15;
    osc.connect(gain);
    gain.connect(c.destination);
    osc.start();
    osc.stop(c.currentTime + 0.5);
  }

  function enable() {
    void context()?.resume().then(() => setBlocked(context()?.state === "suspended"));
  }

  useEffect(() => {
    if (!active) return;
    const c = context();
    if (c && c.state === "suspended") void c.resume().catch(() => {});
    setBlocked(c?.state === "suspended");
    let high = true;
    beep(880);
    const id = setInterval(() => { high = !high; beep(high ? 880 : 660); }, 600);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  return { blocked, enable };
}

/**
 * Janela de alerta de pânico para a portaria: aparece por cima de tudo enquanto houver alerta na fila, com sirene e a
 * unidade de origem. O operador decide: atender agora (assume o alerta; começa o turno se preciso) ou silenciar o som.
 * Quem não é operador (síndico) vê o alerta, mas só o operador consegue atender.
 */
export function PanicAlerts({ token, onAttend }: { token: string; onAttend: () => Promise<void> }) {
  const [alerts, setAlerts] = useState<PanicAlert[]>([]);
  const [silenced, setSilenced] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let stopped = false;
    async function load() {
      try {
        const list = (await request<PanicAlert[]>(token, "GET", "/api/v1/operator/panics")) ?? [];
        if (!stopped) setAlerts(list);
      } catch {
        // sem permissão ou API fora do ar: o aviso volta no próximo ciclo
      }
    }
    void load();
    const id = setInterval(load, POLL_MS);
    return () => { stopped = true; clearInterval(id); };
  }, [token]);

  const loud = alerts.some((a) => !silenced.includes(a.callId));
  const siren = useSiren(loud);

  useEffect(() => {
    if (alerts.length === 0) return;
    const original = document.title;
    document.title = `🚨 PÂNICO — ${alerts[0].unitLabel ?? "unidade"}`;
    return () => { document.title = original; };
  }, [alerts]);

  if (alerts.length === 0) return null;

  async function attend() {
    setBusy(true);
    setError(null);
    try {
      await onAttend();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 403 ? "Somente operadores atendem alertas." : e instanceof ApiError ? e.detail : "Erro inesperado");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panic-modal" role="alertdialog" aria-modal="true" aria-label="Alerta de pânico">
      <div className="panic-box">
        <h2>🚨 ALERTA DE PÂNICO</h2>
        <ul>
          {alerts.map((a) => (
            <li key={a.callId}>
              <strong>{a.condominiumName}</strong> · <strong>{a.unitLabel ?? "unidade não identificada"}</strong>
              <small> · há {Math.max(0, Math.round((Date.now() - new Date(a.createdAt).getTime()) / 1000))} s</small>
            </li>
          ))}
        </ul>
        <p>Entre em contato com a unidade e acione o socorro necessário. Atender assume o alerta mais antigo.</p>
        <div className="actions">
          <button className="danger" disabled={busy} onClick={attend}>Atender agora</button>
          <button disabled={!loud} onClick={() => setSilenced(alerts.map((a) => a.callId))}>Silenciar som</button>
        </div>
        {siren.blocked && loud && (
          <p role="status" className="hint">O navegador bloqueou o som. <button onClick={siren.enable}>Ativar som</button></p>
        )}
        {error && <p role="alert" className="error">{error}</p>}
      </div>
    </div>
  );
}
