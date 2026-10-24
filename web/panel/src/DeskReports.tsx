import { useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";
import type { AccessRow } from "./types";
import { errorMessage } from "./useCondoList";

const day = (d: Date) => d.toISOString().slice(0, 10);
const RESULT_TEXT: Record<string, string> = { GRANTED: "liberada", DENIED: "negada" };

/** Relatório de acessos por período, com exportação CSV. O período máximo é de 31 dias (limite da API). */
export function DeskReports({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}/access`;
  const today = new Date();
  const [from, setFrom] = useState(day(new Date(today.getTime() - 6 * 86400000)));
  const [to, setTo] = useState(day(today));
  const [result, setResult] = useState("");
  const [rows, setRows] = useState<AccessRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  // O fim é exclusivo na API: o dia final inteiro entra até 00:00 do dia seguinte.
  const query = () => {
    const start = new Date(`${from}T00:00:00`).toISOString();
    const end = new Date(new Date(`${to}T00:00:00`).getTime() + 86400000).toISOString();
    return `from=${encodeURIComponent(start)}&to=${encodeURIComponent(end)}${result ? `&result=${result}` : ""}`;
  };

  async function search() {
    setError(null);
    try {
      const page = await request<Page<AccessRow>>(token, "GET", `${base}/report?${query()}&size=200`);
      setRows(page?.items ?? []);
    } catch (e) {
      setRows(null);
      setError(e instanceof ApiError && e.status === 400 ? "Período inválido: use no máximo 31 dias." : errorMessage(e));
    }
  }

  async function exportCsv() {
    setError(null);
    try {
      const res = await fetch(`${base}/report.csv?${query()}`, { headers: { Authorization: `Bearer ${token}` } });
      if (!res.ok) throw new ApiError(res.status, res.status === 400 ? "Período inválido ou grande demais: reduza o período." : res.statusText);
      const link = document.createElement("a");
      link.href = URL.createObjectURL(await res.blob());
      link.download = `acessos-${from}-a-${to}.csv`;
      link.click();
      URL.revokeObjectURL(link.href);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Relatórios">
      <h3>Relatório de acessos</h3>
      <label htmlFor="rep-from">De</label>
      <input id="rep-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
      <label htmlFor="rep-to">Até</label>
      <input id="rep-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      <label htmlFor="rep-result">Resultado</label>
      <select id="rep-result" value={result} onChange={(e) => setResult(e.target.value)}>
        <option value="">Todos</option>
        <option value="GRANTED">Liberadas</option>
        <option value="DENIED">Negadas</option>
      </select>
      <button onClick={search}>Consultar</button>
      <button onClick={exportCsv}>Exportar CSV</button>
      <p className="hint">A exportação contém nomes de visitantes (dado pessoal) e fica registrada na auditoria.</p>
      {error && <p role="alert" className="error">{error}</p>}
      {rows && (
        <ul>
          {rows.map((r) => (
            <li key={r.attemptId}>
              {new Date(r.occurredAt).toLocaleString()} · {r.entrance} ·{" "}
              <span className={r.result === "GRANTED" ? "badge ok" : "badge warn"}>{RESULT_TEXT[r.result] ?? r.result}</span>
              {r.result !== "GRANTED" && ` (${r.reason})`}
              {r.unitLabel && ` · unidade ${r.unitLabel}`}
              {r.visitorName && ` · ${r.visitorName}`}
              {r.exitedAt && ` · saiu ${new Date(r.exitedAt).toLocaleString()}`}
            </li>
          ))}
          {rows.length === 0 && <li>Nenhum acesso no período.</li>}
        </ul>
      )}
    </div>
  );
}
