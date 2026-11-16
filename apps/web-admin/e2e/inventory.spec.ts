import { expect, test } from "@playwright/test";

// F03 ponta a ponta no Chrome real, contra Keycloak + API + Postgres locais (dados fictícios criados pelo teste).
test.describe("estoque — pilha real", () => {
  test("fornecedor, produto com conversão, recebimento com frete, perda aprovada e devolução", async ({ page }) => {
    test.setTimeout(90_000);
    const tag = Date.now().toString(36).toUpperCase();
    const fornecedor = `Fornecedor E2E ${tag}`;
    const produto = `Cana E2E ${tag}`;

    // Fornecedor
    await page.goto("/operation/suppliers");
    await page.getByRole("button", { name: /novo fornecedor/i }).click();
    await page.getByLabel("Nome *").fill(fornecedor);
    await page.getByRole("button", { name: "Cadastrar fornecedor" }).click();
    await expect(page.getByRole("rowheader", { name: fornecedor })).toBeVisible();

    // Produto em dúzias, 1 feixe = 3 dúzias, unidade comercial confirmada
    await page.goto("/operation/products");
    await page.getByRole("button", { name: /novo produto/i }).click();
    await page.getByLabel("Nome *").fill(produto);
    await page.getByLabel("Código *").fill(`E2E-${tag}`);
    await page.getByLabel("Unidade-base de estoque *").selectOption("DUZIA");
    await page.getByLabel(/confirmo que esta é a unidade/i).check();
    await page.getByRole("button", { name: /adicionar conversão/i }).click();
    await page.locator("#pc-u-0").selectOption("FEIXE");
    await page.locator("#pc-f-0").fill("3");
    await page.getByRole("button", { name: "Cadastrar produto" }).click();
    const prow = page.getByRole("row", { name: new RegExp(produto) });
    await expect(prow).toContainText("1 feixe = 3 dúzias");
    await expect(prow).toContainText("confirmada");

    // W06: 10 feixes previstos, 9 recebidos a R$ 30,00 + frete R$ 15,00 → R$ 285,00; entra 27 dúzias
    await page.goto("/operation/receipts/new");
    await page.getByLabel("Fornecedor *").selectOption({ label: fornecedor });
    await page.getByLabel("Documento (nota ou romaneio)").fill(`NF-${tag}`);
    await page.getByLabel("Produto *").selectOption({ label: produto });
    await page.getByLabel("Medida").selectOption("FEIXE");
    await page.getByLabel("Prevista").fill("10");
    await page.getByLabel("Recebida *").fill("9");
    await page.getByLabel("Custo unitário (R$)").fill("30,00");
    await page.getByLabel("Frete de compra autorizado (R$)").fill("15,00");
    await page.getByRole("button", { name: "Confirmar entrada" }).click();
    await expect(page.getByRole("heading", { name: fornecedor })).toBeVisible();
    await expect(page.getByText("Confirmado", { exact: true })).toBeVisible();
    const linha = page.getByRole("row", { name: new RegExp(produto) });
    await expect(linha).toContainText("= 27 dúzias");
    await expect(linha).toContainText("−1"); // diferença registrada, sem inventar estoque
    await expect(linha).toContainText("R$ 270,00");
    await expect(linha).toContainText("R$ 15,00");
    await expect(page.getByRole("row", { name: /Total/ })).toContainText("R$ 285,00");
    await page.screenshot({ path: "test-results/telas/F03-recebimento-confirmado.png", fullPage: true });

    // W05: saldo, valor e custo médio vindos do servidor
    await page.goto("/operation/stock");
    const srow = page.getByRole("row", { name: new RegExp(produto) }).filter({ has: page.locator('td[data-label="Físico"]') });
    await expect(srow).toContainText("27 dúzias");
    await expect(srow).toContainText("R$ 285,00");

    // Perda: registro não baixa; aprovação baixa 2 dúzias pelo custo médio
    await page.getByRole("button", { name: /registrar perda/i }).click();
    await page.getByLabel("Produto *").selectOption({ label: `${produto} (E2E-${tag})` });
    await page.getByLabel("Quantidade *").fill("2");
    await page.getByLabel("Motivo *").selectOption("QUEBRA");
    await page.getByRole("button", { name: "Registrar perda", exact: true }).last().click();
    const perda = page.getByRole("row", { name: new RegExp(produto) }).filter({ hasText: "Quebra" });
    await expect(perda).toContainText("Aguardando aprovação");
    await expect(srow).toContainText("27 dúzias");
    await perda.getByRole("button", { name: /aprovar baixa/i }).click();
    await expect(srow.locator('td[data-label="Físico"]')).toHaveText("25 dúzias");
    await expect(srow.locator('td[data-label="Perdas"]')).toHaveText("2 dúzias");
    await expect(srow.locator('td[data-label="Valor em estoque"]')).toHaveText("R$ 263,89"); // 285 − 2 × 285/27

    // Histórico do produto
    await srow.getByRole("button", { name: "Movimentos" }).click();
    const historico = page.getByRole("list", { name: "Últimos movimentos do produto" });
    await expect(historico).toContainText("Entrada de fornecedor");
    await expect(historico).toContainText("Perda");
    await page.screenshot({ path: "test-results/telas/F03-estoque.png", fullPage: true });

    // Devolução de 1 feixe ao fornecedor, ao custo histórico da entrada
    await page.goto("/operation/receipts");
    await page.getByRole("link", { name: fornecedor }).first().click();
    await page.getByLabel("Linha *").selectOption("1");
    await page.getByLabel("Quantidade (na medida da linha) *").fill("1");
    await page.getByLabel("Motivo *").fill("Feixe verde devolvido");
    await page.getByRole("button", { name: "Registrar devolução" }).click();
    await expect(page.getByRole("row", { name: new RegExp(produto) })).toContainText("3 dúzias"); // devolvido
    await page.goto("/operation/stock");
    await expect(page.locator('tr:has(td[data-label="Físico"])', { hasText: produto }).locator('td[data-label="Físico"]')).toHaveText("22 dúzias");

    // Trilha
    await page.goto("/team/audit");
    await expect(page.getByText("inventory.receipts.confirm").first()).toBeVisible();
  });
});
