import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import { CameraPlayer } from "../../shared/src/CameraPlayer";
import { errorText } from "./useApi";
import type { EntranceCamera } from "./types";

const MAX_LIVE = 4;

/**
 * Câmeras de entrada que o síndico liberou para o morador (no máximo 4). O vídeo só abre quando o morador toca em
 * "Ver"; cada abertura fica na auditoria. A portaria vê todas as câmeras ativas, o morador só estas.
 */
export function ResidentCameras({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me/cameras`;
  const [cameras, setCameras] = useState<EntranceCamera[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [aware, setAware] = useState(false); // ciência a cada abertura da tela; não é consentimento jurídico
  const [watching, setWatching] = useState<EntranceCamera[]>([]);
  useEffect(() => {
    request<EntranceCamera[]>(token, "GET", base).then((c) => setCameras(c ?? [])).catch((e) => setError(errorText(e)));
  }, [token, base]);
  const close = (id: string) => setWatching((list) => list.filter((w) => w.id !== id));

  return (
    <section aria-label="Câmeras da entrada">
      <h2>Câmeras da entrada</h2>
      <p className="hint">
        Imagem ao vivo das entradas liberadas pela administração, só para acompanhar a entrada. Cada visualização fica
        registrada em seu nome. Não há gravação.
      </p>
      <label className="check">
        <input type="checkbox" checked={aware} onChange={(e) => setAware(e.target.checked)} />{" "}
        Entendi: as imagens mostram outras pessoas e não vou gravá-las nem compartilhá-las.
      </label>
      {error && <p role="alert" className="error">{error}</p>}
      {!error && cameras.length === 0 && <p>Nenhuma câmera foi liberada para os moradores neste condomínio.</p>}
      <ul>
        {cameras.map((c) => (
          <li key={c.id}>
            <strong>{c.name}</strong> ({c.entrance}){" "}
            <button disabled={!aware || watching.some((w) => w.id === c.id) || watching.length >= MAX_LIVE}
              onClick={() => setWatching((list) => [...list, c])} aria-label={`Ver ${c.name}`}>Ver</button>
          </li>
        ))}
      </ul>
      {watching.length > 0 && (
        <div className="camera-grid" aria-label="Câmeras abertas">
          {watching.map((w) => (
            <CameraPlayer key={w.id} token={token} name={w.name} sessionUrl={`${base}/${w.id}/stream-session`}
              onClose={() => close(w.id)} />
          ))}
        </div>
      )}
    </section>
  );
}
