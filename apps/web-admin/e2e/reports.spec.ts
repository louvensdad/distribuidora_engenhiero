import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { formatMoney, todayBr } from "../src/app/format";

// F09 no Chrome real contra a pilha local: a tela, o gráfico, a API e os arquivos baixados mostram os MESMOS números.
test("relatório: indicadores, colunas 3D, tabela e arquivos reconciliam com a API", async ({ page }) => {
  const today = todayBr();
  await page.goto(`/reports?period=MONTH&date=${today}`);
  const kpis = page.getByLabel("Indicadores do período");
  await expect(kpis).toBeVisible();
  const api = await (await page.request.get(`/api/v1/reports/overview?period=MONTH&date=${today}`)).json();
  expect(api.timezone).toBe("America/Sao_Paulo");
  // Indicadores = colunas do gráfico = valores da API (mesmas strings).
  for (const c of api.chart) {
    await expect(kpis.getByText(c.label, { exact: true })).toBeVisible();
    await expect(kpis.locator(".kpi", { hasText: c.label }).locator(".kpi-value")).toHaveText(formatMoney(c.amount));
  }
  const svg = page.getByRole("img", { name: /Fechamento —/ });
  const amounts = await svg.locator("g[data-key]").evaluateAll((gs) => gs.map((g) => g.getAttribute("data-amount")));
  expect(amounts).toEqual(api.chart.map((c: { amount: string }) => c.amount));
  const table = page.locator("section.card", { has: page.getByRole("heading", { name: "Fechamento (tabela)" }) });
  await expect(table.getByRole("row", { name: new RegExp("^Receita líquida " + formatMoney(api.result.net_revenue).replace(/[.$()]/g, "\\$&")) })).toBeVisible();
  if (api.result.profit) {
    await expect(table.getByRole("row", { name: /(Lucro|Prejuízo) do período/ })).toContainText(formatMoney(api.result.profit));
  }
  // A soma da linha do tempo bate com o total, centavo a centavo.
  const cents = (s: string) => Math.round(Number(s) * 100);
  expect(api.timeline.reduce((a: number, p: { net_revenue: string }) => a + cents(p.net_revenue), 0)).toBe(cents(api.result.net_revenue));
  await page.screenshot({ path: "../../docs/evidence/telas-web/F09-relatorio.png", fullPage: true });

  // CSV baixado pelo botão: mesmos valores (vírgula decimal).
  const [csv] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "CSV" }).click()]);
  const text = readFileSync((await csv.path())!, "utf8");
  expect(text).toContain(`Receita líquida;${api.result.net_revenue.replace(".", ",")}`);
  expect(text).toContain(`(-) Despesas;${api.result.expenses_total.replace(".", ",")}`);
  if (api.result.profit) expect(text).toMatch(new RegExp(`(Lucro|Prejuízo);${api.result.profit.replace(".", ",")}`));
  expect(csv.suggestedFilename()).toMatch(/^relatorio-.*\.csv$/);
  const [xlsx] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "Planilha (XLSX)" }).click()]);
  expect(readFileSync((await xlsx.path())!).subarray(0, 2).toString()).toBe("PK");
  const [pdf] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "PDF" }).click()]);
  expect(readFileSync((await pdf.path())!).subarray(0, 5).toString()).toBe("%PDF-");

  // 2D para impressão/acessibilidade: sem faces de profundidade, mesmos valores.
  await page.getByRole("button", { name: "Versão 2D" }).click();
  await expect(svg.locator("polygon")).toHaveCount(0);
  // Drilldown: abrir as linhas da receita leva ao pedido de origem.
  await table.getByRole("button", { name: "Abrir lançamentos de Vendas cumpridas (bruto)" }).click();
  const lines = page.locator("section.card", { has: page.getByRole("heading", { name: /^Lançamentos de Vendas cumpridas/ }) });
  await expect(lines).toBeVisible();
  if (api.result.gross_revenue !== "0.00") await expect(lines.getByRole("link", { name: /Pedido #/ }).first()).toBeVisible();
});

test("filtro de dia usa a data civil de São Paulo e a tela segue a API", async ({ page }) => {
  await page.goto("/reports");
  await page.getByRole("radio", { name: "Dia" }).click();
  await expect(page).toHaveURL(/period=DAY/);
  const date = new URL(page.url()).searchParams.get("date")!;
  expect(date).toBe(todayBr());
  const api = await (await page.request.get(`/api/v1/reports/overview?period=DAY&date=${date}`)).json();
  expect(api.from).toBe(date);
  await expect(page.getByLabel("Indicadores do período").locator(".kpi").first().locator(".kpi-value")).toHaveText(formatMoney(api.chart[0].amount));
});

test("gastos por categoria antes do fechamento; metas com realizado do relatório; clientes por medida", async ({ page }) => {
  test.setTimeout(60_000);
  const today = todayBr();
  const api = await (await page.request.get(`/api/v1/reports/overview?period=MONTH&date=${today}`)).json();
  await page.goto(`/reports/categories?period=MONTH&date=${today}`);
  await expect(page.locator(".category-total")).toContainText(formatMoney(api.result.expenses_total));
  await page.locator(".category-list summary").first().click();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F09-categorias.png", fullPage: true });

  // Meta de vendas do mês: o realizado vem do mesmo cálculo; depois é cancelada com motivo (não sobra lixo ativo).
  await page.goto("/reports/goals");
  const form = page.getByRole("form", { name: "Nova meta" });
  const title = `Meta E2E ${Date.now()}`;
  await form.getByLabel("Título").fill(title);
  await form.getByLabel("Meta (R$)").fill("100.000,00");
  await form.getByRole("button", { name: "Criar rascunho" }).click();
  const card = page.getByRole("article", { name: title });
  await expect(card).toBeVisible();
  await expect(card.getByText("Realizado").locator("xpath=following-sibling::dd[1]")).toHaveText(formatMoney(api.result.net_revenue));
  await expect(card.getByRole("progressbar")).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F09-metas.png", fullPage: true });
  await card.getByLabel("Motivo do cancelamento").fill("Limpeza do teste E2E");
  await card.getByRole("button", { name: "Cancelar meta" }).click();
  await expect(card).toHaveCount(0); // sai da lista viva e vai para "Canceladas"
  const canceladas = page.locator("details", { hasText: "Canceladas neste mês" });
  await canceladas.locator("summary").click();
  await expect(canceladas.getByText(title)).toBeVisible();

  await page.goto(`/reports/customers?period=MONTH&date=${today}`);
  await expect(page.getByRole("heading", { name: "Clientes por medida" })).toBeVisible();
  await expect(page.getByText("Como cada medida é calculada").or(page.getByText("Sem vendas cumpridas no período"))).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F09-clientes.png", fullPage: true });
});
