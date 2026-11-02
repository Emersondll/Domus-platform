import { useState } from "react";
import { request } from "../../shared/src/api";
import type { Block, Resident, Unit } from "./types";
import { errorMessage, useCondoList } from "./useCondoList";
import { RegistryAccessIdentities } from "./RegistryAccessIdentities";

const TYPE_TEXT: Record<string, string> = { OWNER: "proprietário", TENANT: "locatário", SPOUSE: "cônjuge", DEPENDENT: "dependente" };

/** Moradores por unidade: titulares, cônjuge e dependentes; acesso ao app e direitos do titular (LGPD). */
export function RegistryResidents({ token, condominiumId }: { token: string; condominiumId: string }) {
  const base = `/api/v1/condominiums/${condominiumId}`;
  const blocks = useCondoList<Block>(token, `${base}/blocks?size=200`);
  const units = useCondoList<Unit>(token, `${base}/units?size=500`);
  const [unitId, setUnitId] = useState("");
  const residents = useCondoList<Resident>(token, unitId ? `${base}/residents?unitId=${unitId}&size=100` : "");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [type, setType] = useState("OWNER");
  const [accessFor, setAccessFor] = useState<string | null>(null);
  const [identitiesFor, setIdentitiesFor] = useState<string | null>(null);
  const [detail, setDetail] = useState<Resident | null>(null);
  const [moving, setMoving] = useState<{ id: string; toUnit: string; reason: string; dependents: boolean } | null>(null);
  const [subject, setSubject] = useState("");
  const [accountEmail, setAccountEmail] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const blockName = (id: string) => blocks.items.find((b) => b.id === id)?.name ?? "";

  async function run(fn: () => Promise<unknown>, done?: string) {
    setError(null);
    setMessage(null);
    try {
      await fn();
      await residents.reload();
      if (done) setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  const unitLabel = (id: string) => {
    const u = units.items.find((x) => x.id === id);
    return u ? `${blockName(u.blockId)} / ${u.number}` : "?";
  };

  async function confirmMove() {
    if (!moving) return;
    const r = residents.items.find((x) => x.id === moving.id);
    await run(async () => {
      const res = await request<{ dependentsMoved: number; invitationsRevoked: number }>(token, "POST",
        `${base}/residents/${moving.id}/move`, { unitId: moving.toUnit, reason: moving.reason.trim(), moveDependents: moving.dependents });
      setMoving(null);
      setMessage(`${r?.fullName ?? "Morador"} mudou para ${unitLabel(moving.toUnit)}. Convites revogados: ${res?.invitationsRevoked ?? 0}`
        + `; dependentes levados: ${res?.dependentsMoved ?? 0}. Veículos e animais continuam na unidade antiga.`);
    });
  }

  const setActive = (r: Resident, active: boolean) => run(() => request(token, "PUT", `${base}/residents/${r.id}`,
    { fullName: r.fullName, email: r.email, phone: r.phone, residentType: r.residentType, active }));

  return (
    <div aria-label="Moradores">
      <h3>Moradores por unidade</h3>
      <label htmlFor="res-unit">Unidade</label>
      <select id="res-unit" value={unitId} onChange={(e) => { setUnitId(e.target.value); setAccessFor(null); setIdentitiesFor(null); }}>
        <option value="">Selecione</option>
        {units.items.map((u) => <option key={u.id} value={u.id}>{blockName(u.blockId)} / {u.number}</option>)}
      </select>
      {unitId && (
        <>
          <ul>
            {residents.items.map((r) => (
              <li key={r.id}>
                <strong>{r.fullName}</strong> · {TYPE_TEXT[r.residentType] ?? r.residentType}
                {r.phone ? ` · ${r.phone}` : ""} {!r.active && <span className="badge warn">inativo</span>}{" "}
                {r.active ? <button onClick={() => setActive(r, false)} aria-label={`Desativar ${r.fullName}`}>Desativar</button>
                  : <button onClick={() => setActive(r, true)} aria-label={`Reativar ${r.fullName}`}>Reativar</button>}{" "}
                {r.active && r.residentType !== "DEPENDENT" && <button onClick={() => { setAccessFor(r.id); setMessage(null); }} aria-label={`Acesso ao app de ${r.fullName}`}>Acesso ao app</button>}{" "}
                {r.active && <button onClick={() => { setMoving({ id: r.id, toUnit: "", reason: "", dependents: false }); setMessage(null); }}
                  aria-label={`Mudar ${r.fullName} de unidade`}>Mudar de unidade</button>}{" "}
                {r.active && <button onClick={() => setIdentitiesFor(r.id)} aria-label={`Meios de acesso de ${r.fullName}`}>Meios de acesso</button>}{" "}
                <button onClick={async () => {
                  setError(null);
                  try {
                    setDetail((await request<Resident>(token, "GET", `${base}/residents/${r.id}`)) ?? null);
                  } catch (e) {
                    setError(errorMessage(e));
                  }
                }} aria-label={`Detalhes de ${r.fullName}`}>Detalhes</button>{" "}
                <button onClick={async () => {
                  const data = await request<unknown>(token, "GET", `${base}/residents/${r.id}/export`);
                  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = `dados-morador-${r.id}.json`;
                  a.click();
                  URL.revokeObjectURL(url);
                }} aria-label={`Exportar dados de ${r.fullName}`}>Exportar dados</button>{" "}
                <button className="danger" onClick={() => {
                  if (window.confirm(`Anonimizar ${r.fullName}? Os dados pessoais serão apagados e isso não pode ser desfeito.`)) {
                    run(() => request(token, "POST", `${base}/residents/${r.id}/anonymize`), "Cadastro anonimizado.");
                  }
                }} aria-label={`Anonimizar ${r.fullName}`}>Anonimizar</button>
              </li>
            ))}
            {residents.items.length === 0 && <li>Nenhum morador nesta unidade.</li>}
          </ul>
          {moving && residents.items.find((x) => x.id === moving.id) && (
            <section aria-label="Mudar de unidade">
              <h3>Mudar {residents.items.find((x) => x.id === moving.id)!.fullName} de unidade</h3>
              <p className="hint">
                O cadastro, o acesso ao app, os meios de acesso e o histórico dele vão junto. Os convites ativos que ele criou para a unidade
                antiga são revogados. Veículos e animais continuam na unidade antiga (não têm dono individual): ajuste-os depois, se for o caso.
              </p>
              <label htmlFor="mv-unit">Nova unidade</label>
              <select id="mv-unit" value={moving.toUnit} onChange={(e) => setMoving({ ...moving, toUnit: e.target.value })}>
                <option value="">Selecione</option>
                {units.items.filter((u) => u.id !== unitId).map((u) => <option key={u.id} value={u.id}>{blockName(u.blockId)} / {u.number}</option>)}
              </select>
              <label htmlFor="mv-reason">Motivo (sem dados pessoais)</label>
              <input id="mv-reason" maxLength={200} value={moving.reason} onChange={(e) => setMoving({ ...moving, reason: e.target.value })} />
              {residents.items.find((x) => x.id === moving.id)!.residentType !== "DEPENDENT" && (
                <label className="check">
                  <input type="checkbox" checked={moving.dependents} onChange={(e) => setMoving({ ...moving, dependents: e.target.checked })} />{" "}
                  Levar também os dependentes ativos da unidade antiga
                </label>
              )}
              <button disabled={!moving.toUnit || moving.reason.trim().length < 5} onClick={() => void confirmMove()}>Confirmar mudança</button>{" "}
              <button onClick={() => setMoving(null)}>Cancelar</button>
            </section>
          )}
          {detail && (
            <section aria-label="Detalhe do morador">
              <h3>{detail.fullName}</h3>
              <p>{TYPE_TEXT[detail.residentType] ?? detail.residentType} · {detail.active ? "ativo" : "inativo"}</p>
              <p>E-mail: {detail.email ?? "não informado"} · Telefone: {detail.phone ?? "não informado"}</p>
              <button onClick={() => setDetail(null)}>Fechar detalhe</button>
            </section>
          )}
          {identitiesFor && residents.items.find((x) => x.id === identitiesFor) && (
            <RegistryAccessIdentities token={token} condominiumId={condominiumId}
              resident={residents.items.find((x) => x.id === identitiesFor)!} />
          )}
          {accessFor && (
            <section aria-label="Acesso ao app">
              <h3>Dar acesso ao app</h3>
              <p className="hint">Informe o identificador do usuário no provedor de login (Keycloak) e o e-mail da pessoa.</p>
              <label htmlFor="acc-subject">Identificador do usuário</label>
              <input id="acc-subject" value={subject} onChange={(e) => setSubject(e.target.value)} />
              <label htmlFor="acc-email">E-mail</label>
              <input id="acc-email" type="email" value={accountEmail} onChange={(e) => setAccountEmail(e.target.value)} />
              <button disabled={!subject.trim() || !accountEmail.trim()} onClick={() => run(async () => {
                const r = residents.items.find((x) => x.id === accessFor)!;
                await request(token, "POST", `${base}/residents/${accessFor}/account`,
                  { externalSubject: subject.trim(), email: accountEmail.trim(), displayName: r.fullName });
                setSubject("");
                setAccountEmail("");
                setAccessFor(null);
              }, "Acesso ao app concedido.")}>Conceder acesso</button>
            </section>
          )}
          <h3>Novo morador nesta unidade</h3>
          <label htmlFor="res-name">Nome completo</label>
          <input id="res-name" maxLength={160} value={name} onChange={(e) => setName(e.target.value)} />
          <label htmlFor="res-type">Vínculo</label>
          <select id="res-type" value={type} onChange={(e) => setType(e.target.value)}>
            {Object.entries(TYPE_TEXT).map(([v, t]) => <option key={v} value={v}>{t}</option>)}
          </select>
          <label htmlFor="res-email">E-mail (opcional)</label>
          <input id="res-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <label htmlFor="res-phone">Telefone (opcional)</label>
          <input id="res-phone" maxLength={30} value={phone} onChange={(e) => setPhone(e.target.value)} />
          <button disabled={!name.trim()} onClick={() => run(async () => {
            await request(token, "POST", `${base}/residents`,
              { unitId, fullName: name.trim(), email: email.trim() || null, phone: phone.trim() || null, residentType: type });
            setName("");
            setEmail("");
            setPhone("");
          }, "Morador cadastrado.")}>Cadastrar morador</button>
        </>
      )}
      {message && <p role="status" className="hint">{message}</p>}
      {error && <p role="alert" className="error">{error}</p>}
    </div>
  );
}
