import { useEffect, useId, useState } from "react";
import { request } from "../../shared/src/api";
import { AuthImage } from "../../shared/src/AuthImage";
import { MissingHint } from "./MissingHint";
import { errorMessage } from "./useCondoList";
import { useUnitLabels } from "./useUnitLabels";

/**
 * Pedido de autorização ao morador (visita ou prestador): o único formulário para isso, usado na aba Visitantes e dentro de um
 * atendimento. No atendimento (`callId`) o pedido fica ligado à chamada e a unidade pode já vir definida (`fixedUnitId`).
 * A resposta do morador não abre a porta: quem libera é o operador.
 */
export function VisitRequestForm({ token, condominiumId, callId = null, fixedUnitId = null, onAsked }:
  { token: string; condominiumId: string; callId?: string | null; fixedUnitId?: string | null; onAsked: () => void }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const uid = useId();
  const { units, label } = useUnitLabels(token, condominiumId);
  const [unitId, setUnitId] = useState(fixedUnitId ?? "");
  const [name, setName] = useState("");
  const [company, setCompany] = useState("");
  const [kind, setKind] = useState("VISITOR");
  const [avail, setAvail] = useState<{ enabled: boolean; entrances: string[] }>({ enabled: false, entrances: [] });
  const [snapEntrance, setSnapEntrance] = useState("");
  const [snap, setSnap] = useState<{ id: string; camera: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    request<{ enabled: boolean; entrances: string[] }>(token, "GET", `${base}/entrance-snapshots/availability`)
      .then((a) => setAvail(a ?? { enabled: false, entrances: [] })).catch(() => {});
  }, [token, base]);

  async function captureImage() {
    setError(null);
    try {
      const c = await request<{ id: string; cameraName: string }>(token, "POST", `${base}/entrance-snapshots`,
        { entrance: snapEntrance || null });
      if (c) setSnap({ id: c.id, camera: c.cameraName });
    } catch {
      setSnap(null);
      setError("Não foi possível capturar a imagem: confira se a câmera de entrada está ativa e com vídeo. O aviso pode ser enviado sem imagem.");
    }
  }

  /** Prestador chega com a empresa ou o serviço entre parênteses, para o morador reconhecer quem é. */
  function visitorLabel() {
    const c = company.trim();
    return c ? `${name.trim()} (${c})` : name.trim();
  }

  async function ask() {
    setError(null);
    try {
      await request(token, "POST", `${base}/visit-requests`, { unitId, visitorName: visitorLabel(), kind, callId,
        ...(snap ? { snapshotId: snap.id } : {}) });
      setSnap(null);
      setName("");
      setCompany("");
      onAsked();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  const missing = [!unitId && "a unidade", !name.trim() && "o nome do visitante"].filter(Boolean) as string[];

  return (
    <div aria-label="Pedido de autorização" className="visit-form">
      {!fixedUnitId && (
        <>
          <label htmlFor={`${uid}-unit`}>Unidade visitada</label>
          <select id={`${uid}-unit`} value={unitId} onChange={(e) => setUnitId(e.target.value)}>
            <option value="">Selecione</option>
            {units.map((u) => <option key={u.id} value={u.id}>{label(u)}</option>)}
          </select>
        </>
      )}
      <label className="required" htmlFor={`${uid}-name`}>Nome do visitante</label>
      <input id={`${uid}-name`} maxLength={160} required value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor={`${uid}-kind`}>Tipo</label>
      <select id={`${uid}-kind`} value={kind} onChange={(e) => setKind(e.target.value)}>
        <option value="VISITOR">Visita (pessoa conhecida)</option>
        <option value="SERVICE_PROVIDER">Prestador de serviço</option>
      </select>
      {kind === "SERVICE_PROVIDER" && (
        <>
          <label htmlFor={`${uid}-company`}>Empresa ou serviço (opcional)</label>
          <input id={`${uid}-company`} maxLength={80} placeholder="Ex.: Net, encanador, entrega de gás" value={company}
            onChange={(e) => setCompany(e.target.value)} />
        </>
      )}
      {avail.enabled && avail.entrances.length > 0 && (
        <div aria-label="Imagem da entrada">
          <h4>Imagem da entrada</h4>
          <p className="hint">Capture a imagem da câmera de entrada e confira se mostra a pessoa certa: ela vai junto do aviso e o morador a vê no app. A imagem é apagada depois do prazo de retenção.</p>
          {avail.entrances.length > 1 && (
            <>
              <label htmlFor={`${uid}-snap`}>Entrada</label>
              <select id={`${uid}-snap`} value={snapEntrance} onChange={(e) => { setSnapEntrance(e.target.value); setSnap(null); }}>
                <option value="">Selecione</option>
                {avail.entrances.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
            </>
          )}
          <button disabled={avail.entrances.length > 1 && !snapEntrance} onClick={captureImage}>
            {snap ? "Capturar de novo" : "Capturar imagem da entrada"}
          </button>
          {snap && (
            <div role="group" aria-label="Prévia da imagem">
              <AuthImage token={token} path={`${base}/entrance-snapshots/${snap.id}/image`} alt={`Prévia da imagem de ${snap.camera}`} />
              <p className="hint">Câmera: {snap.camera}. Esta imagem será enviada com o aviso.</p>
              <button onClick={() => setSnap(null)}>Descartar imagem</button>
            </div>
          )}
        </div>
      )}
      <button className="primary" disabled={missing.length > 0} onClick={ask}>
        {snap ? "Pedir autorização ao morador (com imagem)" : "Pedir autorização ao morador"}
      </button>
      <MissingHint missing={missing} />
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
