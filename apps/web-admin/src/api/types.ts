// Tipos espelhando packages/contracts/openapi.json (endpoints implementados).
export type Scope = "OWN" | "ASSIGNED" | "SECTOR" | "BRANCH" | "ORGANIZATION";
export type Effect = "ALLOW" | "DENY";

export interface CapabilityView {
  capability: string;
  resource: string;
  action: string;
  scopes: Scope[];
  max_amount: string | null;
}

export interface Me {
  user: { id: string; display_name: string };
  organization: { id: string; name: string };
  branch: { id: string; name: string };
  client_id: string;
  admin: boolean;
  role_template: string;
  sector: string;
  policy_version: number;
  capabilities: CapabilityView[];
  fields: Record<string, boolean>;
}

export interface BffSession {
  web_login: "CONFIGURADO" | "NAO_CONFIGURADO";
  authenticated: boolean;
  login_url?: string;
  display_name?: string;
}

export interface UserView {
  id: string;
  display_name: string;
  status: "ACTIVE" | "DISABLED";
  sector: string;
  role_template: string;
  admin: boolean;
  branch: string;
  version: number;
}

export interface Grant {
  resource: string;
  action: string;
  scope: Scope;
  effect: Effect;
  max_amount?: string | null;
  expires_at?: string | null;
}

export interface FieldPolicy {
  field: string;
  effect: Effect;
}

export interface Policy {
  user: UserView;
  clients: string[];
  grants: Grant[];
  fields: FieldPolicy[];
}

export interface RoleTemplate {
  name: string;
  sector: string;
  admin: boolean;
  clients: string[];
  grants: string[];
  fields: Record<string, Effect>;
}

export interface AuditEvent {
  id: string;
  occurred_at: string;
  actor_user_id: string | null;
  actor_name: string | null;
  actor_kind: string;
  client_id: string | null;
  action: string;
  entity_type: string | null;
  entity_id: string | null;
  result: "SUCCESS" | "DENIED" | "FAILURE";
  reason: string | null;
  request_id: string | null;
}

export interface Customer {
  id: string;
  name: string;
  branch: string;
  contact: string | null;
  address: string | null;
  personal_document: string | null;
  status: string;
  version: number;
  created_at: string;
  masked_fields: string[];
}

export interface Page<T> {
  items: T[];
  next_cursor: string | null;
}

export interface Problem {
  type?: string;
  title?: string;
  status: number;
  code: string;
  request_id?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}
