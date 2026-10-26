import { useState } from "react";
import { request } from "../../shared/src/api";
import type { DeviceFull } from "./types";
import { CameraPlayer } from "./CameraPlayer";
import { errorMessage, useCondoList } from "./useCondoList";

const STATUS_TEXT: Record<string, string> = { ONLINE: "online", OFFLINE: "offline", UNKNOWN: "estado desconhecido" };

/**
 * Câmeras do condomínio: cadastro (síndico/administração), estado e vídeo ao vivo (HLS via servidor de mídia, quando a
 * câmera tem endereço RTSP). Nenhuma imagem é simulada.
 */
export function DeskCameras({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/devices`;
  const devices = useCondoList<DeviceFull>(token, `${base}?size=100`, 10000);
  const cameras = devices.items.filter((d) => d.kind === "CAMERA");
  const [name, setName] = useState("");
  const [entrance, setEntrance] = useState("");
  const [adapter, setAdapter] = useState("ONVIF");
  const [rtspUrl, setRtspUrl] = useState("");
  const [rtspEnv, setRtspEnv] = useState("");
  const [watching, setWatching] = useState<DeviceFull | null>(null);
  const [apiKey, setApiKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  function cameraConfig(): Record<string, string> {
    const config: Record<string, string> = {};
    if (rtspUrl.trim()) config.rtspUrl = rtspUrl.trim();
    if (rtspUrl.trim() && rtspEnv.trim()) config.rtspCredentialsEnv = rtspEnv.trim();
    return config;
  }

  async function register() {
    setError(null);
    setApiKey(null);
    try {
      const created = await request<DeviceFull & { apiKey?: string }>(token, "POST", base,
        { name: name.trim(), kind: "CAMERA", adapter, entrance: entrance.trim(), config: cameraConfig() });
      setApiKey(created?.apiKey ?? null);
      setName("");
      setEntrance("");
      setRtspUrl("");
      setRtspEnv("");
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function refresh(id: string) {
    setError(null);
    try {
      await request(token, "POST", `${base}/${id}/refresh-status`);
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Câmeras">
      <h3>Câmeras</h3>
      <p className="hint">
        Cadastre as câmeras e acompanhe se estão online. O vídeo ao vivo só aparece para câmeras com endereço RTSP e com o
        servidor de mídia ligado (docs/cameras.md). Ver a imagem fica registrado na auditoria. Nenhuma imagem é simulada.
      </p>
      {watching && (
        <CameraPlayer token={token} name={watching.name} sessionUrl={`${base}/${watching.id}/stream-session`}
          onClose={() => setWatching(null)} />
      )}
      <ul>
        {cameras.map((c) => (
          <li key={c.id}>
            <strong>{c.name}</strong> ({c.entrance}) · {STATUS_TEXT[c.status] ?? "estado desconhecido"}{" "}
            <button onClick={() => refresh(c.id)} aria-label={`Atualizar estado de ${c.name}`}>Atualizar estado</button>{" "}
            {c.liveVideo
              ? <button onClick={() => setWatching(c)} aria-label={`Ver ao vivo ${c.name}`}>Ver ao vivo</button>
              : <span className="hint">sem vídeo configurado</span>}
          </li>
        ))}
        {cameras.length === 0 && <li>Nenhuma câmera cadastrada.</li>}
      </ul>
      <h3>Cadastrar câmera</h3>
      <p className="hint">Só o síndico ou a administração cadastra equipamentos. Segredos de acesso ficam em variáveis de ambiente, não aqui.</p>
      <label htmlFor="cam-name">Nome</label>
      <input id="cam-name" maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="cam-entrance">Entrada</label>
      <input id="cam-entrance" maxLength={60} value={entrance} onChange={(e) => setEntrance(e.target.value)} />
      <label htmlFor="cam-adapter">Tipo de integração</label>
      <select id="cam-adapter" value={adapter} onChange={(e) => setAdapter(e.target.value)}>
        <option value="ONVIF">ONVIF</option>
        <option value="GENERIC_HTTP">HTTP genérico</option>
        <option value="AGENT_RELAY">Via agente local</option>
        <option value="SIMULATOR">Simulador (só desenvolvimento)</option>
      </select>
      <label htmlFor="cam-rtsp">Endereço RTSP (opcional, sem usuário e senha)</label>
      <input id="cam-rtsp" maxLength={200} placeholder="rtsp://192.168.0.20:554/stream1" value={rtspUrl}
        onChange={(e) => setRtspUrl(e.target.value)} />
      <label htmlFor="cam-rtsp-env">Variável de ambiente com usuário:senha da câmera (opcional)</label>
      <input id="cam-rtsp-env" maxLength={80} placeholder="CAM_PORTARIA_CRED" value={rtspEnv}
        onChange={(e) => setRtspEnv(e.target.value)} />
      <button disabled={!name.trim() || !entrance.trim()} onClick={register}>Cadastrar câmera</button>
      {apiKey && <p role="status" className="hint">Chave do equipamento (aparece só agora, guarde): <code>{apiKey}</code></p>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
