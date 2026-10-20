import type { Overview } from "./types";

function Pending({ label, value }: { label: string; value: string }) {
  return (
    <span className="badge pending" title="Integration not implemented yet">
      {label}: {value === "NOT_IMPLEMENTED" ? "não integrado" : value}
    </span>
  );
}

/** Visão geral dos condomínios autorizados. Saúde de dispositivos e contingência aparecem como pendentes. */
export function OverviewPanel({
  items,
  selected,
  onSelect,
}: {
  items: Overview[];
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <section aria-label="Condomínios">
      <h2>Condomínios</h2>
      <div className="cards">
        {items.map((o) => (
          <button
            key={o.condominiumId}
            className={`card ${selected === o.condominiumId ? "active" : ""}`}
            onClick={() => onSelect(o.condominiumId)}
          >
            <strong>{o.name}</strong>
            <span>Fila: {o.queuedCalls}</span>
            <span>Em atendimento: {o.assignedCalls}</span>
            <span>Incidentes abertos: {o.openIncidents}</span>
            <Pending label="Dispositivos" value={o.deviceHealth} />
            <Pending label="Conectividade" value={o.connectivity} />
          </button>
        ))}
        {items.length === 0 && <p>Nenhum condomínio autorizado.</p>}
      </div>
    </section>
  );
}
