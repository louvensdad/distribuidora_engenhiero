import { test as setup } from "@playwright/test";
import { loginPeloSite } from "./keycloak";

setup("login do patrão (Keycloak + TOTP + PKCE)", async ({ page }) => {
  setup.setTimeout(90_000); // pode esperar a próxima janela de TOTP
  await loginPeloSite(page, "patrao");
  await page.getByRole("navigation", { name: "Seções principais" }).waitFor();
  await page.context().storageState({ path: "e2e/.auth/patrao.json" });
});
