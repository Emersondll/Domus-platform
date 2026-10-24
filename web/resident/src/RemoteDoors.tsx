import { useCallback, useEffect, useState } from "react";
import { ApiError, request } from "../../shared/src/api";
import type { Door, DoorCommand } from "./types";

const STATE_TEXT: Record<string, string> = {
  PENDING: "Enviando o comando…",
  SENT: "Comando aceito pelo equipamento. Aguardando a confirmação de que a porta abriu…",
  CONFIRMED: "Porta aberta (confirmado pelo equipamento).",
  FAILED: "Não foi possível abrir. A porta NÃO abriu.",
  UNKNOWN: "Sem confirmação do equipamento. Confira no local ou fale com a portaria.",
};
const FINAL = new Set(["CONFIRMED", "FAILED", "UNKNOWN"]);

/**
 * Abertura remota pelo morador. Só aparece quando o condomínio habilitou o recurso e marcou a porta. "Aceito" não
 * é "aberto": a mensagem de sucesso só sai com o estado CONFIRMED vindo do equipamento.
 */
export function RemoteDoors({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const [doors, setDoors] = useState<Door[]>([]);
  const [status, setStatus] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    request<Door[]>(token, "GET", `${base}/doors`).then((d) => setDoors(d ?? [])).catch(() => setDoors([]));
  }, [token, base]);

  const follow = useCallback(async (doorId: string, commandId: string) => {
    for (let i = 0; i < 20; i++) {
      const c = await request<DoorCommand>(token, "GET", `${base}/commands/${commandId}`);
      if (!c) return;
      setStatus((s) => ({ ...s, [doorId]: STATE_TEXT[c.state] ?? c.state }));
      if (FINAL.has(c.state)) return;
      await new Promise((r) => setTimeout(r, 1000));
    }
    setStatus((s) => ({ ...s, [doorId]: STATE_TEXT.UNKNOWN }));
  }, [token, base]);

  async function open(door: Door) {
    setBusy(door.id);
    setStatus((s) => ({ ...s, [door.id]: STATE_TEXT.PENDING }));
    try {
      const c = await request<DoorCommand>(token, "POST", `${base}/doors/${door.id}/open`,
        { idempotencyKey: crypto.randomUUID() });
      if (c) await follow(door.id, c.id);
    } catch (e) {
      const text = e instanceof ApiError && e.status === 429 ? "Aguarde alguns segundos para abrir de novo."
        : e instanceof ApiError && e.status === 404 ? "Abertura remota indisponível no momento."
        : "Não foi possível enviar o comando. A porta NÃO abriu.";
      setStatus((s) => ({ ...s, [door.id]: text }));
    } finally {
      setBusy(null);
    }
  }

  if (doors.length === 0) return null;
  return (
    <section aria-label="Abrir portão">
      <h3>Abrir portão</h3>
      <p className="hint">Use somente para quem você autorizou. Cada abertura fica registrada.</p>
      <ul>
        {doors.map((d) => (
          <li key={d.id}>
            <strong>{d.name}</strong> ({d.entrance}){" "}
            <button onClick={() => open(d)} disabled={busy !== null}>Abrir {d.name}</button>
            {status[d.id] && <p role="status">{status[d.id]}</p>}
          </li>
        ))}
      </ul>
    </section>
  );
}
