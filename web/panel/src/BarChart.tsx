export type Bar = { label: string; value: number };

/**
 * Gráfico de barras horizontais sem biblioteca: cada barra é um item de lista com o valor também em texto, então o gráfico
 * não depende de cor nem de imagem (leitor de tela e impressão funcionam). A largura é proporcional ao maior valor.
 */
export function BarChart({ title, bars }: { title: string; bars: Bar[] }) {
  const max = Math.max(1, ...bars.map((b) => b.value));
  return (
    <figure className="barchart">
      <figcaption>{title}</figcaption>
      <ul aria-label={title}>
        {bars.map((b) => (
          <li key={b.label}>
            <span className="barchart-label">{b.label}</span>
            <span className="barchart-track" aria-hidden="true"><span className="barchart-fill" style={{ width: `${(b.value / max) * 100}%` }} /></span>
            <span className="barchart-value">{b.value}</span>
          </li>
        ))}
        {bars.length === 0 && <li>Sem dados no período.</li>}
      </ul>
    </figure>
  );
}
