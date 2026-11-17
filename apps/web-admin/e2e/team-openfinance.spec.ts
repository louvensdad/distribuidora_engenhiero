import { expect, test } from "@playwright/test";

// Pedido do responsável (ADR-021) no Chrome real: folga no calendário da equipe e Open Finance sem agregador
// (NÃO CONFIGURADO de verdade: o servidor recusa e nada é importado).
test("funcionários: folga registrada, aprovada e visível no calendário; depois cancelada", async ({ page }) => {
  const d = new Date(Date.now() + (40 + Math.floor(Math.random() * 200)) * 86400000);
  const iso = d.toISOString().slice(0, 10);
  const monthsAhead = (d.getUTCFullYear() - new Date().getFullYear()) * 12 + d.getUTCMonth() - new Date().getMonth();
  await page.goto("/team/absences");
  const form = page.getByRole("form", { name: "Registrar folga ou férias" });
  await form.getByLabel("Funcionário *").selectOption({ label: "Recebimento Exemplo" });
  await form.getByLabel("Tipo *").selectOption("DAY_OFF");
  await form.getByLabel("Início *").fill(iso);
  await form.getByRole("button", { name: "Registrar" }).click();
  await expect(page.getByRole("status")).toContainText("aguardando aprovação");
  for (let i = 0; i < monthsAhead; i++) await page.getByRole("button", { name: "Próximo mês" }).click();
  const br = iso.split("-").reverse().join("/");
  const row = page.getByRole("row", { name: new RegExp(`Recebimento Exemplo.*Folga.*${br}`) });
  await row.getByRole("button", { name: "Aprovar" }).click();
  await expect(page.getByRole("status")).toContainText("Recebimento Exemplo: ausência aprovada");
  const dayRow = page.getByRole("row", { name: new RegExp(`^${br.slice(0, 5)}`) });
  await expect(dayRow).toContainText("Recebimento Exemplo (folga)");
  await page.screenshot({ path: "../../docs/evidence/telas-web/ADR021-funcionarios.png", fullPage: true });
  await page.getByRole("row", { name: new RegExp(`Recebimento Exemplo.*Folga.*${br}`) }).getByRole("button", { name: "Cancelar" }).click();
  await page.getByPlaceholder("Motivo").fill("teste automatizado");
  await page.getByRole("button", { name: "Confirmar" }).click();
  await expect(page.getByRole("status")).toContainText("cancelada");
});

test("Open Finance sem agregador: NÃO CONFIGURADO e conexão recusada pelo servidor", async ({ page }) => {
  await page.goto("/finance/open-finance");
  await expect(page.getByText("NÃO CONFIGURADO", { exact: true })).toBeVisible();
  const form = page.getByRole("form", { name: "Conectar conta bancária" });
  await form.getByLabel("Conta no sistema *").selectOption({ label: "Banco principal" });
  await form.getByLabel("Banco *").fill("Banco Fictício");
  await form.getByLabel("CNPJ da empresa *").fill("11.222.333/0001-81");
  await form.getByRole("button", { name: "Conectar (requer agregador)" }).click();
  await expect(form.getByRole("alert")).toContainText("NÃO CONFIGURADO");
  await expect(page.getByText("Nada nesta situação")).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/ADR021-open-finance.png", fullPage: true });
});
