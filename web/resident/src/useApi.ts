import { useCallback, useEffect, useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";

/** Carrega uma lista paginada e permite recarregar após uma ação. */
export function useList<T>(token: string, path: string, pollMs?: number) {
  const [items, setItems] = useState<T[]>([]);
  const [error, setError] = useState<string | null>(null);
  const reload = useCallback(async () => {
    try {
      setItems((await request<Page<T>>(token, "GET", path))?.items ?? []);
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.detail : "Unexpected error");
    }
  }, [token, path]);
  useEffect(() => {
    reload();
    if (!pollMs) return;
    const id = setInterval(reload, pollMs);
    return () => clearInterval(id);
  }, [reload, pollMs]);
  return { items, error, reload };
}

export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 409) return "Conflito: o recurso já existe ou o horário está indisponível.";
    return e.detail;
  }
  return "Erro inesperado";
}
