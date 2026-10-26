import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Parcel, Vehicle } from "./types";
import { errorText, useList } from "./useApi";

export function Parcels({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me/parcels`;
  const pending = useList<Parcel>(token, `${base}?status=RECEIVED&size=100`, 5000).items;
  const done = useList<Parcel>(token, `${base}?status=PICKED_UP&size=20&sort=receivedAt,desc`, 5000).items;
  return (
    <section aria-label="Encomendas">
      <h2>Encomendas</h2>
      <h3>A retirar na portaria ({pending.length})</h3>
      <ul>
        {pending.map((p) => (
          <li key={p.id} className="unread">
            <strong>{p.carrier ?? "Encomenda"}</strong> {p.description ?? ""} · chegou {new Date(p.receivedAt).toLocaleString()}
          </li>
        ))}
        {pending.length === 0 && <li>Nada para retirar.</li>}
      </ul>
      <h3>Já retiradas</h3>
      <ul>
        {done.map((p) => (
          <li key={p.id}>
            {p.carrier ?? "Encomenda"} · recebida {new Date(p.receivedAt).toLocaleString()}
            {p.pickedUpAt ? ` · retirada ${new Date(p.pickedUpAt).toLocaleString()}` : ""}
          </li>
        ))}
        {done.length === 0 && <li>Nenhuma ainda.</li>}
      </ul>
    </section>
  );
}

export function Vehicles({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me/vehicles`;
  const { items, reload } = useList<Vehicle>(token, base);
  const [plate, setPlate] = useState("");
  const [model, setModel] = useState("");
  const [msg, setMsg] = useState<string | null>(null);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    try {
      await request(token, "POST", base, { plate, model: model || null });
      setPlate("");
      setModel("");
      reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  return (
    <section aria-label="Veículos">
      <h2>Veículos</h2>
      <form onSubmit={add}>
        <label htmlFor="plate">Placa</label>
        <input id="plate" required value={plate} onChange={(e) => setPlate(e.target.value)} />
        <label htmlFor="model">Modelo</label>
        <input id="model" value={model} onChange={(e) => setModel(e.target.value)} />
        <button type="submit">Cadastrar veículo</button>
      </form>
      {msg && <p role="alert" className="error">{msg}</p>}
      <ul>
        {items.map((v) => (
          <li key={v.id}>{v.plate} {v.model ?? ""}</li>
        ))}
        {items.length === 0 && <li>Nenhum veículo.</li>}
      </ul>
    </section>
  );
}
