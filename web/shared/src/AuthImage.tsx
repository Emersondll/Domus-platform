import { useEffect, useState } from "react";
import { requestImageUrl } from "./api";

/** Imagem que exige o token da API: baixa com Authorization, mostra e libera a URL local ao sair da tela. */
export function AuthImage({ token, path, alt }: { token: string; path: string; alt: string }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let url: string | null = null;
    let stopped = false;
    setSrc(null);
    setFailed(false);
    requestImageUrl(token, path)
      .then((u) => { if (stopped) URL.revokeObjectURL(u); else { url = u; setSrc(u); } })
      .catch(() => { if (!stopped) setFailed(true); });
    return () => { stopped = true; if (url) URL.revokeObjectURL(url); };
  }, [token, path]);
  if (failed) return <p className="hint">Imagem indisponível.</p>;
  if (!src) return <p className="hint">Carregando imagem…</p>;
  return <img src={src} alt={alt} style={{ maxWidth: "100%", borderRadius: 12 }} />;
}
