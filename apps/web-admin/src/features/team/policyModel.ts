import type { CapabilityView, Effect, FieldPolicy, Grant, Policy, Scope } from "../../api/types";
import { ACTION_LABELS, CLIENT_LABELS, FIELD_LABELS, RESOURCE_LABELS, SCOPE_LABELS, label } from "../../api/labels";

export const SCOPES: Scope[] = ["OWN", "ASSIGNED", "SECTOR", "BRANCH", "ORGANIZATION"];
export const FIELDS = Object.keys(FIELD_LABELS);
export const CLIENTS = Object.keys(CLIENT_LABELS);

export type CellEffect = "NONE" | Effect;

export interface Cell {
  effect: CellEffect;
  scope: Scope;
  maxAmount: string;
  /** Escopos adicionais já concedidos para a mesma ação; preservados ao salvar. */
  extraScopes: Scope[];
  expiresAt: string | null;
}

export interface Draft {
  cells: Record<string, Cell>;
  fields: Record<string, CellEffect>;
  clients: string[];
}

const key = (resource: string, action: string) => `${resource}.${action}`;
const emptyCell = (): Cell => ({ effect: "NONE", scope: "OWN", maxAmount: "", extraScopes: [], expiresAt: null });

/** Recursos/ações que o editor pode conceder: os que o próprio usuário possui (regra do servidor). */
export function grantableMatrix(own: CapabilityView[]): { resource: string; actions: string[] }[] {
  const byResource = new Map<string, string[]>();
  for (const c of own) byResource.set(c.resource, [...(byResource.get(c.resource) ?? []), c.action]);
  return [...byResource.entries()]
    .map(([resource, actions]) => ({ resource, actions }))
    .sort((a, b) => label(RESOURCE_LABELS, a.resource).localeCompare(label(RESOURCE_LABELS, b.resource), "pt-BR"));
}

export function fromPolicy(p: Policy): Draft {
  const cells: Record<string, Cell> = {};
  for (const g of p.grants) {
    const k = key(g.resource, g.action);
    const cell = cells[k] ?? emptyCell();
    if (g.effect === "DENY") {
      cell.effect = "DENY";
    } else if (cell.effect === "NONE") {
      cell.effect = "ALLOW";
      cell.scope = g.scope;
      cell.maxAmount = g.max_amount ?? "";
      cell.expiresAt = g.expires_at ?? null;
    } else if (cell.effect === "ALLOW" && g.scope !== cell.scope && !cell.extraScopes.includes(g.scope)) {
      cell.extraScopes.push(g.scope);
    }
    cells[k] = cell;
  }
  const fields: Record<string, CellEffect> = {};
  for (const f of p.fields) fields[f.field] = f.effect;
  return { cells, fields, clients: [...p.clients].sort() };
}

export function cellOf(d: Draft, resource: string, action: string): Cell {
  return d.cells[key(resource, action)] ?? emptyCell();
}

export function withCell(d: Draft, resource: string, action: string, patch: Partial<Cell>): Draft {
  return { ...d, cells: { ...d.cells, [key(resource, action)]: { ...cellOf(d, resource, action), ...patch } } };
}

export interface PolicyPayload {
  grants: Grant[];
  fields: FieldPolicy[];
  clients: string[];
  reason: string;
}

export function toPayload(d: Draft, reason: string): PolicyPayload {
  const grants: Grant[] = [];
  for (const [k, c] of Object.entries(d.cells)) {
    if (c.effect === "NONE") continue;
    const dot = k.lastIndexOf(".");
    const resource = k.slice(0, dot);
    const action = k.slice(dot + 1);
    const base = { resource, action, expires_at: c.expiresAt };
    if (c.effect === "DENY") {
      grants.push({ ...base, scope: "ORGANIZATION", effect: "DENY", max_amount: null });
      continue;
    }
    const max = c.maxAmount.trim() === "" ? null : c.maxAmount.trim().replace(",", ".");
    for (const scope of [c.scope, ...c.extraScopes]) grants.push({ ...base, scope, effect: "ALLOW", max_amount: max });
  }
  const fields = Object.entries(d.fields)
    .filter(([, e]) => e !== "NONE")
    .map(([field, e]) => ({ field, effect: e as Effect }));
  return { grants, fields, clients: d.clients, reason: reason.trim() };
}

/** Resumo legível do que muda, mostrado antes de salvar. */
export function describeChanges(before: Draft, after: Draft): string[] {
  const out: string[] = [];
  const keys = new Set([...Object.keys(before.cells), ...Object.keys(after.cells)]);
  for (const k of [...keys].sort()) {
    const a = before.cells[k] ?? emptyCell();
    const b = after.cells[k] ?? emptyCell();
    const dot = k.lastIndexOf(".");
    const name = `${label(RESOURCE_LABELS, k.slice(0, dot))} · ${label(ACTION_LABELS, k.slice(dot + 1))}`;
    const fmt = (c: Cell) =>
      c.effect === "NONE" ? "sem acesso" : c.effect === "DENY" ? "bloqueado"
        : `permitido (${label(SCOPE_LABELS, c.scope)}${c.maxAmount ? `, até R$ ${c.maxAmount}` : ""})`;
    if (fmt(a) !== fmt(b)) out.push(`${name}: ${fmt(a)} → ${fmt(b)}`);
  }
  for (const f of FIELDS) {
    const a = before.fields[f] ?? "NONE";
    const b = after.fields[f] ?? "NONE";
    const fmt = (e: CellEffect) => (e === "ALLOW" ? "visível" : e === "DENY" ? "bloqueado" : "oculto (padrão)");
    if (a !== b) out.push(`Campo ${label(FIELD_LABELS, f)}: ${fmt(a)} → ${fmt(b)}`);
  }
  for (const c of CLIENTS) {
    const a = before.clients.includes(c);
    const b = after.clients.includes(c);
    if (a !== b) out.push(`${label(CLIENT_LABELS, c)}: ${b ? "liberado" : "removido"}`);
  }
  return out;
}
