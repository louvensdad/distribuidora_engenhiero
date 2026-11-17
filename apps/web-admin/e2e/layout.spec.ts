import { expect, test } from "@playwright/test";

// U02: sem corte nem rolagem horizontal da página em 360, 768 e 1440 px.
// Usuário fictício do seed local (Balcão Exemplo) para abrir o editor do mapa de acessos.
const BALCAO = "0193a000-0000-7000-8000-000000000a02";

for (const path of ["/overview", "/overview/sectors", "/overview/approvals", "/search?q=ca", "/team/audit?sector=COUNTER", "/sales/orders?q=1", "/sales/customers", "/operation/stock", "/operation/receipts", "/operation/receipts/new",
  "/operation/products", "/operation/suppliers", "/operation/fleet", "/operation/trips", "/finance/cash", "/finance/closing", "/finance/expenses", "/finance/receivables", "/finance/open-finance", "/team/employees", "/finance/taxes", "/finance/payroll", "/finance/documents", "/finance/invoices", "/finance/integrations", "/finance/payables", "/reports", "/reports/categories", "/reports/goals", "/reports/customers", "/team/branches", "/team/people", "/team/access", `/team/access?user=${BALCAO}`, "/team/audit"]) {
  test(`sem rolagem horizontal em ${path}`, async ({ page }, info) => {
    await page.goto(path);
    await page.locator("main h1").first().waitFor();
    await page.waitForLoadState("networkidle");
    const { scroll, client } = await page.evaluate(() => ({
      scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }));
    expect(scroll, `${info.project.name} ${path}`).toBeLessThanOrEqual(client);
    await expect(page.getByRole("button", { name: "Sair" })).toBeVisible();
    await page.screenshot({ path: `test-results/telas/${info.project.name}${path.replaceAll(/[/?=]/g, "_")}.png`, fullPage: true });
  });
}

test("abas acessíveis em qualquer largura (rolagem própria da barra)", async ({ page }) => {
  await page.goto("/overview");
  const equipe = page.getByRole("navigation", { name: "Seções principais" }).getByRole("link", { name: "Equipe" });
  await equipe.scrollIntoViewIfNeeded();
  await equipe.click();
  await expect(page.getByRole("heading", { name: "Pessoas" })).toBeVisible();
});

test("ações por pessoa ficam visíveis sem rolagem lateral", async ({ page }) => {
  await page.goto("/team/people");
  const botao = page.getByRole("link", { name: "Acessos" }).first();
  await expect(botao).toBeInViewport();
  const box = await botao.boundingBox();
  const largura = page.viewportSize()!.width;
  expect(box!.x + box!.width).toBeLessThanOrEqual(largura);
});
