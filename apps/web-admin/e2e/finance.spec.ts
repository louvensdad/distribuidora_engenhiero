import { expect, test } from "@playwright/test";

// F06 no Chrome real contra Keycloak + API + Postgres: conta bancária com saldo inicial, despesa aprovada, pagamento
// (sem segunda despesa), extrato, transferência (consolidado igual), resultado com drilldown e conciliação.
test("financeiro: despesa → conta a pagar → pagamento, extrato, transferência e resultado com lançamentos", async ({ page }) => {
  test.setTimeout(180_000);
  const tag = Date.now().toString(36).toUpperCase();
  const bank = `Banco E2E ${tag}`;

  // Conta bancária com saldo inicial (W08).
  await page.goto("/finance/cash");
  const nova = page.getByRole("form", { name: "Nova conta" });
  await nova.getByLabel("Nome *").fill(bank);
  await nova.getByLabel("Saldo inicial (R$)").fill("1.000,00");
  await nova.getByRole("button", { name: "Criar conta" }).click();
  const row = page.getByRole("row", { name: new RegExp(bank) });
  await expect(row).toContainText("R$ 1.000,00");

  // Despesa por categoria com competência; aprovação gera a conta a pagar.
  await page.goto("/finance/expenses");
  await page.getByRole("button", { name: /Cadastrar despesa/ }).click();
  const form = page.getByRole("form", { name: "Nova despesa" });
  await form.getByLabel("Subcategoria *").fill("Energia");
  await form.getByLabel("Descrição *").fill(`Conta de luz ${tag}`);
  await form.getByLabel("Valor (R$) *").fill("250,00");
  await form.getByRole("button", { name: "Cadastrar despesa" }).click();
  const exp = page.getByRole("row", { name: new RegExp(`Conta de luz ${tag}`) });
  await expect(exp).toContainText("Aguardando aprovação");
  await exp.getByRole("button", { name: "Aprovar" }).click();
  await expect(exp).toContainText("Aprovada");
  await expect(exp).toContainText("conta: Em aberto (R$ 250,00)");

  // Pagar a conta pelo banco: baixa o passivo, sem nova despesa.
  await page.goto("/finance/payables");
  const pay = page.getByRole("row", { name: new RegExp(`Conta de luz ${tag}`) });
  await pay.getByRole("button", { name: "Abrir" }).click();
  const pf = page.getByRole("form", { name: "Registrar pagamento da conta" });
  await pf.getByLabel("Conta (caixa da empresa ou banco) *").selectOption({ label: `${bank} — saldo R$ 1.000,00` });
  await pf.getByRole("button", { name: "Registrar pagamento" }).click();
  await expect(page.getByRole("region", { name: new RegExp(`Conta de luz ${tag}`) })).toContainText("Pago");

  // Extrato: 1.000 − 250 = 750.
  await page.goto("/finance/cash");
  await expect(page.getByRole("row", { name: new RegExp(bank) })).toContainText("R$ 750,00");
  await page.getByRole("row", { name: new RegExp(bank) }).getByRole("button", { name: "Ver extrato" }).click();
  const ext = page.getByRole("region", { name: /Extrato/ });
  await expect(ext).toContainText("saldo final R$ 750,00");
  await expect(ext).toContainText("Pagamento de conta");
  const disponivel = await page.locator(".kpi-highlight .kpi-value").textContent();

  // Transferência para o cofre: o disponível consolidado não muda.
  const tf = page.getByRole("form", { name: "Transferência interna" });
  await tf.getByLabel("De *").selectOption({ label: bank });
  await tf.getByLabel("Para *").selectOption({ label: "Caixa da empresa (cofre)" });
  await tf.getByLabel("Valor (R$) *").fill("100,00");
  await tf.getByRole("button", { name: "Transferir" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Transferência registrada." })).toBeVisible();
  await expect(page.getByRole("row", { name: new RegExp(bank) })).toContainText("R$ 650,00");
  await expect(page.locator(".kpi-highlight .kpi-value")).toHaveText(disponivel!);
  await page.screenshot({ path: "../../docs/evidence/telas-web/F06-contas.png", fullPage: true });

  // Resultado: Estrutura abre os lançamentos (inclui a despesa); caixa à parte; conciliação.
  await page.goto("/finance/closing");
  const dre = page.getByRole("region", { name: "Resultado gerencial (competência)" });
  await dre.getByRole("button", { name: "Abrir lançamentos de Estrutura" }).click();
  await expect(page.getByRole("region", { name: /Lançamentos de Estrutura/ })).toContainText(`Energia — Conta de luz ${tag}`);
  await expect(page.getByRole("region", { name: "Posição de caixa (até o fim do mês)" })).toBeVisible();
  await expect(page.getByRole("region", { name: /Conciliação/ })).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F06-resultado.png", fullPage: true });
});
