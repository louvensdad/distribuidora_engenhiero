import { expect, test, type Page } from "@playwright/test";

// F05 no Chrome real contra Keycloak + API + Postgres: pedido de entrega, frota, despacho com capacidade,
// odômetro regressivo, entrega com dinheiro, retorno com prestação e conferência por outra pessoa.
async function apiPost(page: Page, path: string, body: unknown, extra: Record<string, string> = {}) {
  const csrf = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const r = await page.request.post(path, { data: body, headers: { "X-XSRF-TOKEN": decodeURIComponent(csrf), ...extra } });
  expect(r.ok(), await r.text()).toBeTruthy();
  return r.json();
}

function plate(): string {
  const L = "ABCDEFGHJKLMNPRSTUVWXYZ";
  const r = (n: number) => Math.floor(Math.random() * n);
  return `${L[r(23)]}${L[r(23)]}${L[r(23)]}${r(10)}${L[r(23)]}${r(10)}${r(10)}`;
}

test("entrega: despacho com capacidade, saída, dinheiro na entrega, retorno e prestação conferida por outra pessoa", async ({ page }) => {
  test.setTimeout(180_000);
  const tag = Date.now().toString(36).toUpperCase();
  await page.goto("/operation/fleet");
  // Preparação por API (sessão do patrão): produto com preço, estoque, cliente com endereço e um veículo pequeno.
  const prod = await apiPost(page, "/api/v1/products", { sku: `E-${tag}`, name: `Cana E ${tag}`, base_unit: "DUZIA", base_unit_confirmed: true });
  await apiPost(page, `/api/v1/products/${prod.id}/prices`, { price: "7.50" }, { "If-Match": `"${prod.version}"` });
  const sup = await apiPost(page, "/api/v1/suppliers", { name: `Fornecedor E ${tag}` });
  const rec = await apiPost(page, "/api/v1/inventory/receipts", { supplier_id: sup.id, lines: [{ product_id: prod.id, quantity_received: "50", unit_cost: "3.00" }] },
    { "Idempotency-Key": `e2e-${tag}-rec-000001` });
  await apiPost(page, `/api/v1/inventory/receipts/${rec.id}/confirm`, null, { "Idempotency-Key": `e2e-${tag}-conf-00001`, "If-Match": `"${rec.version}"` });
  await apiPost(page, "/api/v1/customers", { name: `Mercado E2E ${tag}`, address: "Rua Exemplo, 100 — Centro" });
  const small = plate();
  await apiPost(page, "/api/v1/vehicles", { plate: small, model: "Moto", capacity_quantity: "5", capacity_unit: "DUZIA", odometer_km: "1000" },
    { "Idempotency-Key": `e2e-${tag}-veh-000001` });

  // Frota (W07): cadastro do veículo pela tela.
  const big = plate();
  await page.reload();
  await page.getByRole("button", { name: "Novo veículo" }).click();
  await page.getByLabel("Placa *").fill(big);
  await page.getByLabel("Modelo *").fill("Fiorino");
  await page.getByLabel("Capacidade *", { exact: true }).fill("100");
  await page.getByLabel("Odômetro atual (km) *").fill("45.200");
  await page.getByRole("button", { name: "Cadastrar veículo" }).click();
  await page.getByRole("link", { name: big }).click();
  await expect(page.getByRole("heading", { name: `Fiorino · ${big}` })).toBeVisible();
  await expect(page.getByText("Dados insuficientes")).toBeVisible();

  // Pedido de entrega (LOG-01): endereço vem do cliente, pagamento combinado obrigatório.
  await page.goto("/sales/orders/new");
  await page.getByLabel("Tipo *").selectOption("DELIVERY");
  await page.getByLabel("Cliente *").selectOption({ label: `Mercado E2E ${tag}` });
  await expect(page.getByLabel("Endereço de entrega *")).toHaveValue("Rua Exemplo, 100 — Centro");
  await page.getByLabel("Produto *").selectOption({ label: `Cana E ${tag}` });
  await page.getByLabel("Quantidade *").fill("10");
  await page.getByRole("button", { name: "Salvar e reservar" }).click();
  await expect(page.getByRole("alert")).toContainText("Corrija");
  await page.getByLabel("Pagamento combinado *").selectOption("CASH");
  await page.getByRole("button", { name: "Salvar e reservar" }).click();
  const heading = page.getByRole("heading", { name: new RegExp(`Pedido #(\\d+) · Mercado E2E ${tag}`) });
  await expect(heading).toBeVisible();
  const orderNo = (await heading.textContent())!.match(/#(\d+)/)![1];
  await expect(page.getByRole("region", { name: "Entrega" })).toContainText("aguardando despacho");
  const orderUrl = page.url();

  // Despacho (E04): capacidade validada pelo servidor.
  await page.goto("/operation/trips");
  await page.getByRole("button", { name: "Criar viagem" }).click();
  await expect(page.getByRole("heading", { name: /Viagem V\d+/ })).toBeVisible();
  const form = page.getByRole("form", { name: "Atribuir carro e motorista" });
  await form.getByLabel("Veículo e placa *").selectOption({ label: `${small} · Moto · 5 dúzias` });
  // Motorista livre (um execução anterior interrompida pode ter deixado outro "em viagem").
  const livres = await form.getByLabel("Motorista autorizado *").locator("option").allTextContents();
  const driver = livres.find((t) => /^Motorista [AB] Exemplo$/.test(t));
  expect(driver, "nenhum motorista livre").toBeTruthy();
  await form.getByLabel("Motorista autorizado *").selectOption({ label: driver! });
  await form.getByRole("checkbox", { name: new RegExp(`#${orderNo} · Mercado E2E ${tag}`) }).check();
  await form.getByRole("button", { name: "Validar capacidade e atribuir" }).click();
  await expect(form.getByText(/acima da capacidade do veículo/)).toBeVisible();
  await form.getByLabel("Veículo e placa *").selectOption({ label: `${big} · Fiorino · 100 dúzias` });
  await form.getByRole("button", { name: "Validar capacidade e atribuir" }).click();
  await expect(page.getByText("Atribuída", { exact: true })).toBeVisible();
  await expect(page.getByText("10 / 100")).toBeVisible();

  // Saída: odômetro regressivo recusado.
  const depart = page.getByRole("form", { name: "Registrar saída" });
  await depart.getByLabel("Odômetro de saída (km) *").fill("45100");
  await depart.getByRole("button", { name: "Registrar saída" }).click();
  await expect(depart.getByText(/Menor que o último odômetro/)).toBeVisible();
  await depart.getByLabel("Odômetro de saída (km) *").fill("45210");
  await depart.getByRole("button", { name: "Registrar saída" }).click();
  await expect(page.getByText("Em rota", { exact: true })).toBeVisible();

  // Caixa do patrão para receber em dinheiro (fecha um antigo se ficou aberto).
  const tripUrl = page.url();
  await page.goto("/finance/my-cash");
  await page.getByLabel("Caixa aberto").or(page.getByLabel("Fundo de troco (R$) *")).waitFor();
  if (await page.getByLabel("Caixa aberto").isVisible()) {
    await page.getByLabel("Valor contado (R$) *").fill("0");
    await page.getByLabel("Justificativa (obrigatória se houver diferença)").fill("encerramento de teste anterior");
    await page.getByRole("button", { name: "Fechar e enviar para conferência" }).click();
  }
  await page.getByLabel("Fundo de troco (R$) *").fill("0");
  await page.getByRole("button", { name: "Abrir caixa" }).click();
  await expect(page.getByLabel("Caixa aberto")).toBeVisible();

  // Entrega com dinheiro (E06/E07).
  await page.goto(tripUrl);
  const stop = page.getByRole("article", { name: `Parada 1: pedido ${orderNo}` });
  await expect(stop).toContainText("Rua Exemplo, 100 — Centro");
  await expect(stop).toContainText("Cobrar R$ 75,00 (combinado: Dinheiro)");
  await stop.getByText("Confirmar entrega", { exact: true }).first().click();
  const dform = stop.getByRole("form", { name: `Entrega do pedido ${orderNo}` });
  await dform.getByLabel("Quem recebeu *").fill("Gerente do mercado");
  await dform.getByLabel("Valor entregue pelo cliente (R$) *").fill("100,00");
  await dform.getByRole("button", { name: "Confirmar entrega" }).click();
  await expect(stop.getByText("Entregue", { exact: true })).toBeVisible();
  await expect(stop).toContainText("Recebido por Gerente do mercado");

  // Retorno com prestação (E10): 75 recebidos, sem gastos.
  const ret = page.getByRole("form", { name: "Retorno e prestação de contas" });
  await ret.getByLabel("Odômetro de chegada (km) *").fill("45250");
  await ret.getByLabel("Dinheiro entregue (R$) *").fill("75,00");
  await ret.getByRole("button", { name: "Enviar prestação" }).click();
  await expect(page.getByText("Retornou", { exact: true })).toBeVisible();
  await expect(page.getByText("declarado R$ 75,00 · diferença R$ 0,00")).toBeVisible();
  // Conferência: quem registrou o retorno não confere.
  await page.getByRole("button", { name: "Conferir retorno" }).click();
  await expect(page.getByRole("alert")).toContainText("outra pessoa");
  await expect(page.getByRole("region", { name: "Histórico da viagem" })).toContainText("Registrou retorno e prestação");
  await page.screenshot({ path: "../../docs/evidence/telas-web/F05-viagem-retornada.png", fullPage: true });

  // Pedido mostra a viagem e a entrega no histórico.
  await page.goto(orderUrl);
  await expect(page.getByText("Concluído", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Entrega" })).toContainText(driver!);
  await expect(page.getByRole("region", { name: "Histórico do pedido" })).toContainText("Pedido entrou na viagem");
  // Veículo: histórico e leituras de odômetro.
  await page.goto("/operation/fleet");
  await page.getByRole("link", { name: big }).click();
  await expect(page.getByRole("region", { name: "Leituras de odômetro" })).toContainText("45.250,0 km");
  await expect(page.getByRole("region", { name: "Histórico do veículo" })).toContainText("Registrou saída da viagem");
  await page.screenshot({ path: "../../docs/evidence/telas-web/F05-veiculo.png", fullPage: true });
});
