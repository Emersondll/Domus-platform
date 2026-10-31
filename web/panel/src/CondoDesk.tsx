import { useState } from "react";
import { DeskAnnouncements } from "./DeskAnnouncements";
import { DeskCalls } from "./DeskCalls";
import { DeskCameras } from "./DeskCameras";
import { DeskDoors } from "./DeskDoors";
import { DeskParcels } from "./DeskParcels";
import { DeskReports } from "./DeskReports";
import { DeskMessages } from "./DeskMessages";
import { DeskShiftLog } from "./DeskShiftLog";
import { DeskVisitors } from "./DeskVisitors";
import { IncidentsPanel } from "./IncidentsPanel";
import type { AccessRow, Parcel, VisitRequest } from "./types";
import { useCondoList } from "./useCondoList";

/** Seções agrupadas por assunto, para a portaria achar cada coisa sem varrer uma lista longa. */
const GROUPS = [
  { label: "Rotina", slug: "rotina", tabs: ["Chamadas", "Encomendas", "Visitantes", "Livro de turno"] },
  { label: "Segurança", slug: "seguranca", tabs: ["Portas", "Câmeras", "Ocorrências"] },
  { label: "Comunicação", slug: "comunicacao", tabs: ["Mensagens", "Comunicados"] },
  { label: "Gestão", slug: "gestao", tabs: ["Relatórios"] },
] as const;
const tabId = (t: string) => `desk-tab-${t.toLowerCase().normalize("NFD").replace(/[^a-z]/g, "")}`;
type Tab = (typeof GROUPS)[number]["tabs"][number];

/** Mesa do condomínio selecionado: o que a portaria acompanha além da fila de chamadas. */
export function CondoDesk({ token, condominiumId, name }:
  { token: string; condominiumId: string; name: string }) {
  const [tab, setTab] = useState<Tab>("Chamadas");
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
      <h2>{name}</h2>
      <div className="desk-summary" role="status" aria-label="Resumo da portaria">
        <button className={`card ${parcels.items.length === 0 ? "zero" : ""}`} onClick={() => setTab("Encomendas")}>
          <strong>{parcels.items.length}</strong>
          <span>{parcels.items.length === 1 ? "encomenda a retirar" : "encomendas a retirar"}</span>
        </button>
        <button className={`card ${waiting > 0 ? "alert" : "zero"}`} onClick={() => setTab("Visitantes")}>
          <strong>{waiting}</strong>
          <span>{waiting === 1 ? "visita aguardando o morador" : "visitas aguardando o morador"}</span>
        </button>
        <button className={`card ${onSite.items.length === 0 ? "zero" : ""}`} onClick={() => setTab("Visitantes")}>
          <strong>{onSite.items.length}</strong>
          <span>{onSite.items.length === 1 ? "visitante no local" : "visitantes no local"}</span>
        </button>
      </div>
      <div role="tablist" aria-label="Seções do condomínio" className="tab-groups">
        {GROUPS.map((g) => (
          <div key={g.label} role="group" aria-label={g.label} className="tab-group" data-group={g.slug}>
            <span className="tab-group-label">{g.label}</span>
            <div className="tabs">
              {g.tabs.map((t) => (
                <button key={t} role="tab" id={tabId(t)} aria-selected={tab === t} aria-controls="desk-panel" aria-label={t} onClick={() => setTab(t)}>{withCount(t)}</button>
              ))}
            </div>
          </div>
        ))}
      </div>
      <div role="tabpanel" id="desk-panel" aria-labelledby={tabId(tab)}>
      {tab === "Chamadas" && <DeskCalls token={token} condominiumId={condominiumId} />}
      {tab === "Encomendas" && <DeskParcels token={token} condominiumId={condominiumId} />}
      {tab === "Visitantes" && <DeskVisitors token={token} condominiumId={condominiumId} />}
      {tab === "Portas" && <DeskDoors token={token} condominiumId={condominiumId} />}
      {tab === "Câmeras" && <DeskCameras token={token} condominiumId={condominiumId} />}
      {tab === "Ocorrências" && <IncidentsPanel token={token} condominiumId={condominiumId} />}
      {tab === "Mensagens" && <DeskMessages token={token} condominiumId={condominiumId} />}
      {tab === "Livro de turno" && <DeskShiftLog token={token} condominiumId={condominiumId} />}
      {tab === "Comunicados" && <DeskAnnouncements token={token} condominiumId={condominiumId} />}
      {tab === "Relatórios" && <DeskReports token={token} condominiumId={condominiumId} />}
      </div>
    </section>
  );
}
