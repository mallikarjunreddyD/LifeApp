import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: 'test',
  testMatch: '**/*.spec.ts',
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['junit', { outputFile: 'test-results/browser.xml' }]],
  timeout: 45_000,
  use: {
    ...devices['iPhone 12'],
    browserName: 'chromium',
    baseURL: 'https://127.0.0.1:4186',
    ignoreHTTPSErrors: true,
    launchOptions: { args: ['--ignore-certificate-errors'] },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run preview -- --strictPort --port 4186',
    url: 'https://127.0.0.1:4186/',
    ignoreHTTPSErrors: true,
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
