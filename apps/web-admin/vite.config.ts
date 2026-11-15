/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// O site é servido na MESMA ORIGEM da API (sem CORS): em desenvolvimento o Vite repassa estas rotas ao backend.
// changeOrigin=false preserva o Host (localhost:4200) para o BFF montar o redirect OIDC de volta ao site.
const api = process.env.ENGENHEIRO_API ?? "http://localhost:8081";
const backend = { target: api, changeOrigin: false, xfwd: true };

export default defineConfig({
  plugins: [react()],
  server: {
    port: 4200,
    strictPort: true,
    proxy: { "/api": backend, "/bff": backend, "/oauth2": backend, "/login/oauth2": backend },
  },
  build: { sourcemap: false, target: "es2023" },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    css: false,
  },
});
