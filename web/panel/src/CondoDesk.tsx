import { useState } from "react";
import { DeskAnnouncements } from "./DeskAnnouncements";
import { CameraMonitor } from "./CameraMonitor";
import { DeskCameras } from "./DeskCameras";
import { DeskDoors } from "./DeskDoors";
import { DeskParcels } from "./DeskParcels";
import { DeskRegistry } from "./DeskRegistry";
import { DeskReports } from "./DeskReports";
import { DeskMessages } from "./DeskMessages";
import { DeskShiftLog } from "./DeskShiftLog";
import { DeskVisitors } from "./DeskVisitors";
import { IncidentsPanel } from "./IncidentsPanel";
import type { AccessRow, Parcel, VisitRequest } from "./types";
import { useCondoList } from "./useCondoList";

/** Seções agrupadas por assunto, para a portaria achar cada coisa sem varrer uma lista longa. */
const GROUPS = [
  { label: "Rotina", tabs: ["Encomendas", "Visitantes", "Livro de turno"] },
  { label: "Segurança", tabs: ["Portas", "Câmeras", "Ocorrências"] },
  { label: "Comunicação", tabs: ["Mensagens", "Comunicados"] },
  { label: "Gestão", tabs: ["Cadastros", "Relatórios"] },
] as const;
type Tab = (typeof GROUPS)[number]["tabs"][number];

/** Mesa do condomínio selecionado: o que a portaria acompanha além da fila de chamadas. */
export function CondoDesk({ token, condominiumId, name, onBack }:
  { token: string; condominiumId: string; name: string; onBack: () => void }) {
  const [tab, setTab] = useState<Tab>("Encomendas");
  const base = `/api/v1/condominiums/${condominiumId}`;
  const parcels = useCondoList<Parcel>(token, `${base}/parcels?status=RECEIVED&size=200`, 10000);
  const onSite = useCondoList<AccessRow>(token, `${base}/access/on-site?size=100`, 10000);
  const asking = useCondoList<VisitRequest>(token, `${base}/visit-requests?size=15`, 5000);
  const waiting = asking.items.filter((r) => r.status === "PENDING").length;
  const withCount = (t: Tab) =>
    t === "Encomendas" && parcels.items.length > 0 ? `${t} (${parcels.items.length})`
      : t === "Visitantes" && waiting > 0 ? `${t} (${waiting})` : t;
  return (
    <section aria-label="Mesa do condomínio">
      <button className="back" onClick={onBack}>← Voltar aos condomínios</button>
      <h2>{name}</h2>
      <div className="desk-summary" role="status" aria-label="Resumo da portaria">
        <button className="card" onClick={() => setTab("Encomendas")}>
          <strong>{parcels.items.length}</strong>
          <span>{parcels.items.length === 1 ? "encomenda a retirar" : "encomendas a retirar"}</span>
        </button>
        <button className="card" onClick={() => setTab("Visitantes")}>
          <strong>{waiting}</strong>
          <span>{waiting === 1 ? "visita aguardando o morador" : "visitas aguardando o morador"}</span>
        </button>
        <button className="card" onClick={() => setTab("Visitantes")}>
          <strong>{onSite.items.length}</strong>
          <span>{onSite.items.length === 1 ? "visitante no local" : "visitantes no local"}</span>
        </button>
      </div>
      <CameraMonitor token={token} condominiumId={condominiumId} />
      <div role="tablist" aria-label="Seções do condomínio" className="tab-groups">
        {GROUPS.map((g) => (
          <div key={g.label} role="group" aria-label={g.label} className="tab-group">
            <span className="tab-group-label">{g.label}</span>
            <div className="tabs">
              {g.tabs.map((t) => (
                <button key={t} role="tab" aria-selected={tab === t} aria-label={t} onClick={() => setTab(t)}>{withCount(t)}</button>
              ))}
            </div>
          </div>
        ))}
      </div>
      {tab === "Encomendas" && <DeskParcels token={token} condominiumId={condominiumId} />}
      {tab === "Visitantes" && <DeskVisitors token={token} condominiumId={condominiumId} />}
      {tab === "Portas" && <DeskDoors token={token} condominiumId={condominiumId} />}
      {tab === "Câmeras" && <DeskCameras token={token} condominiumId={condominiumId} />}
      {tab === "Ocorrências" && <IncidentsPanel token={token} condominiumId={condominiumId} />}
      {tab === "Mensagens" && <DeskMessages token={token} condominiumId={condominiumId} />}
      {tab === "Livro de turno" && <DeskShiftLog token={token} condominiumId={condominiumId} />}
      {tab === "Comunicados" && <DeskAnnouncements token={token} condominiumId={condominiumId} />}
      {tab === "Cadastros" && <DeskRegistry token={token} condominiumId={condominiumId} />}
      {tab === "Relatórios" && <DeskReports token={token} condominiumId={condominiumId} />}
    </section>
  );
}
