import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Parcel, Unit } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

/** Recebimento e entrega de encomendas na portaria. */
export function DeskParcels({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const parcels = useCondoList<Parcel>(token, `${base}/parcels?size=100`, 10000);
  const units = useCondoList<Unit>(token, `${base}/units?size=200`);
  const [unitId, setUnitId] = useState("");
  const [carrier, setCarrier] = useState("");
  const [description, setDescription] = useState("");
  const [picking, setPicking] = useState<string | null>(null);
  const [pickedBy, setPickedBy] = useState("");
  const [error, setError] = useState<string | null>(null);

  const unitNumber = (id: string) => units.items.find((u) => u.id === id)?.number ?? "?";
  const pending = parcels.items.filter((p) => p.status === "RECEIVED");
  const done = parcels.items.filter((p) => p.status !== "RECEIVED").slice(-10).reverse();

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await parcels.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Encomendas">
      <h3>Registrar chegada</h3>
      <label htmlFor="parcel-unit">Unidade</label>
      <select id="parcel-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
        <option value="">Selecione</option>
        {units.items.map((u) => <option key={u.id} value={u.id}>{u.number}</option>)}
      </select>
      <label htmlFor="parcel-carrier">Transportadora</label>
      <input id="parcel-carrier" value={carrier} onChange={(e) => setCarrier(e.target.value)} />
      <label htmlFor="parcel-desc">Descrição</label>
      <input id="parcel-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
      <button
        disabled={!unitId}
        onClick={() =>
          run(async () => {
            await request(token, "POST", `${base}/parcels`, { unitId, carrier: carrier || null, description: description || null });
            setCarrier("");
            setDescription("");
          })
        }
      >
        Registrar encomenda
      </button>
      {error && <p role="alert" className="error">{error}</p>}

      <h3>Aguardando retirada ({pending.length})</h3>
      <ul>
        {pending.map((p) => (
          <li key={p.id}>
            Unidade {unitNumber(p.unitId)} · {p.carrier ?? "Encomenda"} {p.description ?? ""} · {new Date(p.receivedAt).toLocaleString()}
            {picking === p.id ? (
              <span>
                {" "}
                <input aria-label="Quem retirou" value={pickedBy} onChange={(e) => setPickedBy(e.target.value)} />
                <button
                  disabled={!pickedBy.trim()}
                  onClick={() =>
                    run(async () => {
                      await request(token, "POST", `${base}/parcels/${p.id}/pickup`, { pickedUpByName: pickedBy });
                      setPicking(null);
                      setPickedBy("");
                    })
                  }
                >
                  Confirmar entrega
                </button>
              </span>
            ) : (
              <button onClick={() => { setPicking(p.id); setPickedBy(""); }}>Entregar</button>
            )}
          </li>
        ))}
        {pending.length === 0 && <li>Nenhuma encomenda aguardando.</li>}
      </ul>

      <h3>Últimas entregas</h3>
      <ul>
        {done.map((p) => (
          <li key={p.id}>
            Unidade {unitNumber(p.unitId)} · {p.carrier ?? "Encomenda"} · retirada por {p.pickedUpByName ?? "?"}
            {p.pickedUpAt ? ` em ${new Date(p.pickedUpAt).toLocaleString()}` : ""}
          </li>
        ))}
        {done.length === 0 && <li>Nenhuma entrega registrada.</li>}
      </ul>
    </div>
  );
}
