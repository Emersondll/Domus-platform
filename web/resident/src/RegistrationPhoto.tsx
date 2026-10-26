import { useEffect, useState } from "react";
import { request, uploadFile } from "../../shared/src/api";
import { AuthImage } from "../../shared/src/AuthImage";
import type { PhotoStatus } from "./types";
import { errorText } from "./useApi";

/**
 * Foto de cadastro do morador. Só aparece quando o recurso está ligado na instalação. Mostra, antes de qualquer envio, o
 * que será feito com a imagem (transparência) e só envia depois do aceite específico, que pode ser revogado.
 */
export function RegistrationPhoto({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const [status, setStatus] = useState<PhotoStatus | null>(null);
  const [agreed, setAgreed] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [version, setVersion] = useState(0); // força recarregar a imagem depois de enviar

  const load = () => request<PhotoStatus>(token, "GET", `${base}/photo/status`).then(setStatus).catch(() => setStatus(null));
  useEffect(() => { load(); }, [token, base]);

  if (!status?.enabled) return null;

  async function grant() {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/consents`, { textVersion: status!.textVersion });
      setAgreed(false);
      await load();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function revoke() {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/consents/${status!.consentId}/revoke`);
      setMsg("Consentimento revogado e foto apagada.");
      await load();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function send(file: File | undefined) {
    if (!file) return;
    setMsg(null);
    try {
      await uploadFile(token, `${base}/photo`, file);
      setVersion((v) => v + 1);
      setMsg("Foto enviada.");
      await load();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function remove() {
    setMsg(null);
    try {
      await request(token, "DELETE", `${base}/photo`);
      setMsg("Foto removida.");
      await load();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  return (
    <section aria-label="Sua foto de cadastro" className="photo-card">
      <h3>Sua foto de cadastro</h3>
      {status.consentId === null ? (
        <>
          <p>{status.text}</p>
          <p className="hint">Para que serve: só controle de acesso. Quem vê: apenas você. Por quanto tempo: até você remover ou revogar. Alternativa sem foto: cartão ou QR Code.</p>
          <label>
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} /> Li e autorizo o uso da minha foto para esta finalidade
          </label>
          <button onClick={grant} disabled={!agreed}>Autorizar</button>
        </>
      ) : (
        <>
          {status.hasPhoto ? (
            <AuthImage key={version} token={token} path={`${base}/photo`} alt="Sua foto de cadastro" />
          ) : (
            <p>Nenhuma foto enviada ainda.</p>
          )}
          <p className="hint">Use uma foto de rosto, de frente, sem óculos escuros. Não verificamos o rosto: a qualidade depende da foto que você enviar.</p>
          <label htmlFor="photo-file">{status.hasPhoto ? "Trocar foto" : "Enviar foto"}</label>
          <input id="photo-file" type="file" accept="image/jpeg,image/png" onChange={(e) => send(e.target.files?.[0])} />
          {status.hasPhoto && <button onClick={remove}>Remover minha foto</button>}
          <button className="danger" onClick={revoke}>Revogar consentimento</button>
        </>
      )}
      {msg && <p role="status">{msg}</p>}
    </section>
  );
}
