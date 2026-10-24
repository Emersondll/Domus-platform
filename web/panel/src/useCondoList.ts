import { useCallback, useEffect, useState } from "react";
import { ApiError, request, type Page } from "../../shared/src/api";

/** Lista paginada de um condomínio, com recarga manual e opcionalmente periódica. */
export function useCondoList<T>(token: string, path: string, pollMs?: number) {
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

export function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.detail : "Unexpected error";
}
