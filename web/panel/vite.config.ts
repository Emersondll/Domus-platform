/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // O proxy evita CORS no desenvolvimento: a API roda na 8081.
    proxy: { "/api": "http://localhost:8081", "/dev": "http://localhost:8081" },
  },
  test: { environment: "jsdom", globals: true, setupFiles: ["./src/setup.ts"] },
});
