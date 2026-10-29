import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Block, Unit } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

type Area = { id: string; name: string; capacity: number };
type Reservation = { id: string; areaId: string; unitId: string; startAt: string; endAt: string; status: string };

/** Áreas comuns do condomínio e as reservas dos moradores (a equipe pode cancelar). */
export function RegistryAreas({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const areas = useCondoList<Area>(token, `${base}/common-areas?size=100`);
  const reservations = useCondoList<Reservation>(token, `${base}/reservations?size=50`);
  const [name, setName] = useState("");
  const [capacity, setCapacity] = useState(10);
  const [error, setError] = useState<string | null>(null);
  const areaName = (id: string) => areas.items.find((a) => a.id === id)?.name ?? "Área";
  const unitLabel = (id: string) => {
    const u = units.items.find((x) => x.id === id);
    return u ? `${blocks.items.find((b) => b.id === u.blockId)?.name ?? ""} / ${u.number}` : "?";
  };

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await Promise.all([areas.reload(), reservations.reload()]);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Áreas comuns">
      <h3>Áreas comuns</h3>
      <ul>
        {areas.items.map((a) => <li key={a.id}><strong>{a.name}</strong> · até {a.capacity} pessoas</li>)}
        {areas.items.length === 0 && <li>Nenhuma área cadastrada.</li>}
      </ul>
      <h3>Nova área</h3>
      <label htmlFor="area-name">Nome da área</label>
      <input id="area-name" maxLength={80} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="area-capacity">Capacidade (pessoas)</label>
      <input id="area-capacity" type="number" min={1} value={capacity} onChange={(e) => setCapacity(Number(e.target.value))} />
      <button disabled={!name.trim() || capacity < 1} onClick={() => run(async () => {
        await request(token, "POST", `${base}/common-areas`, { name: name.trim(), capacity });
        setName("");
      })}>Cadastrar área</button>
      <h3>Reservas</h3>
      <ul aria-label="Reservas dos moradores">
        {reservations.items.map((r) => (
          <li key={r.id}>
            {areaName(r.areaId)} · unidade {unitLabel(r.unitId)} · {new Date(r.startAt).toLocaleString("pt-BR")} · {r.status === "CONFIRMED" ? "confirmada" : "cancelada"}{" "}
            {r.status === "CONFIRMED" && (
              <button aria-label={`Cancelar reserva de ${areaName(r.areaId)} da unidade ${unitLabel(r.unitId)}`}
                onClick={() => run(() => request(token, "POST", `${base}/reservations/${r.id}/cancel`))}>Cancelar</button>
            )}
          </li>
        ))}
        {reservations.items.length === 0 && <li>Nenhuma reserva.</li>}
      </ul>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
