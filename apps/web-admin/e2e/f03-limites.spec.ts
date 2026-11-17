import { expect, test, type Page } from "@playwright/test";

// Limites da F03 resolvidos, no Chrome real contra Keycloak + API: conta a pagar, anexo privado, contagem e unidades.
const PNG = Buffer.from("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415478da63f8ffff3f0005fe02fea7d6a4b20000000049454e44ae426082", "hex");

async function apiPost(page: Page, path: string, body: unknown, extra: Record<string, string> = {}) {
  const csrf = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const r = await page.request.post(path, { data: body, headers: { "X-XSRF-TOKEN": decodeURIComponent(csrf), ...extra } });
  expect(r.ok(), await r.text()).toBeTruthy();
  return r.json();
}

test("recebimento confirmado gera conta a pagar; anexo privado; contagem aplicada; unidade criada e desativada", async ({ page }) => {
  test.setTimeout(90_000);
  const tag = Date.now().toString(36).toUpperCase();
  await page.goto("/operation/stock"); // garante o cookie XSRF
  const sup = await apiPost(page, "/api/v1/suppliers", { name: `Fornecedor L ${tag}` });
  const prod = await apiPost(page, "/api/v1/products", { sku: `L-${tag}`, name: `Cana L ${tag}`, base_unit: "DUZIA", base_unit_confirmed: true });
  const rec = await apiPost(page, "/api/v1/inventory/receipts", { supplier_id: sup.id, document_number: `NF-L-${tag}`,
    lines: [{ product_id: prod.id, quantity_received: "20", unit_cost: "8.00" }] }, { "Idempotency-Key": `e2e-${tag}-rec-0001` });

  // Confirmação com vencimento → conta a pagar
  await page.goto(`/operation/receipts/${rec.id}`);
  await page.getByLabel("Vencimento da conta a pagar").fill("2026-12-20");
  await page.getByRole("button", { name: "Confirmar entrada" }).click();
  await expect(page.getByText("Confirmado", { exact: true })).toBeVisible();

  // Anexo: PNG válido disponível e baixável; arquivo disfarçado recusado
  await page.locator('input[type="file"]').setInputFiles({ name: "romaneio.png", mimeType: "image/png", buffer: PNG });
  await expect(page.getByText("“romaneio.png” anexado.")).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({ name: "nota.pdf", mimeType: "application/pdf", buffer: Buffer.from("<html>x</html>") });
  await expect(page.getByText(/Arquivo recusado: Tipo não aceito/)).toBeVisible();
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "romaneio.png" }).click()]);
  expect(download.suggestedFilename()).toBe("romaneio.png");
  await page.screenshot({ path: "test-results/telas/F03-recebimento-anexos.png", fullPage: true });

  await page.goto("/finance/payables");
  const conta = page.getByRole("row", { name: new RegExp(`NF-L-${tag}`) });
  await expect(conta).toContainText("20/12/2026");
  await expect(conta).toContainText("R$ 160,00");
  await expect(conta).toContainText("Em aberto");

  // Contagem: sistema 20, contado 18 → falta 2 aplicada pelo médio (R$ 16,00)
  await page.goto("/operation/stock");
  await page.getByRole("button", { name: /registrar contagem/i }).click();
  await page.getByLabel("Buscar produto").fill(`Cana L ${tag}`);
  await page.getByLabel("Produto *").selectOption({ label: `Cana L ${tag} (L-${tag})` });
  await page.getByLabel("Quantidade contada *").fill("18");
  await page.getByLabel("Observação da contagem *").fill("Contagem E2E");
  await page.getByRole("button", { name: "Registrar contagem", exact: true }).last().click();
  const ajuste = page.locator("section[aria-labelledby=titulo-ajustes] tr", { hasText: `Cana L ${tag}` });
  await expect(ajuste).toContainText("Aguardando aprovação");
  await ajuste.getByRole("button", { name: "Aplicar ajuste" }).click();
  await expect(ajuste).toContainText("Aplicado");
  await expect(ajuste).toContainText("−R$ 16,00");
  const saldo = page.locator('tr:has(td[data-label="Físico"])', { hasText: `Cana L ${tag}` });
  await expect(saldo.locator('td[data-label="Físico"]')).toHaveText("18 dúzias");

  // Unidades
  await page.goto("/team/branches");
  await page.getByLabel("Nome *").fill(`Unidade E2E ${tag}`);
  await page.getByRole("button", { name: "Criar" }).click();
  const unidade = page.getByRole("row", { name: new RegExp(`Unidade E2E ${tag}`) });
  await expect(unidade).toContainText("Ativa");
  page.once("dialog", (d) => void d.accept("Teste E2E"));
  await unidade.getByRole("button", { name: "Desativar" }).click();
  await expect(unidade).toContainText("Inativa");
  const matriz = page.getByRole("row", { name: /^Matriz/ });
  page.once("dialog", (d) => void d.accept("não deve passar"));
  await matriz.getByRole("button", { name: "Desativar" }).click();
  await expect(matriz.getByRole("alert")).toContainText("pessoas ativas ou estoque");
});
