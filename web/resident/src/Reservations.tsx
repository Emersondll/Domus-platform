import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Area, Reservation } from "./types";
import { errorText, useList } from "./useApi";

export function Reservations({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const areas = useList<Area>(token, `${base}/common-areas`);
  const mine = useList<Reservation>(token, `${base}/reservations`);
  const [areaId, setAreaId] = useState("");
  const [start, setStart] = useState("");
  const [hours, setHours] = useState(2);
  const [msg, setMsg] = useState<string | null>(null);

  async function reserve(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    const startAt = new Date(start);
    try {
      await request(token, "POST", `${base}/reservations`, {
        areaId: areaId || areas.items[0]?.id,
        startAt: startAt.toISOString(),
        endAt: new Date(startAt.getTime() + hours * 3600_000).toISOString(),
      });
      mine.reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function cancel(id: string) {
    try {
      await request(token, "POST", `${base}/reservations/${id}/cancel`);
      mine.reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  const nameOf = (id: string) => areas.items.find((a) => a.id === id)?.name ?? "Área";

  return (
    <section aria-label="Reservas">
      <h2>Reservas</h2>
      {areas.loaded && areas.items.length === 0 && (
        <p role="status">Nenhuma área comum disponível para reserva. O síndico precisa cadastrar as áreas do condomínio.</p>
      )}
      {areas.items.length > 0 && <form onSubmit={reserve}>
        <label htmlFor="r-area">Área</label>
        <select id="r-area" value={areaId} onChange={(e) => setAreaId(e.target.value)}>
          {areas.items.map((a) => (
            <option key={a.id} value={a.id}>{a.name} (até {a.capacity} pessoas)</option>
          ))}
        </select>
        <label htmlFor="r-start">Início</label>
        <input id="r-start" type="datetime-local" required value={start} onChange={(e) => setStart(e.target.value)} />
        <label htmlFor="r-hours">Duração (horas, máx. 12)</label>
        <input id="r-hours" type="number" min={1} max={12} value={hours} onChange={(e) => setHours(Number(e.target.value))} />
        <button type="submit" disabled={areas.items.length === 0}>Reservar</button>
      </form>}
      {msg && <p role="alert" className="error">{msg}</p>}
      <ul>
        {mine.items.map((r) => (
          <li key={r.id}>
            {nameOf(r.areaId)} · {new Date(r.startAt).toLocaleString()} – {new Date(r.endAt).toLocaleTimeString()} · {r.status}{" "}
            {r.status === "CONFIRMED" && <button onClick={() => cancel(r.id)}>Cancelar</button>}
          </li>
        ))}
        {mine.items.length === 0 && <li className="empty">Nenhuma reserva.</li>}
      </ul>
    </section>
  );
}
