// Datas no fuso comercial da empresa (America/Sao_Paulo), independentemente do fuso do computador.
const DT = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Sao_Paulo", dateStyle: "short", timeStyle: "short" });
export const formatDateTime = (iso: string) => DT.format(new Date(iso));

// ---- Quantidades e dinheiro (texto decimal do servidor; nunca recalculados no cliente) ----
const UNIT_PLURAL: Record<string, [string, string]> = {
  UNIDADE: ["unidade", "unidades"], DUZIA: ["dúzia", "dúzias"], FEIXE: ["feixe", "feixes"], MACO: ["maço", "maços"],
  CAIXA: ["caixa", "caixas"], SACO: ["saco", "sacos"], KG: ["kg", "kg"], TONELADA: ["tonelada", "toneladas"],
};
export const UNITS = Object.keys(UNIT_PLURAL);
export const unitLabel = (unit: string, qty?: string) => {
  const [one, many] = UNIT_PLURAL[unit] ?? [unit, unit];
  return qty !== undefined && Math.abs(Number(qty)) === 1 ? one : many;
};
/** Formata decimal em texto sem passar por float para dinheiro: só troca separadores. */
export function formatDecimal(value: string | null | undefined): string {
  if (value == null) return "—";
  const neg = value.startsWith("-");
  const [int, frac] = (neg ? value.slice(1) : value).split(".");
  const grouped = (int ?? "0").replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return (neg ? "−" : "") + grouped + (frac ? "," + frac : "");
}
export const formatQty = (value: string, unit: string) => `${formatDecimal(value)} ${unitLabel(unit, value)}`;
export function formatMoney(value: string | null | undefined): string {
  if (value == null) return "—";
  const [int, frac = "00"] = value.replace("-", "").split(".");
  return (value.startsWith("-") ? "−" : "") + "R$ " + formatDecimal(int ?? "0") + "," + frac.padEnd(2, "0").slice(0, 2);
}

/**
 * Texto digitado → decimal do contrato; vazio → null. Vírgula é sempre o separador decimal (pt-BR, "1.234,5").
 * Sem vírgula, ponto seguido de grupos de 3 dígitos é milhar ("45.200" = 45200); senão é decimal ("12.5").
 */
export function toDecimal(v: string): string | null {
  const t = v.trim().replace(/\s/g, "");
  if (!t) return null;
  if (t.includes(",")) return t.replace(/\./g, "").replace(",", ".");
  return /^\d{1,3}(\.\d{3})+$/.test(t) ? t.replace(/\./g, "") : t;
}
