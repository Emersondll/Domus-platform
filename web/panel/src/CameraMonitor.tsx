import { useState } from "react";
import type { DeviceFull } from "./types";
import { CameraPlayer } from "./CameraPlayer";
import { useCondoList } from "./useCondoList";

const MAX_LIVE = 4;

/**
 * Monitoramento rápido das câmeras do condomínio, para a mesa e para o atendimento de uma chamada. O vídeo só abre
 * quando o operador pede (cada abertura é auditada e usa banda); não abre sozinho. Cadastro e edição ficam na aba Câmeras.
 */
export function CameraMonitor({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/devices`;
  const devices = useCondoList<DeviceFull>(token, `${base}?size=100`, 30000);
  const cameras = devices.items.filter((d) => d.kind === "CAMERA" && d.active !== false);
  const withVideo = cameras.filter((c) => c.liveVideo);
  const [watching, setWatching] = useState<DeviceFull[]>([]);
  const close = (id: string) => setWatching((list) => list.filter((w) => w.id !== id));
  const openAll = () => setWatching(withVideo.slice(0, MAX_LIVE));

  return (
    <section aria-label="Monitoramento de câmeras" className="camera-monitor">
      <h3>Câmeras do condomínio</h3>
      {cameras.length === 0 && <p className="hint">Nenhuma câmera ativa cadastrada neste condomínio.</p>}
      {cameras.length > 0 && withVideo.length === 0 && (
        <p className="hint">Nenhuma câmera com vídeo configurado. Informe o endereço RTSP na aba Câmeras.</p>
      )}
      <ul>
        {cameras.map((c) => (
          <li key={c.id}>
            {c.liveVideo
              ? <button disabled={watching.some((w) => w.id === c.id) || watching.length >= MAX_LIVE}
                  onClick={() => setWatching((list) => [...list, c])}
                  aria-label={`Monitorar ${c.name}`}>Monitorar {c.name}</button>
              : <strong>{c.name}</strong>}
            {" "}({c.entrance}) · {c.status === "ONLINE" ? "online" : c.status === "OFFLINE" ? "offline" : "estado desconhecido"}
            {!c.liveVideo && <span className="hint"> · sem vídeo configurado</span>}
          </li>
        ))}
      </ul>
      {withVideo.length > 1 && (
        <p>
          <button onClick={openAll}>Abrir até {MAX_LIVE} câmeras</button>{" "}
          {watching.length > 0 && <button onClick={() => setWatching([])}>Fechar todas</button>}
        </p>
      )}
      {watching.length >= MAX_LIVE && <p className="hint">Limite de {MAX_LIVE} câmeras ao vivo ao mesmo tempo. Feche uma para abrir outra.</p>}
      {watching.length > 0 && (
        <div className="camera-grid" aria-label="Câmeras monitoradas">
          {watching.map((w) => (
            <CameraPlayer key={w.id} token={token} name={w.name} sessionUrl={`${base}/${w.id}/stream-session`}
              onClose={() => close(w.id)} />
          ))}
        </div>
      )}
    </section>
  );
}
