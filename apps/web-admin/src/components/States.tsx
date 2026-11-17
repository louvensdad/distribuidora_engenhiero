import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router";
import type { ApiError } from "../api/http";
import { Icon } from "./Icon";

export function LoadingState({ label = "Carregando" }: { label?: string }) {
  return (
    <div className="state" role="status" aria-live="polite">
      <div className="skeleton" aria-hidden="true">
        <span /><span /><span />
      </div>
      <span className="visually-hidden">{label}…</span>
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state">
      <Icon name="search" size={28} />
      <h3>{title}</h3>
      {children}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div className="state state-error" role="alert">
      <Icon name="close" size={28} />
      <h3>{error.code === "NETWORK_ERROR" ? "Sem conexão com o servidor" : "Não foi possível carregar"}</h3>
      <p>{error.message}</p>
      {error.requestId && (
        <p className="muted small">
          Código para suporte: <code>{error.requestId}</code>
        </p>
      )}
      {onRetry && (
        <button className="btn" onClick={onRetry}>
          <Icon name="refresh" size={16} /> Tentar novamente
        </button>
      )}
    </div>
  );
}

export function ForbiddenState() {
  return (
    <div className="state">
      <Icon name="lock" size={28} />
      <h3>Sem permissão</h3>
      <p>Sua conta não tem acesso a esta área. Peça ao administrador, se precisar.</p>
      <Link className="btn" to="/">Voltar ao início</Link>
    </div>
  );
}

export function NotFoundState() {
  return (
    <div className="state">
      <Icon name="search" size={28} />
      <h3>Página não encontrada</h3>
      <p>Este endereço não existe no site. Confira o link ou use a busca.</p>
      <Link className="btn" to="/">Voltar ao início</Link>
    </div>
  );
}

export function ConflictState({ onReload }: { onReload: () => void }) {
  return (
    <div className="state state-warning" role="alert">
      <Icon name="refresh" size={28} />
      <h3>Este registro mudou enquanto você editava</h3>
      <p>Outra pessoa salvou uma versão mais nova. Recarregue para revisar antes de salvar de novo — nada foi sobrescrito.</p>
      <button className="btn btn-primary" onClick={onReload}>Recarregar versão atual</button>
    </div>
  );
}

export function PendingModule({ title, phase }: { title: string; phase: string }) {
  return (
    <div className="state">
      <Icon name="clock" size={28} />
      <h3>{title}: em implementação</h3>
      <p>
        Esta função entra na fase <strong>{phase}</strong> do roteiro. Nenhum dado é exibido aqui até o serviço real existir —
        não há números de exemplo nesta tela.
      </p>
    </div>
  );
}

export function OfflineBanner() {
  const [online, setOnline] = useState(() => navigator.onLine);
  useEffect(() => {
    const on = () => setOnline(true);
    const off = () => setOnline(false);
    window.addEventListener("online", on);
    window.addEventListener("offline", off);
    return () => {
      window.removeEventListener("online", on);
      window.removeEventListener("offline", off);
    };
  }, []);
  if (online) return null;
  return (
    <div className="offline-banner" role="status">
      <Icon name="offline" size={18} /> SEM CONEXÃO — nada é confirmado até a conexão voltar.
    </div>
  );
}
