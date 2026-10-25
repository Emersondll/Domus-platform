import { useState } from "react";
import { DeskAnnouncements } from "./DeskAnnouncements";
import { DeskCameras } from "./DeskCameras";
import { DeskDoors } from "./DeskDoors";
import { DeskParcels } from "./DeskParcels";
import { DeskReports } from "./DeskReports";
import { DeskMessages } from "./DeskMessages";
import { DeskShiftLog } from "./DeskShiftLog";
import { DeskVisitors } from "./DeskVisitors";
import { IncidentsPanel } from "./IncidentsPanel";

/** Seções agrupadas por assunto, para a portaria achar cada coisa sem varrer uma lista longa. */
const GROUPS = [
  { label: "Rotina", tabs: ["Encomendas", "Visitantes", "Livro de turno"] },
  { label: "Segurança", tabs: ["Portas", "Câmeras", "Ocorrências"] },
  { label: "Comunicação", tabs: ["Mensagens", "Comunicados"] },
  { label: "Gestão", tabs: ["Relatórios"] },
] as const;
type Tab = (typeof GROUPS)[number]["tabs"][number];

/** Mesa do condomínio selecionado: o que a portaria acompanha além da fila de chamadas. */
export function CondoDesk({ token, condominiumId, name }: { token: string; condominiumId: string; name: string }) {
  const [tab, setTab] = useState<Tab>("Encomendas");
  return (
    <section aria-label="Mesa do condomínio">
      <h2>{name}</h2>
      <div role="tablist" aria-label="Seções do condomínio" className="tab-groups">
        {GROUPS.map((g) => (
          <div key={g.label} role="group" aria-label={g.label} className="tab-group">
            <span className="tab-group-label">{g.label}</span>
            <div className="tabs">
              {g.tabs.map((t) => (
                <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t}</button>
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
      {tab === "Relatórios" && <DeskReports token={token} condominiumId={condominiumId} />}
    </section>
  );
}
