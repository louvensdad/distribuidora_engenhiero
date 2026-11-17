import { randomBytes } from "node:crypto";
import { expect, test } from "@playwright/test";

// Pedido do responsável (ADR-027) no Chrome real contra a pilha local: cargo → funcionário → conta criada no
// Keycloak de verdade → a pessoa entra com a senha temporária, troca a senha e (sendo do balcão) NÃO entra no site
// do patrão → desligamento bloqueia a conta. Dados fictícios.
test("cargo, funcionário, conta real no provedor e desligamento", async ({ page, browser }) => {
  test.setTimeout(120_000);
  const suf = randomBytes(3).toString("hex");
  const cargo = `Balconista ${suf}`;
  const nome = `Joana Fictícia ${suf}`;

  await page.goto("/team/positions");
  await page.getByRole("button", { name: /Novo cargo/ }).click();
  await page.getByLabel("Nome do cargo *").fill(cargo);
  await page.getByLabel("Setor *").selectOption("RETIRADA");
  await page.getByLabel(/Acesso sugerido/).selectOption("BALCAO");
  await page.getByLabel("Descrição").fill("Atende o balcão e recebe em dinheiro/Pix");
  await page.getByRole("button", { name: "Salvar cargo" }).click();
  await expect(page.getByRole("rowheader", { name: new RegExp(cargo) })).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/ADR027-cargos.png", fullPage: true });

  await page.goto("/team/employees");
  await page.getByRole("link", { name: /Novo funcionário/ }).click();
  await page.getByLabel("Nome completo *").fill(nome);
  await page.getByLabel("Cargo *").selectOption({ label: cargo });
  await page.getByLabel("Função / atividades").fill("Balcão da manhã e conferência do caixa");
  await page.getByLabel("Jornada").fill("Seg a sáb, 6h às 14h");
  await page.getByLabel("Salário base (R$)").fill("2.150,00");
  await page.getByLabel("CPF").fill("000000000" + String(Math.floor(Math.random() * 90) + 10));
  await page.getByRole("button", { name: "Cadastrar funcionário" }).click();
  await expect(page.getByRole("heading", { name: nome })).toBeVisible();
  await expect(page.getByText("R$ 2.150,00")).toBeVisible();

  // Dar acesso: conta criada no Keycloak local (client de serviço), senha temporária mostrada uma vez.
  const usuario = `joana.${suf}`;
  await page.getByLabel("Nome de usuário *").fill(usuario);
  await expect(page.getByLabel("Modelo de acesso *")).toHaveValue("BALCAO");
  await page.getByRole("button", { name: "Criar conta e dar acesso" }).click();
  const aviso = page.getByRole("alert").filter({ hasText: "Conta criada" });
  await expect(aviso).toBeVisible();
  const senha = (await aviso.locator("code").innerText()).trim();
  expect(senha.length).toBeGreaterThanOrEqual(12);
  // A senha nunca vai para a evidência (mesmo fictícia e temporária): o trecho é mascarado na captura.
  await page.screenshot({ path: "../../docs/evidence/telas-web/ADR027-conta-criada.png", fullPage: true, mask: [aviso.locator("code")] });
  await aviso.getByRole("button", { name: "Já entreguei a senha" }).click();
  await expect(page.getByText("ativa", { exact: true })).toBeVisible();

  // A pessoa entra com a PRÓPRIA conta: troca a senha obrigatória e, sendo do balcão, não entra no site do patrão.
  const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const p2 = await ctx.newPage();
  await p2.goto("/");
  await p2.getByRole("link", { name: /entrar com segurança/i }).click();
  await p2.locator("#username").fill(usuario);
  await p2.locator("#password").fill(senha);
  await p2.locator("#kc-login").click();
  const nova = `Nova-${randomBytes(6).toString("hex")}`;
  await p2.locator("#password-new").fill(nova);
  await p2.locator("#password-confirm").fill(nova);
  await p2.locator("input[type=submit], button[type=submit]").first().click();
  await expect(p2.getByText(/não tem acesso ao site do patrão/)).toBeVisible({ timeout: 30_000 });
  await ctx.close();

  // Desligamento: conta bloqueada na hora; o histórico do funcionário mostra cada passo em nome do patrão.
  await page.getByRole("button", { name: "Registrar desligamento…" }).click();
  await page.getByLabel("Motivo *").fill("Teste automatizado (fictício)");
  await page.getByRole("button", { name: "Confirmar desligamento" }).click();
  await expect(page.getByText("Desligado", { exact: true })).toBeVisible();
  await expect(page.getByText("bloqueada", { exact: true })).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/ADR027-ficha-desligado.png", fullPage: true });
  await page.locator("main").getByRole("link", { name: "Histórico", exact: true }).click();
  for (const a of ["Cadastrou funcionário", "Criou conta de acesso do funcionário", "Registrou desligamento"]) {
    await expect(page.locator("ol.timeline > li", { hasText: a })).toBeVisible();
  }
});
