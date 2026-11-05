import { defineConfig, devices } from '@playwright/test';

/** E2E runs against the backend `local` profile + compose stack (quickstart.md). */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  // specs share one seeded backend; run serially in file order (channel-configs before config-change-log)
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: process.env.PNH_CONSOLE_URL ?? 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.PNH_CONSOLE_URL
    ? undefined
    : { command: 'pnpm dev', url: 'http://localhost:5173', reuseExistingServer: true },
});
