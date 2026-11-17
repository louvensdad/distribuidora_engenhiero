import { formatMoney } from "../../app/format";
import type { ReportColumn } from "../../api/types";

const COLOR: Record<string, string> = {
  net_revenue: "var(--eng-cyan)", cogs: "var(--eng-violet)", expenses_total: "var(--eng-warning)",
  losses: "var(--eng-error)", profit: "var(--eng-green)",
};

/**
 * Colunas extrudadas (REP-03, design/01 "Gráficos 3D"): a ALTURA da face frontal é o valor, numa escala única (zero na
 * linha de base; negativo desce). Topo e lateral só dão profundidade — deslocamento fixo, igual para todas, sem mudar a
 * proporção nem usar volume. Rótulo direto com o mesmo texto da tabela. Em "2D" as faces somem (impressão, acessibilidade,
 * baixa capacidade). Sem animação (respeita redução de movimento por construção) e sem WebGL: é SVG vetorial.
 */
export function Columns3D({ columns, title, subtitle, flat = false }: { columns: ReportColumn[]; title: string; subtitle: string; flat?: boolean }) {
  const values = columns.map((c) => Number(c.amount));
  const maxPos = Math.max(0, ...values);
  const maxNeg = Math.max(0, ...values.map((v) => -v));
  const range = maxPos + maxNeg || 1;
  const plot = 190;
  const top = 34;
  const zeroY = top + (plot * maxPos) / range;
  const colW = 72;
  const gap = 64;
  const left = 48;
  const dx = flat ? 0 : 14;
  const dy = flat ? 0 : -10;
  const width = left * 2 + columns.length * colW + (columns.length - 1) * gap + 14;
  // Rótulo de categoria quebrado em até duas linhas (sem cortar nas bordas).
  const lines = (t: string) => { const m = t.match(/^(.*?) (\(.*\))$/); return m ? [m[1]!, m[2]!] : [t]; };
  const height = top + plot + 56;
  const id = "g3d-" + title.replace(/\W+/g, "-").toLowerCase();
  return (
    <figure className="chart3d">
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-labelledby={`${id}-t ${id}-d`} preserveAspectRatio="xMidYMid meet">
        <title id={`${id}-t`}>{title}</title>
        <desc id={`${id}-d`}>{subtitle + ". " + columns.map((c) => `${c.label}: ${formatMoney(c.amount)}`).join("; ")}</desc>
        <line x1={left - 8} x2={width - 8} y1={zeroY} y2={zeroY} className="chart-axis" />
        {columns.map((c, i) => {
          const v = Number(c.amount);
          const h = (plot * Math.abs(v)) / range;
          const x = left + i * (colW + gap);
          const y = v >= 0 ? zeroY - h : zeroY;
          const fill = c.key === "profit" && v < 0 ? "var(--eng-error)" : COLOR[c.key] ?? "var(--eng-cyan)";
          const labelY = v >= 0 ? y + dy - 8 : y + h + 16;
          return (
            <g key={c.key} data-key={c.key} data-amount={c.amount}>
              {!flat && h > 0 && (
                <>
                  <polygon points={`${x},${y} ${x + dx},${y + dy} ${x + colW + dx},${y + dy} ${x + colW},${y}`} fill={fill} opacity={0.75} />
                  <polygon points={`${x + colW},${y} ${x + colW + dx},${y + dy} ${x + colW + dx},${y + h + dy} ${x + colW},${y + h}`} fill={fill} opacity={0.45} />
                </>
              )}
              <rect x={x} y={y} width={colW} height={Math.max(h, 1)} fill={fill} />
              <text x={x + colW / 2} y={labelY} textAnchor="middle" className="chart-value">{formatMoney(c.amount)}</text>
              <text x={x + colW / 2} y={top + plot + 30} textAnchor="middle" className="chart-label">
                {lines(c.label).map((t, k) => <tspan key={k} x={x + colW / 2} dy={k === 0 ? 0 : 13}>{t}</tspan>)}
              </text>
            </g>
          );
        })}
      </svg>
      <figcaption className="muted small">{subtitle}</figcaption>
    </figure>
  );
}

/** Linha do tempo em colunas 2D (muitos períodos): altura = receita líquida na mesma escala; valores exatos na tabela. */
export function TimelineBars({ points, label }: { points: { label: string; value: string }[]; label: string }) {
  const vals = points.map((p) => Number(p.value));
  const maxPos = Math.max(0, ...vals);
  const maxNeg = Math.max(0, ...vals.map((v) => -v));
  const range = maxPos + maxNeg || 1;
  const plot = 110;
  const zeroY = 8 + (plot * maxPos) / range;
  const width = 1000;
  const bw = Math.max(4, Math.min(60, (width - 16) / Math.max(points.length, 1) - 4));
  return (
    <svg viewBox={`0 0 ${width} ${plot + 34}`} role="img" aria-label={label} className="timeline-chart" preserveAspectRatio="xMidYMid meet">
      <line x1={0} x2={width} y1={zeroY} y2={zeroY} className="chart-axis" />
      {points.map((p, i) => {
        const v = Number(p.value);
        const h = (plot * Math.abs(v)) / range;
        return (
          <g key={i}>
            <rect x={8 + i * (bw + 4)} y={v >= 0 ? zeroY - h : zeroY} width={bw} height={Math.max(h, v === 0 ? 0 : 1)}
              fill={v < 0 ? "var(--eng-error)" : "var(--eng-cyan)"}><title>{`${p.label}: ${formatMoney(p.value)}`}</title></rect>
            {(points.length <= 12 || i % Math.ceil(points.length / 10) === 0) &&
              <text x={8 + i * (bw + 4) + bw / 2} y={plot + 28} textAnchor="middle" className="chart-label">{p.label}</text>}
          </g>
        );
      })}
    </svg>
  );
}
