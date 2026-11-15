import { createHmac } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { expect, type Page } from "@playwright/test";

const SENHA = process.env.ENGENHEIRO_SENHA_LOCAL;
const AUTH_DIR = "e2e/.auth"; // fora do git: sessão e segredo TOTP de usuários FICTÍCIOS locais

/** TOTP (RFC 6238, SHA-1, 6 dígitos, 30 s) com o segredo bruto que o Keycloak usa no formulário de cadastro. */
function totp(secret: string, counter: number): string {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const h = createHmac("sha1", Buffer.from(secret, "utf8")).update(buf).digest();
  const o = h[h.length - 1]! & 0x0f;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

/** Código ainda não usado por este usuário (o Keycloak recusa reutilizar o mesmo código na mesma janela). */
async function freshCode(user: string, secret: string): Promise<string> {
  // Persistido em arquivo: execuções diferentes do Playwright não podem repetir o código da mesma janela.
  const lastFile = `${AUTH_DIR}/totp-${user}.last`;
  const last = existsSync(lastFile) ? Number(readFileSync(lastFile, "utf8")) : -1;
  let counter = Math.floor(Date.now() / 30000);
  if (counter <= last) {
    await new Promise((r) => setTimeout(r, (last + 1) * 30000 - Date.now() + 500));
    counter = Math.floor(Date.now() / 30000);
  }
  writeFileSync(lastFile, String(counter), { mode: 0o600 });
  return totp(secret, counter);
}

const secretFile = (user: string) => `${AUTH_DIR}/totp-${user}.txt`;

/** Faz login pelo site → formulário do Keycloak → TOTP (cadastro ou código) → volta ao site. */
export async function loginPeloSite(page: Page, username: string) {
  mkdirSync(AUTH_DIR, { recursive: true });
  if (!SENHA) throw new Error("Defina ENGENHEIRO_SENHA_LOCAL (senha dos usuários fictícios locais)");
  await page.goto("/");
  await page.getByRole("link", { name: /entrar com segurança/i }).click();
  await page.waitForURL(/localhost:8180\/realms\/engenheiro/);
  await page.locator("#username").fill(username);
  await page.locator("#password").fill(SENHA);
  await page.locator("#kc-login").click();
  await page.waitForLoadState("load");

  if (page.url().includes("localhost:8180")) {
    const setup = page.locator("input[name=totpSecret]");
    const code = page.locator("#otp");
    if ((await setup.count()) > 0) {
      const secret = await setup.inputValue();
      writeFileSync(secretFile(username), secret, { mode: 0o600 });
      await page.locator("#totp").fill(await freshCode(username, secret));
      await page.locator("#userLabel").fill("e2e-playwright").catch(() => undefined);
      await page.locator("input[type=submit], button[type=submit]").first().click();
    } else if ((await code.count()) > 0) {
      if (!existsSync(secretFile(username))) throw new Error(`TOTP de ${username} já cadastrado e segredo local ausente: rode infra/local/definir-senhas-locais.sh`);
      await code.fill(await freshCode(username, readFileSync(secretFile(username), "utf8")));
      await page.locator("#kc-login").click();
    }
  }
  await page.waitForURL(/localhost:4200/, { timeout: 60_000 });
  await expect(page.locator("body")).not.toContainText("Entrar com segurança");
}
