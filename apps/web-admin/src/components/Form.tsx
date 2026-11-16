import type { ReactNode } from "react";
import type { ApiError } from "../api/http";
import { Icon } from "./Icon";

export function Field({ id, label, error, hint, children }: { id: string; label: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && !error && <span className="muted small">{hint}</span>}
      {error && <span className="field-error" id={id + "-erro"}>{error}</span>}
    </div>
  );
}

/** Valor que o servidor não entregou por política de campo. */
export const Hidden = () => (
  <span className="masked" title="Oculto pela sua permissão de campo"><Icon name="lock" size={14} /> oculto</span>
);

/** Erro de envio com código de suporte; campos ficam preservados pelo chamador. */
export function FormError({ error }: { error: ApiError | null }) {
  return (
    <div aria-live="assertive" className="span-2">
      {error && (
        <p className="notice notice-error" role="alert">
          {error.code === "NETWORK_ERROR" ? "Sem conexão. Nada foi confirmado; tente de novo — o envio não será duplicado." : error.message}
          {error.requestId && <span className="muted small"> Código: <code>{error.requestId}</code></span>}
        </p>
      )}
    </div>
  );
}

export const fieldError = (error: ApiError | null, field: string) => error?.fieldErrors.find((x) => x.field === field)?.message;
