import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';

// Standalone test config — kept separate from vite.config.ts so unit tests for
// pure logic (e.g. src/lib/sla.ts) run in a plain Node environment without
// pulling in the React/Vite build pipeline.
export default defineConfig({
  // Mirror the app's "@/..." path alias so tests can import modules that use it
  // (e.g. anything transitively importing "@/lib/storage").
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
