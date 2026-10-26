import { useCondoList } from "./useCondoList";
import type { Block, Unit } from "./types";

/** Unidades do condomínio com o rótulo "Bloco / número" que a portaria usa para se localizar. */
export function useUnitLabels(token: string, condominiumId: string) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const units = useCondoList<Unit>(token, `${base}/units?size=200`);
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=100`);
  const label = (unit: Unit) => `${blocks.items.find((b) => b.id === unit.blockId)?.name ?? "?"} / ${unit.number}`;
  const labelOf = (unitId: string) => {
    const unit = units.items.find((u) => u.id === unitId);
    return unit ? label(unit) : "?";
  };
  return { units: units.items, label, labelOf };
}
