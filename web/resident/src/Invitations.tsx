import QRCode from "qrcode";
import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import type { Invitation } from "./types";
import { isExpected } from "./Home";
import { errorText, useList } from "./useApi";

function localInput(d: Date): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Mostra o QR Code. O token não contém dado pessoal e só é exibido neste momento (nunca armazenado no servidor). */
function Qr({ token }: { token: string }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    QRCode.toDataURL(token, { margin: 1, width: 240 }).then(setSrc).catch(() => setSrc(null));
  }, [token]);
  return src ? <img alt="QR Code do convite" src={src} width={240} height={240} /> : <p>Gerando QR Code…</p>;
}

export function Invitations({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me/invitations`;
  const { items, reload, error } = useList<Invitation>(token, base);
  const [name, setName] = useState("");
  const [kind, setKind] = useState("VISITOR");
  const [from, setFrom] = useState(localInput(new Date()));
  const [until, setUntil] = useState(localInput(new Date(Date.now() + 4 * 3600_000)));
  const [maxUses, setMaxUses] = useState(1);
  const [created, setCreated] = useState<Invitation | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  async function create(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    try {
      const inv = await request<Invitation>(token, "POST", base, {
        visitorName: name,
        kind,
        validFrom: new Date(from).toISOString(),
        validUntil: new Date(until).toISOString(),
        maxUses,
      });
      setCreated(inv);
      setName("");
      reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function revoke(id: string) {
    setMsg(null);
    try {
      await request(token, "POST", `${base}/${id}/revoke`);
      if (created?.id === id) setCreated(null);
      reload();
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  return (
    <section aria-label="Convites">
      <h2>Convites</h2>
      <form onSubmit={create}>
        <label htmlFor="v-name">Nome do visitante</label>
        <input id="v-name" required value={name} onChange={(e) => setName(e.target.value)} />
        <label htmlFor="v-kind">Tipo</label>
        <select id="v-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="VISITOR">Visitante</option>
          <option value="SERVICE_PROVIDER">Prestador de serviço</option>
        </select>
        <label htmlFor="v-from">Válido de</label>
        <input id="v-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        <label htmlFor="v-until">Válido até (máx. 7 dias)</label>
        <input id="v-until" type="datetime-local" value={until} onChange={(e) => setUntil(e.target.value)} />
        <label htmlFor="v-uses">Número de usos (1 = uso único)</label>
        <input id="v-uses" type="number" min={1} max={20} value={maxUses} onChange={(e) => setMaxUses(Number(e.target.value))} />
        <button type="submit">Gerar convite</button>
      </form>
      {msg && <p role="alert" className="error">{msg}</p>}
      {error && <p role="alert" className="error">{error}</p>}

      {created?.token && (
        <div className="qr">
          <h3>Convite para {created.visitorName}</h3>
          <Qr token={created.token} />
          <p className="hint">Mostre este QR Code ao visitante. Ele vale somente até {new Date(created.validUntil).toLocaleString()} e
            será exibido apenas agora.</p>
        </div>
      )}

      <h3>Meus convites</h3>
      <ul>
        {items.map((i) => (
          <li key={i.id}>
            <strong>{i.visitorName ?? "Visitante"}</strong> · {new Date(i.validFrom).toLocaleString()} → {new Date(i.validUntil).toLocaleString()} · usos {i.useCount}/{i.maxUses} ·{" "}
            {invitationState(i)}{" "}
            {isExpected(i) && <button onClick={() => revoke(i.id)}>Revogar</button>}
          </li>
        ))}
        {items.length === 0 && <li className="empty">Nenhum convite.</li>}
      </ul>
    </section>
  );
}

/** Situação do convite para o morador: o `status` da API só distingue ativo de revogado; uso e validade vêm dos outros campos. */
function invitationState(i: Invitation): string {
  if (i.status === "REVOKED") return "Revogado";
  if (i.useCount >= i.maxUses) return "Usado";
  if (new Date(i.validUntil).getTime() <= Date.now()) return "Vencido";
  return "Ativo";
}
