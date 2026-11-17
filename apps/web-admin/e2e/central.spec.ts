import { expect, test } from "@playwright/test";

// F10 no Chrome real contra a pilha local (Keycloak + API + site), com a conta do patrão.
const sufixo = () => Math.random().toString(36).slice(2, 7);

test("patrão percorre os três setores com a própria identidade e a ação dele fica no nome dele", async ({ page }) => {
  await page.goto("/overview/sectors");
  const nome = await page.locator(".user-name").innerText();
  for (const s of ["Retirada, balcão e fornecedores", "Entregas, motoristas e veículos", "Contabilidade e documentos"]) {
    await expect(page.getByRole("article", { name: s })).toBeVisible();
  }
  await page.screenshot({ path: "../../docs/evidence/telas-web/F10-setores.png", fullPage: true });
  // Entra em cada setor pelo link do cartão: a pessoa no topo nunca muda.
  await page.getByRole("article", { name: "Retirada, balcão e fornecedores" }).getByRole("link", { name: "Pedidos de retirada" }).click();
  await expect(page).toHaveURL(/\/sales\/orders\?type=PICKUP/);
  await expect(page.getByRole("heading", { name: "Pedidos de retirada e entrega" })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Setor" })).toHaveValue("PICKUP");
  await expect(page.locator(".user-name")).toHaveText(nome);
  await page.goBack();
  await page.getByRole("article", { name: "Entregas, motoristas e veículos" }).getByRole("link", { name: "Viagens" }).click();
  await expect(page.getByRole("heading", { name: "Viagens de entrega" })).toBeVisible();
  await expect(page.locator(".user-name")).toHaveText(nome);
  await page.goBack();
  await page.getByRole("article", { name: "Contabilidade e documentos" }).getByRole("link", { name: "Guias" }).click();
  await expect(page.getByRole("heading", { name: /Guias, vencimentos/ })).toBeVisible();
  await expect(page.locator(".user-name")).toHaveText(nome);

  // O patrão corrige um cadastro (fictício) e o histórico registra o PATRÃO, pelo site.
  const cliente = `Cliente Fictício F10 ${sufixo()}`;
  await page.goto("/sales/customers");
  await page.getByRole("button", { name: /Novo cliente/ }).click();
  await page.getByLabel("Nome *").fill(cliente);
  await page.getByRole("button", { name: "Cadastrar cliente" }).click();
  await page.getByRole("searchbox", { name: "Buscar cliente por nome" }).fill(cliente);
  await page.getByRole("searchbox", { name: "Buscar cliente por nome" }).press("Enter");
  await expect(page).toHaveURL(/q=Cliente/);
  await page.getByRole("button", { name: `Editar ${cliente}` }).click();
  await page.getByLabel("Nome *").fill(cliente + " Ltda");
  await page.getByRole("button", { name: "Salvar" }).click();
  await page.goto("/team/audit?sector=COUNTER");
  const evento = page.locator("ol.timeline > li", { hasText: "Corrigiu cliente" }).first();
  await expect(evento).toContainText(nome);
  await expect(evento).toContainText("Site do patrão");
  await evento.getByRole("button", { name: "Só este registro" }).click();
  await expect(page).toHaveURL(/entity_type=customer&entity_id=/);
  await expect(page.locator("ol.timeline > li")).toHaveCount(2); // criação + correção, ambas do patrão
  await page.screenshot({ path: "../../docs/evidence/telas-web/F10-historico-registro.png", fullPage: true });
  // O link profundo sobrevive a recarregar a página.
  await page.reload();
  await expect(page.getByText(/Só o registro: Cliente/)).toBeVisible();
});

test("busca geral, aprovações e endereço inexistente", async ({ page }) => {
  await page.goto("/overview");
  const busca = page.getByRole("searchbox", { name: /Buscar pedido, cliente, placa/ });
  await busca.fill("Cliente Fictício F10");
  await busca.press("Enter");
  await expect(page).toHaveURL(/\/search\?q=Cliente/);
  await expect(page.getByRole("region", { name: "Clientes" })).toBeVisible();
  await page.getByRole("region", { name: "Clientes" }).getByRole("link").first().click();
  await expect(page).toHaveURL(/\/sales\/customers\?q=/);
  await expect(page.getByRole("rowheader").first()).toContainText("Cliente Fictício F10");

  await page.goto("/overview/approvals");
  await expect(page.getByRole("heading", { name: "Aprovações pendentes" })).toBeVisible();
  await page.waitForLoadState("networkidle");
  const api = await (await page.request.get("/api/v1/approvals")).json();
  if (api.items.length === 0) await expect(page.getByText("Nada esperando a sua decisão")).toBeVisible();
  else await expect(page.getByRole("link", { name: "Abrir para decidir" })).toHaveCount(api.items.length);
  await page.screenshot({ path: "../../docs/evidence/telas-web/F10-aprovacoes.png", fullPage: true });

  await page.goto("/isto/nao/existe");
  await expect(page.getByRole("heading", { name: "Página não encontrada" })).toBeVisible();
});

test("erro de rede: lista mostra a falha e recupera com 'Tentar novamente'", async ({ page }) => {
  await page.route("**/api/v1/orders?**", (r) => r.abort("internetdisconnected"));
  await page.goto("/sales/orders");
  await expect(page.getByRole("heading", { name: "Sem conexão com o servidor" })).toBeVisible();
  await page.unroute("**/api/v1/orders?**");
  await page.getByRole("button", { name: /Tentar novamente/ }).click();
  await expect(page.getByRole("heading", { name: "Pedidos de retirada e entrega" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Sem conexão com o servidor" })).toHaveCount(0);
});

test("sessão expirada com formulário aberto: aviso, nada digitado se perde e segue após entrar de novo", async ({ page, context }) => {
  await page.goto("/operation/suppliers");
  await page.getByRole("button", { name: /Novo fornecedor/ }).click();
  const nome = `Fornecedor Fictício F10 ${sufixo()}`;
  await page.getByLabel("Nome *").fill(nome);
  // A sessão do servidor some (como quando expira): só o cookie de sessão; o de CSRF continua, como na vida real.
  // Guardamos a sessão para simular "entrei de novo em outra aba" com a mesma conta.
  const cookies = await context.cookies();
  expect(cookies.map((c) => c.name)).toContain("XSRF-TOKEN");
  await context.clearCookies({ name: /^(?!XSRF-TOKEN$)/ });
  await page.getByRole("button", { name: "Cadastrar fornecedor" }).click();
  const aviso = page.getByRole("alertdialog", { name: /sessão terminou/ });
  await expect(aviso).toBeVisible();
  await expect(page.getByLabel("Nome *")).toHaveValue(nome);
  await page.screenshot({ path: "../../docs/evidence/telas-web/F10-sessao-expirada.png" });
  // Ainda sem sessão: "Já entrei" não fecha o aviso.
  await aviso.getByRole("button", { name: "Já entrei, continuar" }).click();
  await expect(aviso.getByRole("alert")).toContainText("Ainda não encontramos");
  // "Entrou em outra aba" (mesma conta): o aviso fecha e o texto continua; repetir o envio conclui.
  await context.addCookies(cookies);
  await aviso.getByRole("button", { name: "Já entrei, continuar" }).click();
  await expect(aviso).toBeHidden();
  await expect(page.getByLabel("Nome *")).toHaveValue(nome);
  await page.getByRole("button", { name: "Cadastrar fornecedor" }).click();
  await page.getByRole("searchbox", { name: "Buscar fornecedor por nome" }).fill(nome);
  await page.getByRole("searchbox", { name: "Buscar fornecedor por nome" }).press("Enter");
  await expect(page.getByRole("rowheader", { name: nome })).toBeVisible();
});
