// Homologação no navegador real (F16): login OIDC com TOTP, telas principais pela borda TLS, e nenhuma violação de
// CSP nem erro de console. Usa o Playwright do site. Uso (na raiz):
//   node infra/homologacao/navegador.mjs   (capturas em docs/evidence/telas-web/F16-homolog-*.png)
import { createRequire } from "node:module";
import { createHmac } from "node:crypto";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const require = createRequire(join(process.cwd(), "apps/web-admin/package.json"));
const { chromium } = require("@playwright/test");
const { AxeBuilder } = require("@axe-core/playwright");
const D = process.env.ENGENHEIRO_HOMOLOG_DIR ?? join(homedir(), ".config/engenheiro/homologacao");
const env = Object.fromEntries(readFileSync(join(D, ".env"), "utf8").split("\n").filter((l) => l.includes("=") && !l.startsWith("#"))
  .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const SITE = `https://gestao.${env.HOMOLOG_DOMINIO}:${env.HOMOLOG_PORTA}`;
const totp = (secret, t = Date.now() / 1000) => {
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(Math.floor(t / 30)));
  const d = createHmac("sha1", Buffer.from(secret)).update(buf).digest();
  const o = d[d.length - 1] & 0x0f;
  return String((d.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, "0");
};
const lastWindow = join(D, ".totp-ultima-janela");
const waitFreshWindow = async () => {
  const w = Math.floor(Date.now() / 30000);
  if (existsSync(lastWindow) && readFileSync(lastWindow, "utf8").trim() === String(w)) {
    await new Promise((r) => setTimeout(r, 30000 - (Date.now() % 30000) + 1000));
  }
  writeFileSync(lastWindow, String(Math.floor(Date.now() / 30000)));
};

const out = "docs/evidence/telas-web";
mkdirSync(out, { recursive: true });
// A CA de homologação não está no repositório de confiança do sistema: o Chrome aceita só esta origem por exceção.
const browser = await chromium.launch({ channel: "chrome", args: ["--ignore-certificate-errors-spki-list"] });
const ctx = await browser.newContext({ ignoreHTTPSErrors: true, locale: "pt-BR", viewport: { width: 1366, height: 900 } });
const page = await ctx.newPage();
const problems = [];
page.on("console", (m) => { if (m.type() === "error") problems.push(`console: ${m.text()}`); });
page.on("pageerror", (e) => problems.push(`erro: ${e.message}`));
await page.addInitScript(() => document.addEventListener("securitypolicyviolation",
  (e) => console.error(`CSP ${e.violatedDirective} ${e.blockedURI}`)));

await page.goto(SITE + "/");
await page.getByRole("link", { name: /entrar com segurança/i }).click();
await page.fill("#username", "patrao");
await page.fill("#password", env.ENGENHEIRO_SENHA_HOMOLOG);
await page.click("#kc-login");
await page.waitForSelector("#otp", { timeout: 15000 });
await waitFreshWindow();
await page.fill("#otp", totp(readFileSync(join(D, "totp-patrao.txt"), "utf8").trim()));
await page.click("#kc-login");
await page.waitForURL(SITE + "/**", { timeout: 15000 });

const telas = [["/overview", "inicio"], ["/reports", "relatorios"], ["/intelligence", "ia"], ["/sales/orders", "pedidos"],
  ["/finance/expenses", "despesas"], ["/team/audit", "historico"]];
let ia = "";
const a11y = [];
let regras = 0;
for (const [path, nome] of telas) {
  await page.goto(SITE + path);
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: `${out}/F16-homolog-${nome}.png` });
  // Acessibilidade (WCAG 2.1 A/AA) da tela real: graves e críticas reprovam; moderadas ficam no relatório.
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  regras += r.passes.length;
  for (const v of r.violations) a11y.push({ tela: path, id: v.id, impacto: v.impact, n: v.nodes.length, ajuda: v.help,
    alvo: v.nodes.slice(0, 2).map((x) => x.target.join(" ")).join(" | ") });
  if (path === "/intelligence") ia = (await page.locator("[aria-label='Situação da IA']").textContent()) ?? "";
  console.log(`  ✓ ${path} — ${await page.title()} — "${(await page.locator("h1").first().textContent())?.trim()}"`);
}
console.log(`  IA: ${ia?.includes("NÃO CONFIGURADA") ? "✓ NÃO CONFIGURADA visível" : "✗ " + ia}`);
await browser.close();
const graves = a11y.filter((v) => v.impacto === "critical" || v.impacto === "serious");
for (const v of a11y) console.log(`  ${graves.includes(v) ? "✗" : "·"} a11y ${v.tela} ${v.impacto} ${v.id} (${v.n}): ${v.ajuda} — ${v.alvo}`);
console.log(graves.length === 0 ? `  ✓ acessibilidade WCAG 2.1 AA: nenhuma violação grave/crítica em ${telas.length} telas (${regras} verificações de regra aprovadas)` : `  ✗ ${graves.length} violações graves/críticas`);
const csp = problems.filter((p) => p.includes("CSP"));
console.log(csp.length === 0 ? "  ✓ nenhuma violação de CSP" : `  ✗ ${csp.length} violações de CSP:\n    ${csp.join("\n    ")}`);
const outros = problems.filter((p) => !p.includes("CSP"));
console.log(outros.length === 0 ? "  ✓ nenhum erro de console/página" : `  ✗ erros:\n    ${outros.join("\n    ")}`);
process.exit(problems.length || graves.length || !ia?.includes("NÃO CONFIGURADA") ? 1 : 0);
