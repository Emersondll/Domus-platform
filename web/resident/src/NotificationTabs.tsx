import { useState } from "react";
import type { Notification } from "./types";

/** Cada tipo de notificação pertence a um assunto; assim visitas, encomendas e avisos não ficam misturados. */
export const CATEGORIES = [
  { name: "Visitas", types: ["VISIT_REQUEST", "VISITOR_ARRIVED"], empty: "Nenhuma notificação de visitas." },
  { name: "Encomendas", types: ["PARCEL_RECEIVED"], empty: "Nenhuma notificação de encomendas." },
  { name: "Avisos", types: ["ANNOUNCEMENT"], empty: "Nenhum aviso do condomínio." },
  { name: "Mensagens", types: ["DIRECT_MESSAGE"], empty: "Nenhuma mensagem nova." },
  { name: "Portaria", types: ["CALL_ASSIGNED", "CALL_CLOSED", "PANIC_RECEIVED"], empty: "Nada sobre chamadas à portaria." },
] as const;

type Name = (typeof CATEGORIES)[number]["name"];

export function categoryOf(n: Notification): Name {
  return CATEGORIES.find((c) => (c.types as readonly string[]).includes(n.type))?.name ?? "Portaria";
}

/** Notificações separadas por assunto, com a contagem de não lidas em cada aba. */
export function NotificationTabs({ items, onRead }: { items: Notification[]; onRead: (id: string) => void }) {
  const unread = (name: Name) => items.filter((n) => categoryOf(n) === name && !n.readAt).length;
  const [chosen, setChosen] = useState<Name | null>(null);
  // Sem escolha do morador, abre a primeira aba que tem algo novo.
  const current: Name = chosen ?? CATEGORIES.find((c) => unread(c.name) > 0)?.name ?? "Visitas";
  const shown = items.filter((n) => categoryOf(n) === current);
  const empty = CATEGORIES.find((c) => c.name === current)!.empty;

  return (
    <div>
      <div role="tablist" aria-label="Tipos de notificação" className="chips">
        {CATEGORIES.map((c) => (
          <button key={c.name} role="tab" aria-selected={current === c.name} onClick={() => setChosen(c.name)}>
            {c.name}{unread(c.name) > 0 && <span className="count" aria-label={`${unread(c.name)} não lidas`}>{unread(c.name)}</span>}
          </button>
        ))}
      </div>
      <ul>
        {shown.map((n) => (
          <li key={n.id} className={n.readAt ? "" : "unread"}>
            <strong>{n.title}</strong> {n.body} <small>{new Date(n.createdAt).toLocaleString()}</small>{" "}
            {!n.readAt && <button onClick={() => onRead(n.id)}>Marcar como lida</button>}
          </li>
        ))}
        {shown.length === 0 && <li>{empty}</li>}
      </ul>
    </div>
  );
}
