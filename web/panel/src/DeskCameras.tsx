import { useState } from "react";
import { request } from "../../shared/src/api";
import type { DeviceFull } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

const STATUS_TEXT: Record<string, string> = { ONLINE: "online", OFFLINE: "offline", UNKNOWN: "estado desconhecido" };

/**
 * Câmeras do condomínio: cadastro (síndico/administração) e estado. O vídeo ao vivo depende de câmeras RTSP e de um
 * servidor de mídia: ainda não integrado, e nenhuma imagem é simulada.
 */
export function DeskCameras({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/devices`;
  const devices = useCondoList<DeviceFull>(token, `${base}?size=100`, 10000);
  const cameras = devices.items.filter((d) => d.kind === "CAMERA");
  const [name, setName] = useState("");
  const [entrance, setEntrance] = useState("");
  const [adapter, setAdapter] = useState("ONVIF");
  const [apiKey, setApiKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function register() {
    setError(null);
    setApiKey(null);
    try {
      const created = await request<DeviceFull & { apiKey?: string }>(token, "POST", base,
        { name: name.trim(), kind: "CAMERA", adapter, entrance: entrance.trim(), config: {} });
      setApiKey(created?.apiKey ?? null);
      setName("");
      setEntrance("");
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
      <p className="badge warn">Vídeo ao vivo não integrado</p>
      <p className="hint">
        Aqui você cadastra as câmeras e acompanha se estão online. A imagem ao vivo exige câmeras com RTSP e um servidor de mídia, que
        dependem de equipamento e de decisão de infraestrutura (ver pendencias.md). Nenhuma imagem é simulada.
      </p>
      <ul>
        {cameras.map((c) => (
          <li key={c.id}>
            <strong>{c.name}</strong> ({c.entrance}) · {STATUS_TEXT[c.status] ?? "estado desconhecido"}{" "}
            <button onClick={() => refresh(c.id)} aria-label={`Atualizar estado de ${c.name}`}>Atualizar estado</button>
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
      <button disabled={!name.trim() || !entrance.trim()} onClick={register}>Cadastrar câmera</button>
      {apiKey && <p role="status" className="hint">Chave do equipamento (aparece só agora, guarde): <code>{apiKey}</code></p>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
