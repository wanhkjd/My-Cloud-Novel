import { defineConfig, devices } from '@playwright/test';
import { e2eEnvironment } from './testing/infrastructure';
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 60000,
  expect: { timeout: 10000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:15173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'java -jar target/cloud-novel-0.1.0.jar',
      cwd: '../backend',
      url: 'http://127.0.0.1:18080/api/ready',
      reuseExistingServer: false,
      timeout: 90000,
      env: e2eEnvironment(process.env),
    },
    {
      command: 'npm run dev -- --port 15173 --strictPort',
      url: 'http://127.0.0.1:15173',
      reuseExistingServer: false,
      timeout: 60000,
      env: { API_PROXY_TARGET: 'http://127.0.0.1:18080' },
    },
  ],
});
