import { friendlyDetail, hasFriendlyDetail } from "../../shared/src/errors";
import { useCallback, useEffect, useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";

/** Carrega uma lista paginada e permite recarregar após uma ação. */
export function useList<T>(token: string, path: string, pollMs?: number) {
  const [items, setItems] = useState<T[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const reload = useCallback(async () => {
    try {
      setItems((await request<Page<T>>(token, "GET", path))?.items ?? []);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
    setLoaded(true);
  }, [token, path]);
  useEffect(() => {
    reload();
    if (!pollMs) return;
    const id = setInterval(reload, pollMs);
    return () => clearInterval(id);
  }, [reload, pollMs]);
  return { items, error, reload, loaded };
}

export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 409 && !hasFriendlyDetail(e.detail)) return "Conflito: o recurso já existe ou o horário está indisponível.";
    return friendlyDetail(e.detail);
  }
  if (e instanceof TypeError) return "Sem conexão com o servidor. Verifique a internet e tente de novo.";
  return "Erro inesperado";
}
