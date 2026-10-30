import { useState } from "react";
import { RegistryAreas } from "./RegistryAreas";
import { RegistryPets } from "./RegistryPets";
import { RegistryRequests } from "./RegistryRequests";
import { RegistryResidents } from "./RegistryResidents";
import { RegistryStaff } from "./RegistryStaff";
import { RegistryUnits } from "./RegistryUnits";
import { RegistryVehicles } from "./RegistryVehicles";

const SECTIONS = ["Unidades", "Moradores", "Animais", "Veículos", "Áreas comuns", "Equipe", "Solicitações"] as const;

/** Cadastros do condomínio. Escrever exige o síndico ou a administração; o operador consulta. */
export function DeskRegistry({ token, condominiumId }: { token: string; condominiumId: string }) {
  const [section, setSection] = useState<(typeof SECTIONS)[number]>("Unidades");
  return (
    <div aria-label="Cadastros">
      <p className="hint">Cadastros do condomínio. Só o síndico ou a administração grava; o operador apenas consulta.</p>
      <div className="tabs" role="group" aria-label="Tipos de cadastro">
        {SECTIONS.map((s) => (
          <button key={s} aria-pressed={section === s} onClick={() => setSection(s)}>{s}</button>
        ))}
      </div>
      {section === "Unidades" && <RegistryUnits token={token} condominiumId={condominiumId} />}
      {section === "Moradores" && <RegistryResidents token={token} condominiumId={condominiumId} />}
      {section === "Animais" && <RegistryPets token={token} condominiumId={condominiumId} />}
      {section === "Veículos" && <RegistryVehicles token={token} condominiumId={condominiumId} />}
      {section === "Áreas comuns" && <RegistryAreas token={token} condominiumId={condominiumId} />}
      {section === "Solicitações" && <RegistryRequests token={token} condominiumId={condominiumId} />}
      {section === "Equipe" && <RegistryStaff token={token} condominiumId={condominiumId} />}
    </div>
  );
}
