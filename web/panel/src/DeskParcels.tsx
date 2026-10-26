import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import type { Parcel } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";
import { useUnitLabels } from "./useUnitLabels";

/** Recebimento e entrega de encomendas na portaria. */
export function DeskParcels({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const pendingList = useCondoList<Parcel>(token, `${base}/parcels?status=RECEIVED&size=200`, 10000);
  const doneList = useCondoList<Parcel>(token, `${base}/parcels?status=PICKED_UP&size=10&sort=receivedAt,desc`, 10000);
  const { units, label, labelOf } = useUnitLabels(token, condominiumId);
  const [reminded, setReminded] = useState<string | null>(null);
  const [unitId, setUnitId] = useState("");
  const [carrier, setCarrier] = useState("");
  const [description, setDescription] = useState("");
  const [picking, setPicking] = useState<string | null>(null);
  const [pickedBy, setPickedBy] = useState("");
  const [error, setError] = useState<string | null>(null);

  const [receipt, setReceipt] = useState<Parcel | null>(null);
  useEffect(() => {
    if (!receipt) return;
    window.print(); // impressão pelo navegador; impressora térmica dedicada é item externo
    setReceipt(null);
  }, [receipt]);

  const unitNumber = labelOf;
  const pending = pendingList.items;
  const done = doneList.items;

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await Promise.all([pendingList.reload(), doneList.reload()]);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Encomendas">
      {receipt && (
        <section className="print-only" aria-label="Comprovante de encomenda">
          <h2>Comprovante de recebimento de encomenda</h2>
          <p>Unidade: {unitNumber(receipt.unitId)}</p>
          <p>Transportadora: {receipt.carrier ?? "—"}</p>
          <p>Descrição: {receipt.description ?? "—"}</p>
          <p>Recebida em: {new Date(receipt.receivedAt).toLocaleString()}</p>
          <p>Código: {receipt.id.slice(0, 8).toUpperCase()}</p>
          <p>Assinatura de quem retirou: ________________________________</p>
        </section>
      )}
      <h3>Registrar chegada</h3>
      <label htmlFor="parcel-unit">Unidade</label>
      <select id="parcel-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
        <option value="">Selecione</option>
        {units.map((u) => <option key={u.id} value={u.id}>{label(u)}</option>)}
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
      {pending.length > 0 && (
        <p role="status" className="badge warn">
          {pending.length} {pending.length === 1 ? "encomenda aguardando" : "encomendas aguardando"} retirada na portaria
        </p>
      )}
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
            )}{" "}
            <button onClick={() => setReceipt(p)}>Imprimir comprovante</button>{" "}
            <button aria-label={`Lembrar morador da encomenda ${p.carrier ?? ""} ${unitNumber(p.unitId)}`}
              onClick={() => run(async () => { await request(token, "POST", `${base}/parcels/${p.id}/remind`); setReminded(p.id); })}>
              Lembrar morador
            </button>
            {reminded === p.id && <small> · lembrete enviado</small>}
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
