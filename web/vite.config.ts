/// <reference types="vitest/config" />
import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In development the app calls the API on VITE_API_BASE (http://localhost:8080, CORS allowed for
// the dev server). In production Spring Boot serves the built app, so API calls are same-origin.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(__dirname, "./src") } },
  build: { outDir: "dist", sourcemap: false },
  test: { environment: "jsdom", include: ["src/**/*.test.{ts,tsx}"] },
});
