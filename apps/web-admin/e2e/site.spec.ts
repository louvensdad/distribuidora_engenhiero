import { expect, test } from "@playwright/test";
import { loginPeloSite } from "./keycloak";

test.describe("site do patrão — pilha real", () => {
  test("abas horizontais na ordem e indicadores financeiros sem números inventados", async ({ page }) => {
    await page.goto("/");
    const nav = page.getByRole("navigation", { name: "Seções principais" });
    await expect(nav.getByRole("link")).toHaveText(["Visão geral", "Vendas", "Operação", "Financeiro", "Relatórios", "Inteligência", "Equipe"]);
    await expect(page.getByRole("heading", { name: "Visão geral" })).toBeVisible();
    // Indicadores: exatamente o resultado do servidor (F06), nunca número de exemplo.
    const sum = await (await page.request.get("/api/v1/reports/summary")).json();
    const brl = (v: string) => {
      const [i, f] = v.replace("-", "").split(".");
      return (v.startsWith("-") ? "−" : "") + "R$ " + i!.replace(/\B(?=(\d{3})+(?!\d))/g, ".") + "," + f;
    };
    await expect(page.locator(".kpi-value").first()).toHaveText(brl(sum.gross_revenue));
    await expect(page.locator(".kpi-value").nth(4)).toHaveText(brl(sum.profit));
    await expect(page.getByText("Distribuidora de Cana Engenheiro Ltda").first()).toBeVisible();
    // Nenhum token acessível ao JavaScript da página.
    const cookies = await page.evaluate(() => document.cookie);
    expect(cookies).not.toContain("ENGENHEIRO_SESSION");
    expect(cookies).not.toMatch(/eyJ/);
  });

  test("cadastra cliente e o encontra na busca", async ({ page }) => {
    const nome = `Cliente E2E ${Date.now()}`;
    await page.goto("/sales/customers");
    await page.getByRole("button", { name: /novo cliente/i }).click();
    await page.getByLabel("Nome *").fill(nome);
    await page.getByLabel("Contato").fill("(11) 90000-1234");
    await page.getByLabel("Endereço de entrega").fill("Rua de Teste, 10");
    await page.getByRole("button", { name: "Cadastrar cliente" }).click();
    await page.getByLabel("Buscar cliente por nome").fill(nome);
    await page.getByRole("button", { name: "Buscar", exact: true }).click();
    const row = page.getByRole("row", { name: new RegExp(nome) });
    await expect(row).toContainText("(11) 90000-1234");
    await expect(row).toContainText("Matriz");
  });

  test("mapa de acessos: altera, mostra resumo, salva com motivo, aparece no histórico e restaura o modelo", async ({ page }) => {
    await page.goto("/team/access");
    await page.getByLabel("Pessoa").selectOption({ label: "Balcão Exemplo — Balcão" });
    const area = page.locator("details.group").filter({ has: page.locator(".group-name", { hasText: /^Clientes$/ }) });
    await expect(area).toHaveAttribute("open", ""); // balcão já tem permissões de clientes
    const exportar = page.locator("#ef-customers-export");
    await expect(exportar).toHaveValue("NONE");
    await exportar.selectOption("ALLOW");
    await expect(page.getByText("Clientes · Exportar: sem acesso → permitido (Próprios registros)")).toBeVisible();
    await page.locator("#motivo-salvar").fill("e2e: liberar exportação temporária");
    await page.getByRole("button", { name: "Salvar permissões" }).click();
    await expect(page.getByText(/Permissões salvas/)).toBeVisible();
    await expect(page.locator("#ef-customers-export")).toHaveValue("ALLOW");

    await page.getByLabel("Modelo").selectOption({ label: "Balcão" });
    await page.locator("#motivo-modelo").fill("e2e: restaurar modelo");
    await page.getByRole("button", { name: "Aplicar modelo" }).click();
    await expect(page.locator("#ef-customers-export")).toHaveValue("NONE");

    await page.goto("/team/audit");
    await expect(page.locator(".event").filter({ hasText: "e2e: restaurar modelo" }).first()).toContainText("Patrão Fictício");
    await expect(page.locator(".event").filter({ hasText: "e2e: liberar exportação temporária" }).first()).toContainText("Alterou permissões");
  });

  test("teclado: primeiro Tab leva ao atalho 'Pular para o conteúdo'", async ({ page }) => {
    await page.goto("/overview");
    await page.getByRole("heading", { name: "Visão geral" }).waitFor(); // sessão carregada antes do primeiro Tab
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Pular para o conteúdo" })).toBeFocused();
  });

  test("contador não entra no site do patrão", async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await ctx.newPage();
    await page.goto("/");
    await page.getByRole("link", { name: /entrar com segurança/i }).click();
    await page.locator("#username").fill("contador");
    await page.locator("#password").fill(process.env.ENGENHEIRO_SENHA_LOCAL!);
    await page.locator("#kc-login").click();
    await expect(page.getByText(/não tem acesso ao site do patrão/)).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Seções principais" })).toHaveCount(0);
    await ctx.close();
  });

  test("sair encerra a sessão no servidor e no provedor", async ({ browser }) => {
    test.setTimeout(90_000); // pode esperar a próxima janela de TOTP
    const ctx = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await ctx.newPage();
    await loginPeloSite(page, "patrao");
    await page.getByRole("button", { name: "Sair" }).click();
    await page.waitForURL("http://localhost:4200/");
    await expect(page.getByRole("link", { name: /entrar com segurança/i })).toBeVisible();
    const status = await page.evaluate(async () => (await fetch("/api/v1/me/capabilities")).status);
    expect(status).toBe(401);
    await ctx.close();
  });
});
