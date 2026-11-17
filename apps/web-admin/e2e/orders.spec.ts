import { expect, test, type Page } from "@playwright/test";

// F04 no Chrome real contra Keycloak + API + Postgres: pedido, caixa, dinheiro com troco, Pix pendente, retirada.
async function apiPost(page: Page, path: string, body: unknown, extra: Record<string, string> = {}) {
  const csrf = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const r = await page.request.post(path, { data: body, headers: { "X-XSRF-TOKEN": decodeURIComponent(csrf), ...extra } });
  expect(r.ok(), await r.text()).toBeTruthy();
  return r.json();
}

test("pedido de R$ 75 pago com R$ 100: troco R$ 25, caixa +75, retirada e fechamento conferível", async ({ page }) => {
  test.setTimeout(120_000);
  const tag = Date.now().toString(36).toUpperCase();
  await page.goto("/sales/orders");
  // Preparação por API (mesma sessão do patrão): produto com preço, estoque e cliente.
  const prod = await apiPost(page, "/api/v1/products", { sku: `P-${tag}`, name: `Cana P ${tag}`, base_unit: "DUZIA", base_unit_confirmed: true });
  const etag = `"${prod.version}"`;
  await apiPost(page, `/api/v1/products/${prod.id}/prices`, { price: "7.50" }, { "If-Match": etag });
  const sup = await apiPost(page, "/api/v1/suppliers", { name: `Fornecedor P ${tag}` });
  const rec = await apiPost(page, "/api/v1/inventory/receipts", { supplier_id: sup.id, lines: [{ product_id: prod.id, quantity_received: "50", unit_cost: "3.00" }] },
    { "Idempotency-Key": `e2e-${tag}-rec-000001` });
  await apiPost(page, `/api/v1/inventory/receipts/${rec.id}/confirm`, null, { "Idempotency-Key": `e2e-${tag}-conf-00001`, "If-Match": `"${rec.version}"` });
  await apiPost(page, "/api/v1/customers", { name: `Ana E2E ${tag}` });

  // Caixa do turno (fecha um caixa antigo, se a execução anterior deixou aberto)
  await page.goto("/finance/my-cash");
  await page.getByLabel("Caixa aberto").or(page.getByLabel("Fundo de troco (R$) *")).waitFor();
  if (await page.getByLabel("Caixa aberto").isVisible()) {
    await page.getByLabel("Valor contado (R$) *").fill("0");
    await page.getByLabel("Justificativa (obrigatória se houver diferença)").fill("encerramento de teste anterior");
    await page.getByRole("button", { name: "Fechar e enviar para conferência" }).click();
  }
  await page.getByLabel("Fundo de troco (R$) *").fill("50,00");
  await page.getByRole("button", { name: "Abrir caixa" }).click();
  await expect(page.getByLabel("Caixa aberto")).toContainText("R$ 50,00");

  // Novo pedido: 10 dúzias × R$ 7,50 do servidor
  await page.goto("/sales/orders/new");
  await page.getByLabel("Buscar cliente").fill(`Ana E2E ${tag}`);
  await page.getByLabel("Cliente *").selectOption({ label: `Ana E2E ${tag}` });
  await page.getByLabel("Buscar produto").fill(`Cana P ${tag}`);
  await page.getByLabel("Produto *").selectOption({ label: `Cana P ${tag}` });
  await expect(page.getByText("R$ 7,50 / dúzia")).toBeVisible();
  await page.getByLabel("Quantidade *").fill("10");
  await page.getByRole("button", { name: "Salvar e reservar" }).click();
  await expect(page.getByRole("heading", { name: new RegExp(`Pedido #\\d+ · Ana E2E ${tag}`) })).toBeVisible();
  await expect(page.getByText("Reservado", { exact: true })).toBeVisible();
  await expect(page.getByText("Não pago", { exact: true })).toBeVisible();

  // Retirar sem pagar: o servidor recusa
  await page.getByRole("button", { name: "Registrar retirada de tudo" }).click();
  await expect(page.getByRole("alert")).toContainText("Pagamento pendente");

  // Dinheiro: 75 pagos com 100
  await page.getByLabel("Dinheiro apresentado (R$) *").fill("100,00");
  await page.getByRole("button", { name: "Registrar pagamento" }).click();
  await expect(page.getByLabel("Último pagamento")).toContainText("Troco a entregar: R$ 25,00");
  await expect(page.getByText("Pago", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Registrar retirada de tudo" }).click();
  await expect(page.getByText("Concluído", { exact: true })).toBeVisible();
  const hist = page.getByRole("region", { name: "Histórico do pedido" });
  await expect(hist).toContainText("Cadastrou pedido");
  await expect(hist).toContainText("Registrou pagamento");
  await expect(hist).toContainText("Registrou retirada/entrega");
  await page.screenshot({ path: "test-results/telas/F04-pedido-concluido.png", fullPage: true });

  // Caixa: esperado 125 (50 + 75, sem os 25 de troco); fechar com diferença exige justificativa
  await page.goto("/finance/my-cash");
  await expect(page.getByLabel("Caixa aberto")).toContainText("R$ 125,00");
  await page.getByLabel("Valor contado (R$) *").fill("120,00");
  await page.getByRole("button", { name: "Fechar e enviar para conferência" }).click();
  await expect(page.getByText(/justifique/)).toBeVisible();
  await page.getByLabel("Justificativa (obrigatória se houver diferença)").fill(`Troco a mais ${tag}`);
  await page.getByRole("button", { name: "Fechar e enviar para conferência" }).click();
  const linha = page.getByRole("row", { name: new RegExp(`Troco a mais ${tag}`) });
  await expect(linha).toContainText("−R$ 5,00");
  await expect(linha).toContainText("Fechado — aguardando conferência");
  await expect(linha.getByRole("button", { name: "Conferir" })).toHaveCount(0); // operador não confere o próprio caixa
  await page.screenshot({ path: "test-results/telas/F04-caixa.png", fullPage: true });
});

test("Pix: comprovante não paga; fica aguardando até a conferência manual", async ({ page }) => {
  test.setTimeout(90_000);
  const tag = Date.now().toString(36).toUpperCase();
  await page.goto("/sales/orders");
  const prod = await apiPost(page, "/api/v1/products", { sku: `X-${tag}`, name: `Cana X ${tag}`, base_unit: "DUZIA", base_unit_confirmed: true });
  await apiPost(page, `/api/v1/products/${prod.id}/prices`, { price: "10.00" }, { "If-Match": `"${prod.version}"` });
  const sup = await apiPost(page, "/api/v1/suppliers", { name: `F X ${tag}` });
  const rec = await apiPost(page, "/api/v1/inventory/receipts", { supplier_id: sup.id, lines: [{ product_id: prod.id, quantity_received: "5", unit_cost: "4.00" }] },
    { "Idempotency-Key": `e2e-${tag}-rec-000002` });
  await apiPost(page, `/api/v1/inventory/receipts/${rec.id}/confirm`, null, { "Idempotency-Key": `e2e-${tag}-conf-00002`, "If-Match": `"${rec.version}"` });
  const cli = await apiPost(page, "/api/v1/customers", { name: `Bruno E2E ${tag}` });
  const ord = await apiPost(page, "/api/v1/orders", { type: "PICKUP", customer_id: cli.id, items: [{ product_id: prod.id, quantity: "2" }] },
    { "Idempotency-Key": `e2e-${tag}-ord-000001` });

  await page.goto(`/sales/orders/${ord.id}`);
  await page.getByLabel("Meio *").selectOption("PIX");
  await page.getByRole("button", { name: "Registrar pagamento" }).click();
  await expect(page.getByLabel("Último pagamento")).toContainText("Aguardando conferência");
  await expect(page.getByText("Não pago", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Registrar retirada de tudo" }).click();
  await expect(page.getByRole("alert")).toContainText("Pagamento pendente");
  page.once("dialog", (d) => void d.accept("Extrato do banco conferido"));
  await page.getByRole("button", { name: "Conferir" }).click();
  await expect(page.getByText("Pago", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Pagamentos" })).toContainText("conferido por");
});
