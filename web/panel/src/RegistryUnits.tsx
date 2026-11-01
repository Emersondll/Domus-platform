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
  // Só um item em correção por vez: "block:<id>" ou "unit:<id>".
  const [editing, setEditing] = useState<string | null>(null);
  const [editValue, setEditValue] = useState("");

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      setEditing(null);
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
            {editing === `block:${b.id}` ? (
              <>
                <label htmlFor={`block-edit-${b.id}`}>Nome do bloco</label>
                <input id={`block-edit-${b.id}`} maxLength={60} value={editValue} onChange={(e) => setEditValue(e.target.value)} />
                <button disabled={!editValue.trim()} onClick={() => run(() => request(token, "PUT", `${base}/blocks/${b.id}`, { name: editValue.trim() }))}>Salvar</button>{" "}
                <button onClick={() => setEditing(null)}>Cancelar</button>
              </>
            ) : (
              <>
                <strong>{b.name}</strong>{" "}
                <button aria-label={`Corrigir nome do bloco ${b.name}`} onClick={() => { setEditing(`block:${b.id}`); setEditValue(b.name); }}>Corrigir</button>:{" "}
              </>
            )}
            {units.items.filter((u) => u.blockId === b.id).map((u) => (
              editing === `unit:${u.id}` ? (
                <span key={u.id}>
                  <label htmlFor={`unit-edit-${u.id}`}>Número da unidade</label>
                  <input id={`unit-edit-${u.id}`} maxLength={20} value={editValue} onChange={(e) => setEditValue(e.target.value)} />
                  <button disabled={!editValue.trim()} onClick={() => run(() => request(token, "PUT", `${base}/units/${u.id}`, { number: editValue.trim() }))}>Salvar</button>{" "}
                  <button onClick={() => setEditing(null)}>Cancelar</button>{" "}
                </span>
              ) : (
                <button key={u.id} className="chip" aria-label={`Corrigir número da unidade ${u.number} do bloco ${b.name}`}
                  onClick={() => { setEditing(`unit:${u.id}`); setEditValue(u.number); }}>{u.number}</button>
              )
            ))}
            {units.items.every((u) => u.blockId !== b.id) && "sem unidades"}
          </li>
        ))}
        {blocks.items.length === 0 && <li>Nenhum bloco cadastrado.</li>}
      </ul>
      <p className="hint">Para corrigir um nome ou número digitado errado, use "Corrigir" ou clique no número da unidade. A unidade, os moradores e o histórico continuam os mesmos.</p>
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
