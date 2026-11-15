import { defineConfig } from "@playwright/test";

// E2E contra a pilha LOCAL real: Keycloak (8180) + API (8081) + site Vite (4200). Usa o Chrome instalado no sistema.
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.ENGENHEIRO_SITE ?? "http://localhost:4200",
    channel: "chrome",
    locale: "pt-BR",
    timezoneId: "America/Sao_Paulo",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    { name: "desktop-1440", dependencies: ["setup"], use: { viewport: { width: 1440, height: 900 }, storageState: "e2e/.auth/patrao.json" } },
    { name: "tablet-768", dependencies: ["setup"], testMatch: /layout\.spec\.ts/, use: { viewport: { width: 768, height: 1024 }, storageState: "e2e/.auth/patrao.json" } },
    { name: "celular-360", dependencies: ["setup"], testMatch: /layout\.spec\.ts/, use: { viewport: { width: 360, height: 780 }, storageState: "e2e/.auth/patrao.json" } },
  ],
});
