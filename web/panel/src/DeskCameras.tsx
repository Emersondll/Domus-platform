import { useEffect, useState } from "react";
import { CameraCredentials } from "./CameraCredentials";
import { request } from "../../shared/src/api";
import type { DeviceFull } from "./types";
import { CameraPlayer } from "../../shared/src/CameraPlayer";
import { errorMessage, useCondoList } from "./useCondoList";

const MAX_LIVE = 4;
const MAX_RESIDENT = 4; // igual ao limite do servidor (DeviceService.MAX_RESIDENT_CAMERAS)
const STATUS_TEXT: Record<string, string> = { ONLINE: "online", OFFLINE: "offline", UNKNOWN: "estado desconhecido" };

/**
 * Câmeras do condomínio: cadastro (síndico/administração), estado e vídeo ao vivo (HLS via servidor de mídia, quando a
 * câmera tem endereço RTSP). Nenhuma imagem é simulada.
 */
export function DeskCameras({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/devices`;
  const devices = useCondoList<DeviceFull>(token, `${base}?size=100`, 10000);
  const cameras = devices.items.filter((d) => d.kind === "CAMERA");
  const released = cameras.filter((c) => c.residentViewable && c.active !== false);
  // O servidor é quem decide; a tela só esconde o que ele recusaria (síndico ou administração).
  const [canManage, setCanManage] = useState(false);
  const [permsLoaded, setPermsLoaded] = useState(false);
  useEffect(() => {
    request<{ canManage: boolean }>(token, "GET", `${base}/permissions`).then((p) => setCanManage(p?.canManage === true)).catch(() => setCanManage(false)).finally(() => setPermsLoaded(true));
  }, [token, base]);
  const [name, setName] = useState("");
  const [entrance, setEntrance] = useState("");
  const [adapter, setAdapter] = useState("ONVIF");
  const [rtspUrl, setRtspUrl] = useState("");
  const [rtspEnv, setRtspEnv] = useState("");
  const [watching, setWatching] = useState<DeviceFull[]>([]);
  const [editing, setEditing] = useState<DeviceFull | null>(null);
  const [editName, setEditName] = useState("");
  const [editEntrance, setEditEntrance] = useState("");
  const [editRtsp, setEditRtsp] = useState("");
  const [editEnv, setEditEnv] = useState("");
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

  function startEdit(c: DeviceFull) {
    setEditing(c);
    setEditName(c.name);
    setEditEntrance(c.entrance);
    setEditRtsp(c.config?.rtspUrl ?? "");
    setEditEnv(c.config?.rtspCredentialsEnv ?? "");
  }

  async function saveEdit() {
    if (!editing) return;
    setError(null);
    const config: Record<string, string> = {};
    if (editRtsp.trim()) config.rtspUrl = editRtsp.trim();
    if (editRtsp.trim() && editEnv.trim()) config.rtspCredentialsEnv = editEnv.trim();
    try {
      await request(token, "PUT", `${base}/${editing.id}`, { name: editName.trim(), entrance: editEntrance.trim(), config });
      setWatching((list) => list.filter((w) => w.id !== editing.id)); // o vídeo reabre com a configuração nova
      setEditing(null);
      await devices.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  /** Desativar/reativar e marcar a câmera de entrada de visitas. */
  async function act(id: string, what: "active" | "visitor-entrance" | "resident-viewable", body: unknown) {
    setError(null);
    try {
      await request(token, "POST", `${base}/${id}/${what}`, body);
      if (what === "active" && (body as { active: boolean }).active === false) {
        setWatching((list) => list.filter((w) => w.id !== id));
      }
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
      <section aria-label="Câmeras para moradores" className="camera-monitor">
        <h4>Câmeras para moradores: {released.length} de {MAX_RESIDENT} liberadas</h4>
        {released.length === 0 && <p className="hint">Nenhuma câmera liberada: os moradores não veem vídeo. Confira o vídeo com "Ver ao vivo" antes de liberar.</p>}
        <ul>
          {released.map((c) => <li key={c.id}>{c.name} ({c.entrance}){canManage && <>{" "}<button onClick={() => act(c.id, "resident-viewable", { enabled: false })}
            aria-label={`Retirar ${c.name} dos moradores`}>Retirar</button></>}</li>)}
        </ul>
        {!permsLoaded && <p className="hint" role="status">Carregando permissões…</p>}
        {permsLoaded && !canManage && <p className="hint">Só o síndico ou a administração libera câmeras aos moradores.</p>}
        {canManage && released.length >= MAX_RESIDENT && <p className="hint">Limite de {MAX_RESIDENT} câmeras atingido. Retire uma para liberar outra.</p>}
      </section>
      {watching.length > 0 && (
        <div className="camera-grid" aria-label="Câmeras ao vivo">
          {watching.map((w) => (
            <CameraPlayer key={w.id} token={token} name={w.name} sessionUrl={`${base}/${w.id}/stream-session`}
              onClose={() => setWatching((list) => list.filter((x) => x.id !== w.id))} />
          ))}
        </div>
      )}
      {watching.length >= MAX_LIVE && <p className="hint">Limite de {MAX_LIVE} câmeras ao vivo ao mesmo tempo. Feche uma para abrir outra.</p>}
      <ul>
        {cameras.map((c) => (
          <li key={c.id} className={c.active === false ? "inactive" : ""}>
            <strong>{c.name}</strong> ({c.entrance}) · {c.active === false ? "desativada" : (STATUS_TEXT[c.status] ?? "estado desconhecido")}
            {c.visitorEntrance && <span className="badge ok"> câmera de entrada de visitas</span>}{" "}
            {c.residentViewable && <span className="badge ok"> visível ao morador</span>}{" "}
            {c.active !== false && (
              <button onClick={() => refresh(c.id)} aria-label={`Atualizar estado de ${c.name}`}>Atualizar estado</button>
            )}{" "}
            {c.active !== false && (c.liveVideo
              ? <button disabled={watching.some((w) => w.id === c.id) || watching.length >= MAX_LIVE}
                  onClick={() => setWatching((list) => [...list, c])} aria-label={`Ver ao vivo ${c.name}`}>Ver ao vivo</button>
              : <span className="hint">sem vídeo configurado</span>)}{" "}
            {canManage && <><button onClick={() => startEdit(c)} aria-label={`Editar ${c.name}`}>Editar</button>{" "}
            <button onClick={() => act(c.id, "active", { active: c.active === false })}
              aria-label={`${c.active === false ? "Reativar" : "Desativar"} ${c.name}`}>{c.active === false ? "Reativar" : "Desativar"}</button>{" "}
            {c.active !== false && c.liveVideo && (
              <button onClick={() => act(c.id, "resident-viewable", { enabled: !c.residentViewable })}
                disabled={!c.residentViewable && released.length >= MAX_RESIDENT}
                title={!c.residentViewable && released.length >= MAX_RESIDENT ? `Já há ${MAX_RESIDENT} câmeras liberadas. Retire uma antes.` : undefined}
                aria-label={`${c.residentViewable ? "Retirar" : "Liberar"} ${c.name} ${c.residentViewable ? "do" : "para o"} morador`}>
                {c.residentViewable ? "Retirar do morador" : "Liberar ao morador"}
              </button>
            )}{" "}</>}
            {canManage && c.active !== false && (
              <button onClick={() => act(c.id, "visitor-entrance", { enabled: !c.visitorEntrance })}
                aria-label={`${c.visitorEntrance ? "Desmarcar" : "Marcar"} ${c.name} como câmera de entrada de visitas`}>
                {c.visitorEntrance ? "Desmarcar entrada de visitas" : "Marcar como entrada de visitas"}
              </button>
            )}
          </li>
        ))}
        {cameras.length === 0 && <li>Nenhuma câmera cadastrada.</li>}
      </ul>
      {editing && (
        <section aria-label={`Editar ${editing.name}`}>
          <h3>Editar câmera</h3>
          <label htmlFor="edit-name">Novo nome</label>
          <input id="edit-name" maxLength={80} value={editName} onChange={(e) => setEditName(e.target.value)} />
          <label htmlFor="edit-entrance">Nova entrada</label>
          <input id="edit-entrance" maxLength={60} value={editEntrance} onChange={(e) => setEditEntrance(e.target.value)} />
          <label htmlFor="edit-rtsp">Endereço RTSP (sem usuário e senha)</label>
          <input id="edit-rtsp" maxLength={200} value={editRtsp} onChange={(e) => setEditRtsp(e.target.value)} />
          <label htmlFor="edit-env">Variável de ambiente com usuário:senha</label>
          <input id="edit-env" maxLength={80} value={editEnv} onChange={(e) => setEditEnv(e.target.value)} />
          <CameraCredentials token={token} base={base} cameraId={editing.id} cameraName={editing.name} />
          <button disabled={!editName.trim() || !editEntrance.trim()} onClick={saveEdit}>Salvar alterações</button>{" "}
          <button onClick={() => setEditing(null)}>Cancelar</button>
        </section>
      )}
      {canManage && (
        <>
      <h3>Cadastrar câmera</h3>
      <p className="hint">Só o síndico ou a administração cadastra equipamentos. O usuário e a senha da câmera são cadastrados ao editá-la (ficam cifrados no servidor); a variável de ambiente abaixo é a alternativa.</p>
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
        </>
      )}
      {apiKey && <p role="status" className="hint">Chave do equipamento (aparece só agora, guarde): <code>{apiKey}</code></p>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
