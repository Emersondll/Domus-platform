import { request } from "../../shared/src/api";
import { useEffect, useState } from "react";
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

  // só síndico ou administração gira a chave de um equipamento (o servidor é quem decide)
  const [canManage, setCanManage] = useState(false);
  const [rotating, setRotating] = useState<string | null>(null);
  const [newKey, setNewKey] = useState<{ name: string; apiKey: string } | null>(null);
  useEffect(() => {
    request<{ canManage: boolean }>(token, "GET", `${base}/devices/permissions`)
      .then((p) => setCanManage(p?.canManage === true)).catch(() => setCanManage(false));
  }, [token, base]);

  async function rotateKey(d: DeviceFull) {
    setError(null);
    try {
      const r = await request<{ apiKey: string }>(token, "POST", `${base}/devices/${d.id}/rotate-key`);
      if (r) setNewKey({ name: d.name, apiKey: r.apiKey });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setRotating(null);
    }
  }

  const [remoteOpen, setRemoteOpen] = useState<boolean | null>(null);
  useEffect(() => {
    request<{ enabled: boolean }>(token, "GET", `${base}/remote-open`)
      .then((r) => setRemoteOpen(r?.enabled ?? false))
      .catch(() => setRemoteOpen(null));
  }, [token, base]);

  async function togglePolicy() {
    setError(null);
    try {
      const r = await request<{ enabled: boolean }>(token, "PUT", `${base}/remote-open`, { enabled: !remoteOpen });
      setRemoteOpen(r?.enabled ?? false);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function toggleDevice(d: DeviceFull) {
    setError(null);
    try {
      await request(token, "PUT", `${base}/devices/${d.id}/resident-openable`, { allowed: !d.residentOpenable });
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function refreshStatus(id: string) {
    setError(null);
    try {
      await request(token, "POST", `${base}/devices/${id}/refresh-status`);
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function refreshAll() {
    setError(null);
    try {
      for (const d of doors) await request(token, "POST", `${base}/devices/${d.id}/refresh-status`);
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Portas">
      <h3>Portas e controladoras</h3>
      {error && <p role="alert" className="error">{error}</p>}
      {newKey && (
        <p role="status" className="hint">
          Nova chave de "{newKey.name}" (aparece só agora, guarde): <code>{newKey.apiKey}</code>{" "}
          <button onClick={() => setNewKey(null)}>Já guardei</button>
        </p>
      )}
      {doors.length > 1 && <button onClick={refreshAll}>Atualizar estado de todas</button>}
      <ul>
        {doors.map((d) => (
          <li key={d.id}>
            <strong>{d.name}</strong> ({d.entrance}) ·{" "}
            <span className={d.status === "ONLINE" ? "badge ok" : "badge warn"}>{d.status === "ONLINE" ? "online" : d.status === "OFFLINE" ? "offline" : "desconhecido"}</span>
            {d.lastSeenAt && <small> visto {new Date(d.lastSeenAt).toLocaleString()}</small>}{" "}
            <button onClick={() => refreshStatus(d.id)}>Atualizar estado</button>
            {canManage && (rotating === d.id ? (
              <span role="group" aria-label={`Confirmar troca de chave de ${d.name}`}>
                <small>A chave atual deixa de valer; o equipamento ou agente para até receber a nova.</small>{" "}
                <button onClick={() => rotateKey(d)}>Confirmar troca</button>{" "}
                <button onClick={() => setRotating(null)}>Cancelar</button>
              </span>
            ) : (
              <button aria-label={`Girar chave de ${d.name}`} onClick={() => setRotating(d.id)}>Girar chave</button>
            ))}
            {remoteOpen && d.kind === "ACCESS_CONTROLLER" && (
              <button onClick={() => toggleDevice(d)} aria-pressed={!!d.residentOpenable}>
                {d.residentOpenable ? "Bloquear abertura pelo morador" : "Liberar abertura pelo morador"}
              </button>
            )}
          </li>
        ))}
        {doors.length === 0 && <li>Nenhum dispositivo cadastrado.</li>}
      </ul>
      {remoteOpen !== null && (
        <div aria-label="Abertura remota pelo morador">
          <h3>Abertura remota pelo morador</h3>
          <p>
            Situação: <span className={remoteOpen ? "badge pending" : "badge ok"}>{remoteOpen ? "habilitada" : "desligada"}</span>{" "}
            <button onClick={togglePolicy}>{remoteOpen ? "Desligar" : "Habilitar"}</button>
          </p>
          <p className="hint">Opcional e desligada por padrão. Só o síndico altera. Mesmo habilitada, só abre as portas marcadas abaixo; cada abertura é registrada e só vale como "aberta" com a confirmação do equipamento.</p>
        </div>
      )}
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
