import { healthSummary } from "./labels";
import type { Overview } from "./types";

/** Faixa de estado da central: o que exige ação agora, somado dos condomínios autorizados. Zero fica apagado; acima de zero ganha cor. */
export function StatusBar({ items }: { items: Overview[] }) {
  const sum = (f: (o: Overview) => number) => items.reduce((n, o) => n + f(o), 0);
  const stats = [
    { label: "Pânico na fila", value: sum((o) => o.queuedPanics), tone: "err" },
    { label: "Chamadas na fila", value: sum((o) => o.queuedCalls), tone: "warn" },
    { label: "Em atendimento", value: sum((o) => o.assignedCalls), tone: "info" },
    { label: "Incidentes abertos", value: sum((o) => o.openIncidents), tone: "warn" },
  ];
  return (
    <div className="status-bar" role="status" aria-label="Estado da central">
      {stats.map((s) => (
        <div key={s.label} className={`stat ${s.value > 0 ? `on ${s.tone}` : "zero"}`}>
          <strong>{s.value}</strong>
          <span>{s.label}</span>
        </div>
      ))}
    </div>
  );
}

function Health({ label, value }: { label: string; value: string }) {
  const bad = /^(OFFLINE|DEGRADED)/.test(value);
  return <span className={bad ? "badge warn" : "badge pending"}>{label}: {healthSummary(value)}</span>;
}

/** Condomínios autorizados para escolher. Os números ficam na faixa de estado; o cartão só destaca o que pede ação. */
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
    <section aria-label="Condomínios" className="condos">
      <h2>Condomínios</h2>
      <div className="cards">
        {items.map((o) => (
          <button
            key={o.condominiumId}
            className={`card ${selected === o.condominiumId ? "active" : ""}`}
            aria-pressed={selected === o.condominiumId}
            onClick={() => onSelect(o.condominiumId)}
          >
            <strong>{o.name}</strong>
            {o.queuedPanics > 0 && <span className="badge err">PÂNICO na fila: {o.queuedPanics}</span>}
            {o.queuedCalls > 0 && <span className="badge warn">Fila: {o.queuedCalls}</span>}
            {o.assignedCalls > 0 && <span className="badge pending">Em atendimento: {o.assignedCalls}</span>}
            {o.openIncidents > 0 && <span className="badge warn">Incidentes abertos: {o.openIncidents}</span>}
            <Health label="Dispositivos" value={o.deviceHealth} />
            <Health label="Conectividade" value={o.connectivity} />
          </button>
        ))}
        {items.length === 0 && <p>Nenhum condomínio autorizado.</p>}
      </div>
    </section>
  );
}
