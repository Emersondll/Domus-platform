import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Block, Unit } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

/** Blocos e unidades do condomínio. Só o síndico ou a administração cadastra. */
export function RegistryUnits({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const [blockName, setBlockName] = useState("");
  const [blockId, setBlockId] = useState("");
  const [number, setNumber] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await blocks.reload();
      await units.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Unidades">
      <h3>Blocos e unidades</h3>
      <ul>
        {blocks.items.map((b) => (
          <li key={b.id}>
            <strong>{b.name}</strong>:{" "}
            {units.items.filter((u) => u.blockId === b.id).map((u) => u.number).join(", ") || "sem unidades"}
          </li>
        ))}
        {blocks.items.length === 0 && <li>Nenhum bloco cadastrado.</li>}
      </ul>
      <h3>Novo bloco</h3>
      <label htmlFor="block-name">Nome do bloco ou torre</label>
      <input id="block-name" maxLength={60} value={blockName} onChange={(e) => setBlockName(e.target.value)} />
      <button disabled={!blockName.trim()} onClick={() => run(async () => {
        await request(token, "POST", `${base}/blocks`, { name: blockName.trim() });
        setBlockName("");
      })}>Cadastrar bloco</button>
      <h3>Nova unidade</h3>
      <label htmlFor="unit-block">Bloco</label>
      <select id="unit-block" value={blockId} onChange={(e) => setBlockId(e.target.value)}>
        <option value="">Selecione</option>
        {blocks.items.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
      </select>
      <label htmlFor="unit-number">Número</label>
      <input id="unit-number" maxLength={20} value={number} onChange={(e) => setNumber(e.target.value)} />
      <button disabled={!blockId || !number.trim()} onClick={() => run(async () => {
        await request(token, "POST", `${base}/units`, { blockId, number: number.trim() });
        setNumber("");
      })}>Cadastrar unidade</button>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
