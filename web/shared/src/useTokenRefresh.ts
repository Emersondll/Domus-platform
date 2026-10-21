import { useEffect } from "react";
import { setTokenRefresher } from "./api";
import { refreshAccessToken, scheduleRefresh, type OidcConfig } from "./oidc";

/**
 * Mantém o access token válido enquanto há sessão: renova em segundo plano antes de expirar e também quando a API
 * responde 401. Sem OIDC (desenvolvimento) não faz nada.
 */
export function useTokenRefresh(oidc: OidcConfig | null | undefined, signedIn: boolean, onToken: (token: string) => void): void {
  useEffect(() => {
    if (!oidc || !signedIn) return;
    setTokenRefresher(async () => {
      const t = await refreshAccessToken(oidc);
      if (t) onToken(t);
      return t;
    });
    const cancel = scheduleRefresh(oidc, onToken);
    return () => {
      cancel();
      setTokenRefresher(null);
    };
    // onToken muda de identidade a cada render; o efeito só deve reagir a oidc/sessão.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [oidc, signedIn]);
}
