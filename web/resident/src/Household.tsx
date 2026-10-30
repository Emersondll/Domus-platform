import { useCallback, useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import type { HouseholdMember, Pet } from "./types";
import { errorText } from "./useApi";

const REL_TEXT: Record<string, string> = { OWNER: "proprietário", TENANT: "locatário", SPOUSE: "cônjuge", DEPENDENT: "dependente" };
const SPECIES_TEXT: Record<string, string> = { DOG: "cachorro", CAT: "gato", BIRD: "ave", OTHER: "outro" };

/** Carrega uma lista simples (sem paginação) e permite recarregar. */
function useArray<T>(token: string, path: string) {
  const [items, setItems] = useState<T[]>([]);
  const reload = useCallback(async () => {
    try {
      setItems((await request<T[]>(token, "GET", path)) ?? []);
    } catch {
      setItems([]);
    }
  }, [token, path]);
  useEffect(() => { reload(); }, [reload]);
  return { items, reload };
}

/** Minha casa: família (cônjuge e dependentes) e animais de estimação da unidade. */
export function Household({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const family = useArray<HouseholdMember>(token, `${base}/household`);
  const pets = useArray<Pet>(token, `${base}/pets`);
  const [name, setName] = useState("");
  const [rel, setRel] = useState("SPOUSE");
  const [phone, setPhone] = useState("");
  const [petName, setPetName] = useState("");
  const [species, setSpecies] = useState("DOG");
  const [breed, setBreed] = useState("");
  const [notes, setNotes] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<unknown>, after: () => Promise<void>) {
    setError(null);
    try {
      await fn();
      await after();
    } catch (e) {
      setError(errorText(e));
    }
  }

  function startEdit(p: Pet) {
    setEditing(p.id);
    setPetName(p.name);
    setSpecies(p.species);
    setBreed(p.breed ?? "");
    setNotes(p.notes ?? "");
  }

  function clearPetForm() {
    setEditing(null);
    setPetName("");
    setSpecies("DOG");
    setBreed("");
    setNotes("");
  }

  return (
    <section aria-label="Minha casa">
      <h2>Minha casa</h2>
      <h3>Família</h3>
      <p className="hint">Cônjuge e dependentes ficam no cadastro da unidade para a portaria reconhecer. O acesso ao app é liberado pela administração.</p>
      <ul>
        {family.items.map((m) => (
          <li key={m.id}>
            <strong>{m.fullName}</strong> · {REL_TEXT[m.relationship] ?? m.relationship}{m.phone ? ` · ${m.phone}` : ""}{" "}
            {m.removable && (
              <button aria-label={`Remover ${m.fullName}`}
                onClick={() => run(() => request(token, "POST", `${base}/household/${m.id}/remove`), family.reload)}>Remover</button>
            )}
          </li>
        ))}
        {family.items.length === 0 && <li>Ninguém cadastrado.</li>}
      </ul>
      <label htmlFor="fam-name">Nome completo</label>
      <input id="fam-name" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} />
      <label htmlFor="fam-rel">Vínculo</label>
      <select id="fam-rel" value={rel} onChange={(e) => setRel(e.target.value)}>
        <option value="SPOUSE">Cônjuge</option>
        <option value="DEPENDENT">Dependente (filho ou outro familiar)</option>
      </select>
      <label htmlFor="fam-phone">Telefone (opcional)</label>
      <input id="fam-phone" maxLength={30} value={phone} onChange={(e) => setPhone(e.target.value)} />
      <button disabled={!name.trim()} onClick={() => run(async () => {
        await request(token, "POST", `${base}/household`, { fullName: name.trim(), relationship: rel, phone: phone.trim() || null });
        setName("");
        setPhone("");
      }, family.reload)}>Adicionar pessoa</button>

      <h3>Animais</h3>
      <ul>
        {pets.items.map((p) => (
          <li key={p.id}>
            <strong>{p.name}</strong> · {SPECIES_TEXT[p.species] ?? p.species}{p.breed ? ` (${p.breed})` : ""}{p.notes ? ` · ${p.notes}` : ""}{" "}
            <button aria-label={`Editar ${p.name}`} onClick={() => startEdit(p)}>Editar</button>{" "}
            <button aria-label={`Remover ${p.name}`}
              onClick={() => run(() => request(token, "POST", `${base}/pets/${p.id}/remove`), pets.reload)}>Remover</button>
          </li>
        ))}
        {pets.items.length === 0 && <li>Nenhum animal cadastrado.</li>}
      </ul>
      <label htmlFor="pet-name">Nome do animal</label>
      <input id="pet-name" maxLength={60} value={petName} onChange={(e) => setPetName(e.target.value)} />
      <label htmlFor="pet-species">Espécie</label>
      <select id="pet-species" value={species} onChange={(e) => setSpecies(e.target.value)}>
        {Object.entries(SPECIES_TEXT).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
      </select>
      <label htmlFor="pet-breed">Raça (opcional)</label>
      <input id="pet-breed" maxLength={60} value={breed} onChange={(e) => setBreed(e.target.value)} />
      <label htmlFor="pet-notes">Observação para a portaria (opcional)</label>
      <input id="pet-notes" maxLength={200} value={notes} onChange={(e) => setNotes(e.target.value)} />
      <button disabled={!petName.trim()} onClick={() => run(async () => {
        const body = { name: petName.trim(), species, breed: breed.trim() || null, notes: notes.trim() || null };
        if (editing) {
          await request(token, "PUT", `${base}/pets/${editing}`, body);
        } else {
          await request(token, "POST", `${base}/pets`, body);
        }
        clearPetForm();
      }, pets.reload)}>{editing ? "Salvar alterações do animal" : "Adicionar animal"}</button>
      {editing && <button onClick={clearPetForm}>Cancelar edição</button>}
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  );
}
