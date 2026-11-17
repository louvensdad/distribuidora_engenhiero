// Volta ao mesmo endereço depois de entrar de novo (WEB-03). Fica só na aba (sessionStorage), sem dado pessoal;
// aceita apenas caminho interno do site (nunca URL externa, "//host", rotas do servidor de login).
const KEY = "engenheiro:voltar";

export function isSafeReturn(path: string | null | undefined): path is string {
  return !!path && path.startsWith("/") && !path.startsWith("//") && !path.startsWith("/\\")
    && !/^\/(bff|oauth2|login|api|actuator)(\/|$|\?)/.test(path) && path.length <= 500;
}

export function rememberReturn(path = window.location.pathname + window.location.search + window.location.hash): void {
  if (!isSafeReturn(path) || path === "/") return;
  try { sessionStorage.setItem(KEY, path); } catch { /* armazenamento indisponível: volta ao início */ }
}

export function takeReturn(): string | null {
  try {
    const p = sessionStorage.getItem(KEY);
    sessionStorage.removeItem(KEY);
    return isSafeReturn(p) ? p : null;
  } catch {
    return null;
  }
}
