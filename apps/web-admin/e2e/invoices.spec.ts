import { expect, test } from "@playwright/test";

// F08 no Chrome real, contra a pilha local SEM provedor fiscal nem de pagamentos (estado real do projeto): tudo o que
// depende de terceiro precisa aparecer BLOQUEADO, com histórico, e nada pode virar "Autorizada".
test("integrações: três NÃO CONFIGURADAS e aviso de pagamento sem provedor recusado", async ({ page, request }) => {
  await page.goto("/finance/integrations");
  await expect(page.getByText("NÃO CONFIGURADA — bloqueada")).toHaveCount(3);
  const hook = await request.post("http://localhost:8081/webhooks/payments/qualquer", { data: { id: "ev-e2e", type: "PAYMENT_CONFIRMED" } });
  expect(hook.status()).toBe(404);
  expect((await hook.json()).status).toBe("NOT_CONFIGURED");
});

test("nota: configuração fiscal, rascunho, NCM pelo contador, conferência e transmissão BLOQUEADA (continua conferida)", async ({ page }) => {
  test.setTimeout(90_000);
  await page.goto("/finance/invoices");
  const cfg = page.getByRole("article", { name: "Configuração fiscal" });
  await expect(cfg.getByText("Emissão BLOQUEADA — provedor NÃO CONFIGURADO")).toBeVisible();
  // NF-e e NFC-e pelo cliente: consumidor sem CPF/CNPJ sai em NFC-e (NF-e exigiria o documento do cliente).
  await cfg.getByRole("button", { name: "Editar configuração" }).click();
  const form = cfg.getByRole("group", { name: "Editar configuração fiscal" });
  await form.getByLabel("Documento fiscal (com o contador)").selectOption("BOTH");
  await form.getByLabel("CNPJ do emitente").fill("11.222.333/0001-81"); // CNPJ fictício de exemplo
  await form.getByLabel("Inscrição estadual").fill("ISENTO");
  await form.getByLabel("Regime tributário").selectOption("SIMPLES_NACIONAL");
  await form.getByLabel("Série").fill("1");
  await form.getByLabel("CFOP padrão de venda").fill("5102");
  await form.getByRole("button", { name: "Salvar configuração" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Configuração fiscal salva" })).toBeVisible();
  // Rascunhos esquecidos por execuções anteriores: descarta.
  const leftovers = page.getByRole("article", { name: /Nota do pedido/ }).filter({ has: page.locator(".badge", { hasText: /^Rascunho$/ }) });
  for (let i = await leftovers.count(); i > 0; i--) {
    const l = leftovers.first();
    await l.getByLabel("Motivo").fill("Limpeza de teste E2E");
    await l.getByRole("button", { name: "Descartar" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Nota descartada" })).toBeVisible();
    await page.reload();
  }
  await expect(cfg.getByText("Falta definir")).toHaveCount(0);
  const draft = page.getByRole("form", { name: "Gerar rascunho de nota" });
  await expect.poll(() => draft.getByLabel("Pedido").locator("option").count(), { timeout: 10_000 }).toBeGreaterThan(1).catch(() => undefined);
  const options = await draft.getByLabel("Pedido").locator("option").allTextContents();
  test.skip(options.length < 2, "Sem pedido elegível na base local");
  // Escolhe um pedido sem nota viva (o servidor recusa o segundo rascunho com 409).
  let created = false;
  for (const label of options.slice(1)) {
    await draft.getByLabel("Pedido").selectOption({ label });
    await draft.getByRole("button", { name: "Gerar rascunho" }).click();
    const ok = page.getByRole("status").filter({ hasText: "Rascunho criado" });
    const dup = draft.getByRole("alert");
    await expect(ok.or(dup)).toBeVisible();
    if (await ok.count() > 0) { created = true; break; }
  }
  expect(created).toBeTruthy();
  const card = page.getByRole("article", { name: /Nota do pedido/ }).filter({ hasText: "Rascunho" }).first();
  const ncmInputs = card.getByLabel(/^NCM de /);
  for (let i = await ncmInputs.count(); i > 0; i--) {
    await ncmInputs.first().fill("1212.93.00");
    await card.getByRole("button", { name: "Salvar NCM" }).first().click();
    await expect(page.getByRole("status").filter({ hasText: "NCM do produto salvo" })).toBeVisible();
  }
  const number = (await card.getByRole("heading").textContent())!.match(/#(\d+)/)![1];
  const nota = page.getByRole("article", { name: `Nota do pedido ${number}` });
  await nota.getByRole("button", { name: "Conferir (contador)" }).click();
  await expect(nota.getByText("Conferida pelo contador")).toBeVisible();
  await nota.getByRole("button", { name: "Transmitir" }).click();
  await expect(nota.getByRole("alert")).toContainText("Nada foi enviado nem emitido");
  await expect(nota.getByText("Conferida pelo contador")).toBeVisible();
  await expect(nota.getByText("Autorizada", { exact: true })).toHaveCount(0);
  await page.reload();
  await nota.getByText(/Histórico com o provedor/).click();
  await expect(nota.getByText(/Transmissão: Bloqueado/).first()).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F08-nota-bloqueada.png", fullPage: true });
  // Limpeza: descarta para o pedido poder ser usado de novo.
  await nota.getByLabel("Motivo").fill("Teste E2E sem provedor");
  await nota.getByRole("button", { name: "Descartar" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Nota descartada" })).toBeVisible();
  await page.goto("/finance/integrations");
  await expect(page.getByRole("article", { name: "Tentativas recentes" })).toContainText("Bloqueado");
  await page.screenshot({ path: "../../docs/evidence/telas-web/F08-integracoes.png", fullPage: true });
});
