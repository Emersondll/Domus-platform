import { request } from "../../shared/src/api";
import { useState } from "react";
import type { CommandRow, DeviceFull } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

const STATE_TEXT: Record<string, string> = {
  PENDING: "enviando",
  SENT: "aceito pelo equipamento, sem confirmação",
  CONFIRMED: "porta aberta (confirmado pelo equipamento)",
  FAILED: "falhou, porta NÃO aberta",
  UNKNOWN: "sem confirmação, verificar no local",
};

/** Estado das portas/controladoras e histórico de comandos. A abertura é feita dentro de um atendimento. */
export function DeskDoors({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const devices = useCondoList<DeviceFull>(token, `${base}/devices?size=100`, 10000);
  const commands = useCondoList<CommandRow>(token, `${base}/commands?size=10`, 10000);
  const [error, setError] = useState<string | null>(null);
  const doors = devices.items.filter((d) => d.kind !== "CAMERA");
  const name = (id: string) => devices.items.find((d) => d.id === id)?.name ?? "dispositivo";

  async function refreshStatus(id: string) {
    setError(null);
    try {
      await request(token, "POST", `${base}/devices/${id}/refresh-status`);
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Portas">
      <h3>Portas e controladoras</h3>
      {error && <p role="alert" className="error">{error}</p>}
      <ul>
        {doors.map((d) => (
          <li key={d.id}>
            <strong>{d.name}</strong> ({d.entrance}) ·{" "}
            <span className={d.status === "ONLINE" ? "badge ok" : "badge warn"}>{d.status === "ONLINE" ? "online" : d.status === "OFFLINE" ? "offline" : "desconhecido"}</span>
            {d.lastSeenAt && <small> visto {new Date(d.lastSeenAt).toLocaleString()}</small>}{" "}
            <button onClick={() => refreshStatus(d.id)}>Atualizar estado</button>
          </li>
        ))}
        {doors.length === 0 && <li>Nenhum dispositivo cadastrado.</li>}
      </ul>
      <p className="hint">Para abrir uma porta, use o botão "Abrir porta" dentro do atendimento: é lá que a justificativa e a base de autorização são registradas.</p>
      <h3>Últimos comandos</h3>
      <ul>
        {commands.items.map((c) => (
          <li key={c.id}>
            {new Date(c.requestedAt).toLocaleString()} · {name(c.deviceId)} · {STATE_TEXT[c.state] ?? c.state}
            {c.failureReason ? ` (${c.failureReason})` : ""}
          </li>
        ))}
        {commands.items.length === 0 && <li>Nenhum comando.</li>}
      </ul>
    </div>
  );
}
