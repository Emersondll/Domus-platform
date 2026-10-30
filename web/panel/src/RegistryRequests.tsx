import { useCallback, useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import type { Block, Unit } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";

type AssociationRequest = {
  id: string; fullName: string; email: string; phone: string | null; type: string; unitText: string;
  status: "PENDING" | "APPROVED" | "REJECTED"; createdAt: string;
};

const TYPE_TEXT: Record<string, string> = { OWNER: "proprietário", TENANT: "locatário" };

/** Associação de moradores: o síndico divulga o código de adesão e decide os pedidos (escolhendo a unidade real). */
export function RegistryRequests({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const [code, setCode] = useState<string | null>(null);
  const [pending, setPending] = useState<AssociationRequest[]>([]);
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const label = (u: Unit) => `${blocks.items.find((b) => b.id === u.blockId)?.name ?? ""} / ${u.number}`;

  const reload = useCallback(async () => {
    try {
      setCode((await request<{ code: string | null }>(token, "GET", `${base}/join-code`))?.code ?? null);
      setPending((await request<AssociationRequest[]>(token, "GET", `${base}/association-requests`)) ?? []);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, [token, base]);
  useEffect(() => { void reload(); }, [reload]);

  async function run(fn: () => Promise<unknown>) {
    setError(null);
    try {
      await fn();
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div aria-label="Solicitações de acesso">
      <h3>Código de adesão</h3>
      <p className="hint">Quem tem o código pode pedir para entrar no condomínio, mas só passa a ter acesso depois que você aprovar.</p>
      <p>{code ? <>Código atual: <strong aria-label="Código de adesão">{code}</strong></> : "Nenhum código ativo: ninguém consegue pedir acesso."}</p>
      <button onClick={() => run(() => request(token, "POST", `${base}/join-code`))}>{code ? "Gerar novo código" : "Gerar código"}</button>{" "}
      {code && <button onClick={() => run(() => request(token, "POST", `${base}/join-code/disable`))}>Desligar código</button>}

      <h3>Pedidos de acesso</h3>
      <ul>
        {pending.map((r) => (
          <li key={r.id}>
            <strong>{r.fullName}</strong> · {TYPE_TEXT[r.type] ?? r.type} · informou "{r.unitText}" · {r.email}{r.phone ? ` · ${r.phone}` : ""}{" "}
            <select aria-label={`Unidade de ${r.fullName}`} value={chosen[r.id] ?? ""}
              onChange={(e) => setChosen({ ...chosen, [r.id]: e.target.value })}>
              <option value="">Unidade do morador</option>
              {units.items.map((u) => <option key={u.id} value={u.id}>{label(u)}</option>)}
            </select>{" "}
            <button disabled={!chosen[r.id]} aria-label={`Aprovar ${r.fullName}`}
              onClick={() => run(() => request(token, "POST", `${base}/association-requests/${r.id}/approve`, { unitId: chosen[r.id] }))}>Aprovar</button>{" "}
            <button aria-label={`Recusar ${r.fullName}`}
              onClick={() => run(() => request(token, "POST", `${base}/association-requests/${r.id}/reject`))}>Recusar</button>
          </li>
        ))}
        {pending.length === 0 && <li>Nenhum pedido aguardando.</li>}
      </ul>
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
