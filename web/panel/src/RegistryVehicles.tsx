import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Block, Unit, Vehicle } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";


/** Veículos cadastrados pelos moradores, com busca por placa; o síndico corrige a placa ou remove (vendido, trocado). */
export function RegistryVehicles({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const [plate, setPlate] = useState("");
  const [search, setSearch] = useState("");
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const vehicles = useCondoList<Vehicle>(token, `${base}/vehicles?size=200${search ? `&plate=${encodeURIComponent(search)}` : ""}`);
  const [editing, setEditing] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [form, setForm] = useState({ plate: "", model: "", color: "" });
  const [error, setError] = useState<string | null>(null);
  const label = (id: string) => {
    const u = units.items.find((x) => x.id === id);
    return u ? `${blocks.items.find((b) => b.id === u.blockId)?.name ?? ""} / ${u.number}` : "?";
  };

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      setEditing(null);
      setRemoving(null);
      await vehicles.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Veículos">
      <h3>Veículos dos moradores</h3>
      <label htmlFor="veh-plate">Buscar por placa</label>
      <input id="veh-plate" maxLength={8} value={plate} onChange={(e) => setPlate(e.target.value)} />
      <button onClick={() => setSearch(plate.trim())}>Buscar</button>{" "}
      {search && <button onClick={() => { setPlate(""); setSearch(""); }}>Limpar busca</button>}
      <ul>
        {vehicles.items.map((v) => (
          <li key={v.id}>
            {v.plate} · unidade {label(v.unitId)}{v.model ? ` · ${v.model}` : ""}{v.color ? ` · ${v.color}` : ""}{" "}
            {editing !== v.id && removing !== v.id && (
              <>
                <button aria-label={`Corrigir veículo ${v.plate}`} onClick={() => { setEditing(v.id); setRemoving(null); setForm({ plate: v.plate, model: v.model ?? "", color: v.color ?? "" }); }}>Corrigir</button>{" "}
                <button aria-label={`Remover veículo ${v.plate}`} onClick={() => { setRemoving(v.id); setEditing(null); }}>Remover</button>
              </>
            )}
            {removing === v.id && (
              <>
                <span>Remover o veículo {v.plate}? </span>
                <button onClick={() => run(() => request(token, "DELETE", `${base}/vehicles/${v.id}`))}>Confirmar remoção</button>{" "}
                <button onClick={() => setRemoving(null)}>Cancelar</button>
              </>
            )}
            {editing === v.id && (
              <div>
                <label htmlFor={`veh-edit-plate-${v.id}`}>Placa</label>
                <input id={`veh-edit-plate-${v.id}`} maxLength={10} value={form.plate} onChange={(e) => setForm({ ...form, plate: e.target.value })} />
                <label htmlFor={`veh-edit-model-${v.id}`}>Modelo</label>
                <input id={`veh-edit-model-${v.id}`} maxLength={60} value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
                <label htmlFor={`veh-edit-color-${v.id}`}>Cor</label>
                <input id={`veh-edit-color-${v.id}`} maxLength={30} value={form.color} onChange={(e) => setForm({ ...form, color: e.target.value })} />
                <button disabled={form.plate.trim().length < 5} onClick={() => run(() => request(token, "PUT", `${base}/vehicles/${v.id}`, {
                  plate: form.plate.trim(), model: form.model.trim() || null, color: form.color.trim() || null }))}>Salvar</button>{" "}
                <button onClick={() => setEditing(null)}>Cancelar</button>
              </div>
            )}
          </li>
        ))}
        {vehicles.items.length === 0 && <li>Nenhum veículo encontrado.</li>}
      </ul>
      {(error || vehicles.error) && <p role="alert" className="error">{error ?? vehicles.error}</p>}
      <p className="hint">Quem cadastra o veículo é o morador, pelo app. Aqui você corrige uma placa errada ou remove um veículo que saiu.</p>
    </div>
  );
}
