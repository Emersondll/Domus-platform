import { useCallback, useEffect, useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";
import type { Incident } from "./types";

/** Incidentes do condomínio selecionado. */
export function IncidentsPanel({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/incidents`;
  const [items, setItems] = useState<Incident[]>([]);
  const [title, setTitle] = useState("");
  const [severity, setSeverity] = useState("MEDIUM");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const page = await request<Page<Incident>>(token, "GET", base);
    setItems(page?.items ?? []);
  }, [token, base]);

  useEffect(() => {
    load().catch(() => setItems([]));
  }, [load]);

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.detail : "Unexpected error");
    }
  }

  return (
    <section aria-label="Incidentes">
      <h2>Incidentes</h2>
      <label htmlFor="inc-title">Título</label>
      <input id="inc-title" value={title} onChange={(e) => setTitle(e.target.value)} />
      <label htmlFor="inc-sev">Gravidade</label>
      <select id="inc-sev" value={severity} onChange={(e) => setSeverity(e.target.value)}>
        <option>LOW</option>
        <option>MEDIUM</option>
        <option>HIGH</option>
      </select>
      <button
        disabled={!title.trim()}
        onClick={() =>
          run(async () => {
            await request(token, "POST", base, { severity, title });
            setTitle("");
          })
        }
      >
        Abrir incidente
      </button>
      {error && <p role="alert" className="error">{error}</p>}
      <ul>
        {items.map((i) => (
          <li key={i.id}>
            [{i.severity}] {i.title} — {i.status}{" "}
            {i.status === "OPEN" && (
              <button onClick={() => run(() => request(token, "POST", `${base}/${i.id}/resolve`))}>Resolver</button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
