import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Block, Pet, Unit } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

export const SPECIES_TEXT: Record<string, string> = { DOG: "cachorro", CAT: "gato", BIRD: "ave", OTHER: "outro" };

/** Animais de estimação por unidade. */
export function RegistryPets({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const pets = useCondoList<Pet>(token, `${base}/pets?size=200`);
  const [unitId, setUnitId] = useState("");
  const [name, setName] = useState("");
  const [species, setSpecies] = useState("DOG");
  const [breed, setBreed] = useState("");
  const [notes, setNotes] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const label = (id: string) => {
    const u = units.items.find((x) => x.id === id);
    return u ? `${blocks.items.find((b) => b.id === u.blockId)?.name ?? ""} / ${u.number}` : "?";
  };

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await pets.reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  function startEdit(p: Pet) {
    setEditing(p.id);
    setUnitId(p.unitId);
    setName(p.name);
    setSpecies(p.species);
    setBreed(p.breed ?? "");
    setNotes(p.notes ?? "");
  }

  function clearForm() {
    setEditing(null);
    setName("");
    setBreed("");
    setNotes("");
  }

  return (
    <div aria-label="Animais">
      <h3>Animais de estimação</h3>
      <ul>
        {pets.items.map((p) => (
          <li key={p.id}>
            <strong>{p.name}</strong> · {SPECIES_TEXT[p.species] ?? p.species}{p.breed ? ` (${p.breed})` : ""} · unidade {label(p.unitId)}
            {p.notes ? ` · ${p.notes}` : ""}{" "}
            <button onClick={() => startEdit(p)} aria-label={`Editar ${p.name}`}>Editar</button>{" "}
            <button onClick={() => run(() => request(token, "POST", `${base}/pets/${p.id}/remove`))} aria-label={`Remover ${p.name}`}>Remover</button>
          </li>
        ))}
        {pets.items.length === 0 && <li>Nenhum animal cadastrado.</li>}
      </ul>
      <h3>{editing ? "Editar animal" : "Novo animal"}</h3>
      <label htmlFor="pet-unit">Unidade</label>
      <select id="pet-unit" value={unitId} disabled={!!editing} onChange={(e) => setUnitId(e.target.value)}>
        <option value="">Selecione</option>
        {units.items.map((u) => <option key={u.id} value={u.id}>{label(u.id)}</option>)}
      </select>
      <label htmlFor="pet-name">Nome</label>
      <input id="pet-name" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="pet-species">Espécie</label>
      <select id="pet-species" value={species} onChange={(e) => setSpecies(e.target.value)}>
        {Object.entries(SPECIES_TEXT).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
      </select>
      <label htmlFor="pet-breed">Raça (opcional)</label>
      <input id="pet-breed" maxLength={60} value={breed} onChange={(e) => setBreed(e.target.value)} />
      <label htmlFor="pet-notes">Observação para a portaria (opcional)</label>
      <input id="pet-notes" maxLength={200} value={notes} onChange={(e) => setNotes(e.target.value)} />
      <button disabled={!unitId || !name.trim()} onClick={() => run(async () => {
        const body = { name: name.trim(), species, breed: breed.trim() || null, notes: notes.trim() || null };
        if (editing) {
          await request(token, "PUT", `${base}/pets/${editing}`, { unitId, ...body });
        } else {
          await request(token, "POST", `${base}/pets`, { unitId, ...body });
        }
        clearForm();
      })}>{editing ? "Salvar alterações" : "Cadastrar animal"}</button>
      {editing && <button onClick={clearForm}>Cancelar edição</button>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
