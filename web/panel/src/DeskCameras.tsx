import type { DeviceFull } from "./types";
import { useCondoList } from "./useCondoList";

/** Câmeras do condomínio. O vídeo ao vivo depende de câmeras RTSP e de um servidor de mídia: ainda não integrado. */
export function DeskCameras({ token, condominiumId }: { token: string; condominiumId: string }) {
  const devices = useCondoList<DeviceFull>(token, `/api/v1/condominiums/${condominiumId}/devices?size=100`, 10000);
  const cameras = devices.items.filter((d) => d.kind === "CAMERA");
  return (
    <div aria-label="Câmeras">
      <h3>Câmeras</h3>
      <p className="badge warn">Vídeo ao vivo não integrado</p>
      <p className="hint">
        O cadastro e o estado das câmeras já funcionam. A imagem ao vivo exige câmeras com RTSP e um servidor de mídia, que dependem de
        equipamento e de decisão de infraestrutura (ver pendencias.md). Nenhuma imagem é simulada aqui.
      </p>
      <ul>
        {cameras.map((c) => (
          <li key={c.id}>
            <strong>{c.name}</strong> ({c.entrance}) · {c.status === "ONLINE" ? "online" : c.status === "OFFLINE" ? "offline" : "desconhecida"}
          </li>
        ))}
        {cameras.length === 0 && <li>Nenhuma câmera cadastrada.</li>}
      </ul>
    </div>
  );
}
