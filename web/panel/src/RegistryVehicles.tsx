import { useState } from "react";
import type { Block, Unit } from "./types";
import { useCondoList } from "./useCondoList";

type Vehicle = { id: string; unitId: string; plate: string; model: string | null; color: string | null };

/** Veículos cadastrados pelos moradores, com busca por placa (consulta da portaria; o cadastro é feito pelo morador no app). */
export function RegistryVehicles({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const [plate, setPlate] = useState("");
  const [search, setSearch] = useState("");
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const vehicles = useCondoList<Vehicle>(token, `${base}/vehicles?size=200${search ? `&plate=${encodeURIComponent(search)}` : ""}`);
  const label = (id: string) => {
    const u = units.items.find((x) => x.id === id);
    return u ? `${blocks.items.find((b) => b.id === u.blockId)?.name ?? ""} / ${u.number}` : "?";
  };
  return (
    <div aria-label="Veículos">
      <h3>Veículos dos moradores</h3>
      <label htmlFor="veh-plate">Buscar por placa</label>
      <input id="veh-plate" maxLength={8} value={plate} onChange={(e) => setPlate(e.target.value)} />
      <button onClick={() => setSearch(plate.trim())}>Buscar</button>{" "}
      {search && <button onClick={() => { setPlate(""); setSearch(""); }}>Limpar busca</button>}
      <ul>
        {vehicles.items.map((v) => (
          <li key={v.id}>{v.plate} · unidade {label(v.unitId)}{v.model ? ` · ${v.model}` : ""}{v.color ? ` · ${v.color}` : ""}</li>
        ))}
        {vehicles.items.length === 0 && <li>Nenhum veículo encontrado.</li>}
      </ul>
      {vehicles.error && <p role="alert" className="error">{vehicles.error}</p>}
      <p className="hint">Quem cadastra o veículo é o morador, pelo app.</p>
    </div>
  );
}
