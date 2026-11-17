import { expect, test } from "@playwright/test";

// F07 no Chrome real: o patrão decide o que o contador preparou no app Contábil (emulador) — guia e folha —,
// registra os pagamentos em etapa separada e usa a central de documentos com arquivo real.
const PDF = Buffer.from("%PDF-1.4\n% documento ficticio de teste\n1 0 obj<<>>endobj\n%%EOF\n", "latin1");

test("guia preparada no app: aprovar mantém pendente; pagamento é etapa separada", async ({ page }) => {
  await page.goto("/finance/taxes");
  await page.getByRole("article").first().or(page.getByText("Nenhuma guia")).waitFor();
  const all = page.getByRole("article").filter({ hasText: "Simples Nacional teste" });
  const open = all.filter({ hasText: /Aguardando aprovação|Aprovada — pagamento pendente/ }).first();
  test.skip(await open.count() === 0, "Sem guia do app Contábil pendente nesta execução (fluxo cruzado manual)");
  await expect(open.getByRole("link", { name: "guia-ficticia.pdf" })).toBeVisible();
  if (await open.getByRole("button", { name: "Aprovar" }).count() > 0) {
    await open.getByRole("button", { name: "Aprovar" }).click();
    await expect(page.getByRole("status").filter({ hasText: "o pagamento continua pendente" })).toBeVisible();
  }
  const approved = all.filter({ hasText: "Aprovada — pagamento pendente" }).first();
  await expect(approved).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F07-guia-aprovada-pendente.png", fullPage: true });
  const sel = approved.getByLabel("Conta (caixa da empresa ou banco) *");
  const options = await sel.locator("option").allTextContents();
  await sel.selectOption({ label: options.find((o) => o.startsWith("Banco principal"))! });
  await approved.getByRole("button", { name: "Registrar pagamento" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Pagamento da guia registrado" })).toBeVisible();
});

test("guia pelo site: preparar (API), anexar PDF real, aprovar e pagar; arquivo baixável", async ({ page }) => {
  test.setTimeout(120_000);
  const tag = Date.now().toString(36).toUpperCase();
  await page.goto("/finance/taxes");
  const csrf = decodeURIComponent((await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value ?? "");
  const month = new Date().toISOString().slice(0, 7);
  const due = new Date(Date.now() + 15 * 86400000).toISOString().slice(0, 10);
  const g = await (await page.request.post("/api/v1/tax-obligations", { headers: { "X-XSRF-TOKEN": csrf, "Idempotency-Key": `e2e-${tag}-guia-0001` },
    data: { kind: "ISS", description: `ISS E2E ${tag}`, competence: month, due_date: due, amount: "123.45" } })).json();
  const up = await page.request.post(`/api/v1/tax-obligations/${g.id}/attachments`, { headers: { "X-XSRF-TOKEN": csrf },
    multipart: { file: { name: "iss.pdf", mimeType: "application/pdf", buffer: PDF } } });
  expect((await up.json()).state).toBe("AVAILABLE");
  expect((await page.request.post(`/api/v1/tax-obligations/${g.id}/submit`, { headers: { "X-XSRF-TOKEN": csrf, "If-Match": `"${g.version}"` } })).ok()).toBeTruthy();
  await page.reload();
  const card = page.getByRole("article").filter({ hasText: `ISS E2E ${tag}` });
  await card.getByRole("button", { name: "Aprovar" }).click();
  await expect(card.getByText("Aprovada — pagamento pendente")).toBeVisible();
  const pay = card.getByRole("group", { name: "Pagamento da guia ISS" });
  const options = await pay.getByLabel("Conta (caixa da empresa ou banco) *").locator("option").allTextContents();
  await pay.getByLabel("Conta (caixa da empresa ou banco) *").selectOption({ label: options.find((o) => o.startsWith("Banco principal"))! });
  await card.getByRole("button", { name: "Registrar pagamento" }).click();
  await expect(page.getByRole("article").filter({ hasText: `ISS E2E ${tag}` }).locator(".badge")).toHaveText("Paga");
  const link = page.getByRole("article").filter({ hasText: `ISS E2E ${tag}` }).getByRole("link", { name: "iss.pdf" });
  const href = await link.getAttribute("href");
  const file = await page.request.get(href!);
  expect(Buffer.from(await file.body()).equals(PDF)).toBeTruthy();
});

test("folha enviada pelo app: patrão vê por pessoa, aprova e paga os salários", async ({ page }) => {
  await page.goto("/finance/payroll");
  await page.getByRole("article").first().or(page.getByText("Nenhuma folha")).waitFor();
  const card = page.getByRole("article").filter({ hasText: "Aguardando aprovação" }).first();
  test.skip(await card.count() === 0, "Sem folha enviada pelo app Contábil nesta execução");
  await card.getByText("Valores por pessoa (dado individual)").click();
  await expect(card).toContainText("Balcão Exemplo");
  await card.getByRole("button", { name: "Aprovar folha" }).click();
  await expect(page.getByRole("status").filter({ hasText: "salários ainda a pagar" })).toBeVisible();
  const approved = page.getByRole("article").filter({ hasText: "Aprovada — pagamento pendente" }).first();
  const sel = approved.getByLabel("Conta (caixa da empresa ou banco) *");
  const options = await sel.locator("option").allTextContents();
  await sel.selectOption({ label: options.find((o) => o.startsWith("Banco principal"))! });
  await approved.getByRole("button", { name: /Pagar salários líquidos/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "Salários líquidos pagos" })).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F07-folha-paga.png", fullPage: true });
});

test("documentos: envio de arquivo real; sem arquivo não envia", async ({ page }) => {
  const tag = Date.now().toString(36).toUpperCase();
  await page.goto("/finance/documents");
  const form = page.getByRole("form", { name: "Enviar documento" });
  await form.getByLabel("Título *").fill(`Contrato E2E ${tag}`);
  await form.getByRole("button", { name: "Enviar" }).click();
  await expect(form.getByText("Obrigatório")).toBeVisible();
  await form.getByLabel("Tipo *").selectOption("CONTRATO");
  await form.getByLabel("Arquivo *").setInputFiles({ name: "contrato.pdf", mimeType: "application/pdf", buffer: PDF });
  await form.getByRole("button", { name: "Enviar" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Documento enviado" })).toBeVisible();
  await expect(page.getByRole("row", { name: new RegExp(`Contrato E2E ${tag}`) })).toContainText("v1");
  await page.screenshot({ path: "../../docs/evidence/telas-web/F07-documentos.png", fullPage: true });
});
