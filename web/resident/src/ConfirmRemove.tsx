import { useState } from "react";

/** Botão "Remover" que pede uma segunda confirmação no próprio lugar (evita apagar familiar, animal ou veículo com um toque). */
export function ConfirmRemove({ label, onConfirm }: { label: string; onConfirm: () => void }) {
  const [asking, setAsking] = useState(false);
  if (!asking) return <button aria-label={`Remover ${label}`} onClick={() => setAsking(true)}>Remover</button>;
  return (
    <span role="group" aria-label={`Confirmar remoção de ${label}`}>
      <button className="danger" aria-label={`Confirmar remoção de ${label}`} onClick={() => { setAsking(false); onConfirm(); }}>Confirmar remoção</button>{" "}
      <button onClick={() => setAsking(false)}>Cancelar</button>
    </span>
  );
}
