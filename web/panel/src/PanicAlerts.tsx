import { errorMessage } from "./useCondoList";
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
  // falhas seguidas de rede/servidor (não 401/403): com 2 ou mais o operador é avisado de que a vigia do pânico está parada
  const [failures, setFailures] = useState(0);

  useEffect(() => {
    let stopped = false;
    async function load() {
      try {
        const list = (await request<PanicAlert[]>(token, "GET", "/api/v1/operator/panics")) ?? [];
        if (!stopped) { setAlerts(list); setFailures(0); }
      } catch (e) {
        // sem permissão: silêncio (síndico sem a rota); rede ou servidor fora do ar: conta a falha e tenta no próximo ciclo
        if (!stopped && !(e instanceof ApiError && e.status < 500)) setFailures((n) => n + 1);
      }
    }
    void load();
    const id = setInterval(load, POLL_MS);
    // aba em segundo plano tem o relógio atrasado pelo navegador: ao voltar à tela ou à rede, consulta na hora
    const wake = () => { if (document.visibilityState === "visible") void load(); };
    document.addEventListener("visibilitychange", wake);
    window.addEventListener("online", wake);
    window.addEventListener("focus", wake);
    return () => {
      stopped = true; clearInterval(id);
      document.removeEventListener("visibilitychange", wake);
      window.removeEventListener("online", wake);
      window.removeEventListener("focus", wake);
    };
  }, [token]);

  const loud = alerts.some((a) => !silenced.includes(a.callId));
  const siren = useSiren(loud);

  useEffect(() => {
    if (alerts.length === 0) return;
    const original = document.title;
    document.title = `🚨 PÂNICO — ${alerts[0].unitLabel ?? "unidade"}`;
    return () => { document.title = original; };
  }, [alerts]);

  const watchDown = failures >= 2 ? (
    <div className="panic-watch-down" role="alert">
      Sem conexão com a vigia de pânico. Alertas podem não aparecer até a rede voltar; confira a conexão.
    </div>
  ) : null;

  if (alerts.length === 0) return watchDown;

  async function attend() {
    setBusy(true);
    setError(null);
    try {
      await onAttend();
    } catch (e) {
      setError(e instanceof ApiError && e.status === 403 ? "Somente operadores atendem alertas." : errorMessage(e));
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
