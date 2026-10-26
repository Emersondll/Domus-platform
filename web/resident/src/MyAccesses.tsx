import { useEffect, useState } from "react";
import { request, type Page } from "../../shared/src/api";
import type { MyAccess } from "./types";
import { errorText } from "./useApi";
import { RegistrationPhoto } from "./RegistrationPhoto";

type Range = "today" | "7" | "30";
const RANGES: { id: Range; label: string }[] = [
  { id: "today", label: "Hoje" },
  { id: "7", label: "7 dias" },
  { id: "30", label: "30 dias" },
];

export const DIRECTION_LABEL: Record<MyAccess["direction"], string> = { IN: "Entrada", OUT: "Saída", UNKNOWN: "Passagem" };
const METHOD_LABEL: Record<string, string> = { CARD: "cartão", QR: "QR Code", TERMINAL_USER: "terminal", APP: "app", FACE: "rosto" };

function since(range: Range): Date {
  const d = new Date();
  if (range === "today") d.setHours(0, 0, 0, 0);
  else d.setDate(d.getDate() - Number(range));
  return d;
}

/** Agrupa por dia local, mantendo a ordem (mais recente primeiro) que a API entrega. */
export function groupByDay(items: MyAccess[]): { day: string; items: MyAccess[] }[] {
  const groups: { day: string; items: MyAccess[] }[] = [];
  for (const it of items) {
    const day = new Date(it.occurredAt).toLocaleDateString();
    const last = groups[groups.length - 1];
    if (last && last.day === day) last.items.push(it);
    else groups.push({ day, items: [it] });
  }
  return groups;
}

/** "Meus acessos": as entradas e saídas do próprio morador. Só o titular vê esta lista. */
export function MyAccesses({ token, condo }: { token: string; condo: string }) {
  const [range, setRange] = useState<Range>("7");
  const [items, setItems] = useState<MyAccess[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Sem polling: cada consulta fica na auditoria do titular.
  useEffect(() => {
    setItems(null);
    setError(null);
    const from = since(range).toISOString();
    const to = new Date().toISOString();
    request<Page<MyAccess>>(token, "GET", `/api/v1/condominiums/${condo}/me/accesses?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&size=100`)
      .then((p) => setItems(p?.items ?? []))
      .catch((e) => setError(errorText(e)));
  }, [token, condo, range]);

  return (
    <section aria-label="Meus acessos">
      <h2>Meus acessos</h2>
      <p className="hint">Só você vê esta lista. A portaria e o síndico não têm acesso ao seu histórico pessoal.</p>
      <div className="filters" role="group" aria-label="Período">
        {RANGES.map((r) => (
          <button key={r.id} aria-pressed={range === r.id} onClick={() => setRange(r.id)}>{r.label}</button>
        ))}
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {items === null && !error && <p>Carregando…</p>}
      {items !== null && items.length === 0 && (
        <p>Nenhum acesso registrado neste período. Os registros dependem de você ter um cartão ou usuário cadastrado pela administração.</p>
      )}
      {items !== null && groupByDay(items).map((g) => (
        <div key={g.day} className="access-day">
          <h3>{g.day}</h3>
          <ul>
            {g.items.map((a) => (
              <li key={a.id}>
                <strong>{new Date(a.occurredAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</strong>{" "}
                {DIRECTION_LABEL[a.direction]} · {a.entrance} · {METHOD_LABEL[a.method] ?? a.method}
                {a.result === "DENIED" && <span className="badge warn"> acesso negado</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
      <p className="hint">“Passagem” significa que o ponto de acesso não informa se foi entrada ou saída. Os registros são apagados depois de 90 dias.</p>
      <RegistrationPhoto token={token} condo={condo} />
    </section>
  );
}
