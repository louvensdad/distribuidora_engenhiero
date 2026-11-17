import { useSearchParams } from "react-router";
import type { ReportPeriod } from "../../api/types";
import { useResource } from "../../api/useResource";
import { todayBr } from "../../app/format";

export interface Filter { period: ReportPeriod; date: string; from: string; until: string; branch: string }

const addDays = (iso: string, n: number) => {
  const d = new Date(iso + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

/** Filtro na URL (recarregar/compartilhar mantém o período). Datas civis de São Paulo; o servidor resolve o intervalo. */
export function useReportFilter(): [Filter, (f: Partial<Filter>) => void, string] {
  const [sp, setSp] = useSearchParams();
  const today = todayBr();
  const f: Filter = {
    period: (["DAY", "WEEK", "MONTH", "CUSTOM"].includes(sp.get("period") ?? "") ? sp.get("period") : "MONTH") as ReportPeriod,
    date: sp.get("date") ?? today,
    from: sp.get("from") ?? today.slice(0, 8) + "01",
    until: sp.get("until") ?? today,
    branch: sp.get("branch") ?? "",
  };
  const set = (p: Partial<Filter>) => {
    const n = { ...f, ...p };
    const out = new URLSearchParams();
    out.set("period", n.period);
    if (n.period === "CUSTOM") { out.set("from", n.from); out.set("until", n.until); } else out.set("date", n.date);
    if (n.branch) out.set("branch", n.branch);
    setSp(out, { replace: true });
  };
  // Contrato: fim EXCLUSIVO; na tela a pessoa escolhe "até" (inclusivo).
  const qs = new URLSearchParams({ period: f.period });
  if (f.period === "CUSTOM") { qs.set("from", f.from); qs.set("to", addDays(f.until, 1)); } else qs.set("date", f.date);
  if (f.branch) qs.set("branch_id", f.branch);
  return [f, set, qs.toString()];
}

const PERIODS: [ReportPeriod, string][] = [["DAY", "Dia"], ["WEEK", "Semana"], ["MONTH", "Mês"], ["CUSTOM", "Período"]];

export function ReportFilters({ filter, onChange }: { filter: Filter; onChange: (f: Partial<Filter>) => void }) {
  const [branches] = useResource<{ items: { id: string; name: string; status: string }[] }>("/api/v1/branches");
  return (
    <form className="filters" aria-label="Filtros do relatório" onSubmit={(e) => e.preventDefault()}>
      <div className="segmented" role="radiogroup" aria-label="Período">
        {PERIODS.map(([p, l]) => (
          <button key={p} type="button" role="radio" aria-checked={filter.period === p}
            className={"btn btn-small" + (filter.period === p ? " btn-primary" : " btn-ghost")} onClick={() => onChange({ period: p })}>{l}</button>
        ))}
      </div>
      {filter.period !== "CUSTOM" ? (
        <label>{filter.period === "DAY" ? "Dia" : filter.period === "WEEK" ? "Uma data da semana" : "Uma data do mês"}
          <input type="date" value={filter.date} onChange={(e) => e.target.value && onChange({ date: e.target.value })} />
        </label>
      ) : (
        <>
          <label>De<input type="date" value={filter.from} onChange={(e) => e.target.value && onChange({ from: e.target.value })} /></label>
          <label>Até (inclusive)<input type="date" value={filter.until} onChange={(e) => e.target.value && onChange({ until: e.target.value })} /></label>
        </>
      )}
      {branches.status === "ready" && branches.data.items.length > 1 && (
        <label>Unidade
          <select value={filter.branch} onChange={(e) => onChange({ branch: e.target.value })}>
            <option value="">Todas (empresa)</option>
            {branches.data.items.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </label>
      )}
      <span className="muted small">Fuso America/Sao_Paulo · início inclusivo, fim exclusivo</span>
    </form>
  );
}
