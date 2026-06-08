import { defineConfig } from 'vitest/config';

// Standalone test config — kept separate from vite.config.ts so unit tests for
// pure logic (e.g. src/lib/sla.ts) run in a plain Node environment without
// pulling in the React/Vite build pipeline.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
