import { useEffect, useRef, useState } from "react";
import { request } from "../../shared/src/api";
import type { Attempt, Preferences, Profile } from "./types";
import { errorText, useList } from "./useApi";

const TYPE_TEXT: Record<string, string> = { OWNER: "proprietário", TENANT: "locatário", SPOUSE: "cônjuge", DEPENDENT: "dependente" };

export function ProfilePage({ token, condo, residentId }: { token: string; condo: string; residentId?: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const [profile, setProfile] = useState<Profile | null>(null);
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  // Campos que a pessoa já mexeu: a resposta do servidor (carga lenta ou renovação do token) não pode sobrescrevê-los.
  const edited = useRef({ email: false, phone: false });
  const [registration, setRegistration] = useState<{ residentType: string; active: boolean } | null>(null);
  const [prefs, setPrefs] = useState<Preferences | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const history = useList<Attempt>(token, `${base}/access-history`);

  useEffect(() => {
    request<Profile>(token, "GET", `${base}/profile`).then((p) => {
      setProfile(p);
      if (!edited.current.email) setEmail(p?.email ?? "");
      if (!edited.current.phone) setPhone(p?.phone ?? "");
    });
    request<Preferences>(token, "GET", `${base}/preferences`).then(setPrefs);
  }, [token, base]);

  useEffect(() => {
    if (!residentId) return;
    request<{ residentType: string; active: boolean }>(token, "GET", `/api/v1/condominiums/${condo}/residents/${residentId}`)
      .then((r) => setRegistration(r ?? null)).catch(() => setRegistration(null));
  }, [token, condo, residentId]);

  async function saveContact(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    try {
      await request(token, "PUT", `${base}/profile`, { email: email || null, phone: phone || null });
      setMsg("Contato atualizado.");
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  async function savePrefs(next: Preferences) {
    setPrefs(next);
    try {
      await request(token, "PUT", `${base}/preferences`, next);
    } catch (err) {
      setMsg(errorText(err));
    }
  }

  return (
    <section aria-label="Perfil">
      <h2>Perfil e privacidade</h2>
      <p>{profile?.fullName}</p>
      {registration && (
        <p className="hint" aria-label="Meu cadastro">
          Meu cadastro: {TYPE_TEXT[registration.residentType] ?? registration.residentType} · {registration.active ? "ativo" : "inativo"}
        </p>
      )}
      <form onSubmit={saveContact}>
        <label htmlFor="email">E-mail</label>
        <input id="email" type="email" value={email} onChange={(e) => { edited.current.email = true; setEmail(e.target.value); }} />
        <label htmlFor="phone">Telefone</label>
        <input id="phone" value={phone} onChange={(e) => { edited.current.phone = true; setPhone(e.target.value); }} />
        <button type="submit">Salvar contato</button>
      </form>
      {msg && <p role="status">{msg}</p>}

      <h3>Preferências de aviso</h3>
      {prefs && (
        <>
          <label className="check">
            <input type="checkbox" checked={prefs.parcelsEnabled}
              onChange={(e) => savePrefs({ ...prefs, parcelsEnabled: e.target.checked })} />
            Avisar sobre encomendas
          </label>
          <label className="check">
            <input type="checkbox" checked={prefs.visitorArrivalEnabled}
              onChange={(e) => savePrefs({ ...prefs, visitorArrivalEnabled: e.target.checked })} />
            Avisar quando meu visitante entrar
          </label>
        </>
      )}

      <h3>Histórico de acessos dos meus convites</h3>
      <ul>
        {history.items.map((a) => (
          <li key={a.id}>
            {new Date(a.occurredAt).toLocaleString()} · {a.entrance} · {a.result === "GRANTED" ? "autorizado" : `negado (${a.reason})`}
          </li>
        ))}
        {history.items.length === 0 && <li>Sem registros.</li>}
      </ul>
    </section>
  );
}
