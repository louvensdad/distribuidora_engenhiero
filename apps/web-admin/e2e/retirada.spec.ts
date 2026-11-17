import { expect, test } from "@playwright/test";

// F11: o pedido feito no app Retirada (conta do balcão) aparece no site do patrão com o autor e o app corretos.
test("pedido do app Retirada aparece no site com autor e app", async ({ page }) => {
  const r = await page.request.get("/api/v1/audit-events?client_id=engenheiro-retirada&entity_type=order&limit=1");
  const ev = (await r.json()).items[0];
  test.skip(!ev, "nenhum pedido feito pelo app Retirada nesta base local");
  await page.goto(`/sales/orders/${ev.entity_id}`);
  const order = await (await page.request.get(`/api/v1/orders/${ev.entity_id}`)).json();
  await expect(page.getByRole("heading", { name: new RegExp(`#${order.number}`) })).toBeVisible();
  await expect(page.getByText(order.created_by_name).first()).toBeVisible();
  expect(order.created_by_name).toBe(ev.actor_name);
  await expect(page.getByText(/App Engenheiro Retirada/).first()).toBeVisible();
  await page.screenshot({ path: "../../docs/evidence/telas-web/F11-pedido-do-balcao-no-site.png", fullPage: true });
});
