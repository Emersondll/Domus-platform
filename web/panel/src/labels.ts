/** Textos em português para estados que a API devolve como constante em inglês. O valor enviado à API não muda. */
const HEALTH: Record<string, string> = { ONLINE: "online", OFFLINE: "offline", UNKNOWN: "desconhecido", DEGRADED: "degradado", NOT_IMPLEMENTED: "não integrado", NO_AGENT: "sem agente", ONLINE_AGENT: "agente online" };
const SEVERITY: Record<string, string> = { LOW: "Baixa", MEDIUM: "Média", HIGH: "Alta" };
const OUTCOME: Record<string, string> = {
  RESOLVED: "Resolvido", ENTRY_GRANTED: "Entrada liberada", ENTRY_DENIED: "Entrada negada", NO_ANSWER: "Sem resposta", FALSE_ALARM: "Alarme falso",
};

export const healthLabel = (s: string) => HEALTH[s] ?? s;
export const severityLabel = (s: string) => SEVERITY[s] ?? s;
export const outcomeLabel = (s: string) => OUTCOME[s] ?? s;
/** "UNKNOWN 12/24" → "desconhecido 12/24". */
export function healthSummary(v: string): string {
  const m = /^([A-Z_]+)(.*)$/.exec(v);
  return m ? `${healthLabel(m[1])}${m[2]}` : v;
}
