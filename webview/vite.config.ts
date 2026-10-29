import { defineConfig } from "vite";
import solidPlugin from "vite-plugin-solid";
import { resolve } from "node:path";
export default defineConfig({
  plugins: [solidPlugin()],
  root: resolve(__dirname, "src/webview"),
  base: "./",
  build: {
    outDir: resolve(__dirname, "dist"), emptyOutDir: true,
    rollupOptions: { output: { entryFileNames: "main.js", assetFileNames: "main.css" } }
  }
});
