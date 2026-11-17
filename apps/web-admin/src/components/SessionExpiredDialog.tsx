import { useEffect, useRef, useState } from "react";
import { useSession } from "../session/SessionContext";
import { rememberReturn } from "../session/returnPath";
import { Icon } from "./Icon";

/**
 * WEB-03 — sessão expirada com a tela aberta. A tela por trás continua montada: o que foi digitado não se perde.
 * Entrar de novo em outra aba e voltar preserva o trabalho; entrar nesta aba volta ao mesmo endereço depois.
 * Nada é reenviado sozinho: a pessoa confere e repete a ação (comandos com chave de idempotência não duplicam).
 */
export function SessionExpiredDialog() {
  const { expired, loginUrl, recheck } = useSession();
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => { if (expired) { setMsg(null); first.current?.focus(); } }, [expired]);
  if (!expired) return null;
  const check = async () => {
    setBusy(true);
    setMsg(null);
    try {
      const r = await recheck();
      if (r === "still-anonymous") setMsg("Ainda não encontramos a sua entrada. Entre na outra aba e tente de novo.");
    } catch {
      setMsg("Sem conexão com o servidor. Verifique a internet e tente de novo.");
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="modal-backdrop">
      <div className="modal card" role="alertdialog" aria-modal="true" aria-labelledby="sessao-titulo" aria-describedby="sessao-texto">
        <h2 id="sessao-titulo" className="h3"><Icon name="lock" size={20} /> Sua sessão terminou</h2>
        <p id="sessao-texto">
          Por segurança, entre de novo. <strong>O que você digitou nesta tela continua aqui</strong> — entre em outra aba e
          volte para continuar. Nenhuma ação pela metade é confirmada: confira e repita o último envio se precisar.
        </p>
        {msg && <p className="notice notice-error" role="alert">{msg}</p>}
        <div className="actions">
          <a className="btn btn-primary" href={loginUrl} target="_blank" rel="noopener">
            <Icon name="shield" size={16} /> Entrar em outra aba
          </a>
          <button ref={first} className="btn" disabled={busy} onClick={() => void check()}>{busy ? "Conferindo…" : "Já entrei, continuar"}</button>
          <button className="btn btn-ghost" onClick={() => { rememberReturn(); window.location.assign(loginUrl); }}>
            Entrar nesta aba (descarta o que não foi salvo)
          </button>
        </div>
      </div>
    </div>
  );
}
