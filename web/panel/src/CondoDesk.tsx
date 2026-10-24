import { useState } from "react";
import { DeskCameras } from "./DeskCameras";
import { DeskDoors } from "./DeskDoors";
import { DeskParcels } from "./DeskParcels";
import { DeskVisitors } from "./DeskVisitors";
import { IncidentsPanel } from "./IncidentsPanel";

const TABS = ["Encomendas", "Visitantes", "Portas", "Câmeras", "Ocorrências"] as const;
type Tab = (typeof TABS)[number];

/** Mesa do condomínio selecionado: o que a portaria acompanha além da fila de chamadas. */
export function CondoDesk({ token, condominiumId, name }: { token: string; condominiumId: string; name: string }) {
  const [tab, setTab] = useState<Tab>("Encomendas");
  return (
    <section aria-label="Mesa do condomínio">
      <h2>{name}</h2>
      <div role="tablist" aria-label="Seções do condomínio" className="tabs">
        {TABS.map((t) => (
          <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}>{t}</button>
        ))}
      </div>
      {tab === "Encomendas" && <DeskParcels token={token} condominiumId={condominiumId} />}
      {tab === "Visitantes" && <DeskVisitors token={token} condominiumId={condominiumId} />}
      {tab === "Portas" && <DeskDoors token={token} condominiumId={condominiumId} />}
      {tab === "Câmeras" && <DeskCameras token={token} condominiumId={condominiumId} />}
      {tab === "Ocorrências" && <IncidentsPanel token={token} condominiumId={condominiumId} />}
    </section>
  );
}
