import Hls from "hls.js";
import { useEffect, useRef, useState } from "react";
import { ApiError, request } from "../../shared/src/api";

type Session = { hlsUrl: string; expiresAt: string };

const CONNECT_TIMEOUT_MS = 20_000;
/** Esperas entre tentativas de reconexão; depois da última o player desiste e pede para fechar e abrir de novo. */
const RETRY_DELAYS_MS = [5_000, 10_000, 20_000, 30_000, 30_000];

/**
 * Vídeo ao vivo de uma câmera (HLS). O endereço RTSP nunca chega ao navegador: o backend entrega uma URL do servidor
 * de mídia com token curto, renovado antes de vencer. Se o servidor de mídia ou a câmera não responderem, mostra o erro
 * em vez de simular imagem.
 */
export function CameraPlayer({ token, sessionUrl, name, onClose }:
  { token: string; sessionUrl: string; name: string; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const region = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("Conectando…");

  useEffect(() => {
    let hls: Hls | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let connectTimer: ReturnType<typeof setTimeout> | undefined;
    let stopped = false;
    let live = false;
    let attempts = 0;
    let currentToken = "";

    /**
     * Falha de conexão (câmera fora do ar, servidor de mídia reiniciando): mostra o erro e tenta de novo com espera
     * crescente, no máximo {@link RETRY_DELAYS_MS} vezes. Cada tentativa abre uma sessão nova (e a registra na auditoria).
     */
    function fail(message: string) {
      if (stopped) return;
      live = false;
      if (connectTimer) clearTimeout(connectTimer);
      if (timer) clearTimeout(timer);
      hls?.destroy();
      hls = null;
      if (attempts >= RETRY_DELAYS_MS.length) {
        setError(`${message} Feche o vídeo e abra de novo.`);
        return;
      }
      setError(`${message} Tentando de novo…`);
      timer = setTimeout(() => { setError(null); setStatus("Reconectando…"); void open(true); }, RETRY_DELAYS_MS[attempts++]);
    }

    async function open(first: boolean) {
      try {
        const session = await request<Session>(token, "POST", sessionUrl);
        if (stopped || !session) return;
        const url = new URL(session.hlsUrl);
        currentToken = url.searchParams.get("token") ?? "";
        if (first && video.current) {
          if (Hls.isSupported()) {
            hls = new Hls({
              // Todo pedido (lista e trechos) leva o token mais recente.
              xhrSetup: (xhr, requestUrl) => {
                const u = new URL(requestUrl);
                u.searchParams.set("token", currentToken);
                xhr.open("GET", u.toString(), true);
              },
            });
            hls.on(Hls.Events.MANIFEST_PARSED, () => {
              live = true;
              attempts = 0;
              if (connectTimer) clearTimeout(connectTimer);
              setStatus("Ao vivo");
              void video.current?.play().catch(() => {});
            });
            hls.on(Hls.Events.ERROR, (_e, data) => {
              if (data.fatal) fail("Sem imagem: a câmera ou o servidor de mídia não respondeu.");
            });
            connectTimer = setTimeout(() => { if (!live) fail("Sem imagem: a conexão demorou demais."); }, CONNECT_TIMEOUT_MS);
            hls.loadSource(session.hlsUrl);
            hls.attachMedia(video.current);
          } else if (video.current.canPlayType("application/vnd.apple.mpegurl")) {
            video.current.src = session.hlsUrl;
            live = true;
            setStatus("Ao vivo");
          } else {
            setError("Este navegador não reproduz HLS.");
          }
        }
        if (!first && !live) return;
        const wait = Math.max(30_000, new Date(session.expiresAt).getTime() - Date.now() - 60_000);
        timer = setTimeout(() => void open(false), wait);
      } catch (e) {
        if (stopped) return;
        const msg = e instanceof ApiError && e.status === 409
          ? "Vídeo indisponível: o servidor de mídia está desligado ou fora do ar, ou a câmera não tem fonte de vídeo."
          : e instanceof ApiError ? e.detail : "Unexpected error";
        // 404 (câmera não liberada/retirada) e 403 não se resolvem sozinhos: não insiste
        if (e instanceof ApiError && e.status !== 409 && e.status < 500) setError(msg);
        else fail(msg);
      }
    }

    void open(true);
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      if (connectTimer) clearTimeout(connectTimer);
      hls?.destroy();
    };
  }, [token, sessionUrl]);

  return (
    <div role="region" aria-label={`Vídeo de ${name}`} ref={region} className="camera-tile">
      <p className="hint">{error ? "Sem imagem" : status} · {name}</p>
      <video ref={video} muted playsInline controls style={{ width: "100%", maxWidth: 640, background: "#000" }} />
      {error && <p role="alert" className="error">{error}</p>}
      <button onClick={() => void region.current?.requestFullscreen?.()} aria-label={`Tela cheia ${name}`}>Tela cheia</button>{" "}
      <button onClick={onClose} aria-label={`Fechar vídeo ${name}`}>Fechar vídeo</button>
    </div>
  );
}
