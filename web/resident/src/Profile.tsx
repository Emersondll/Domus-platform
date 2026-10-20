import { useEffect, useState } from "react";
import { request } from "../../shared/src/api";
import type { Attempt, Preferences, Profile } from "./types";
import { errorText, useList } from "./useApi";

export function ProfilePage({ token, condo }: { token: string; condo: string }) {
  const base = `/api/v1/condominiums/${condo}/me`;
  const [profile, setProfile] = useState<Profile | null>(null);
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [prefs, setPrefs] = useState<Preferences | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const history = useList<Attempt>(token, `${base}/access-history`);

  useEffect(() => {
    request<Profile>(token, "GET", `${base}/profile`).then((p) => {
      setProfile(p);
      setEmail(p?.email ?? "");
      setPhone(p?.phone ?? "");
    });
    request<Preferences>(token, "GET", `${base}/preferences`).then(setPrefs);
  }, [token, base]);

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
      <form onSubmit={saveContact}>
        <label htmlFor="email">E-mail</label>
        <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <label htmlFor="phone">Telefone</label>
        <input id="phone" value={phone} onChange={(e) => setPhone(e.target.value)} />
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
