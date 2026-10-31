/** Diz o que falta preencher enquanto o botão de enviar está desabilitado (um botão cinza sem explicação confunde). */
export function MissingHint({ missing }: { missing: string[] }) {
  if (missing.length === 0) return null;
  return <p className="missing-hint" role="status">Falta informar: {missing.join(", ")}.</p>;
}
