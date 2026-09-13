import { defineConfig, devices } from '@playwright/test';
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
      url: 'http://127.0.0.1:18080/api/health',
      reuseExistingServer: false,
      timeout: 90000,
      env: {
        SERVER_ADDRESS: '127.0.0.1',
        SERVER_PORT: '18080',
        ADMIN_USERNAME: 'admin',
        ADMIN_PASSWORD: 'isolated-e2e-test-password-only',
        DB_URL: 'jdbc:h2:mem:e2e;MODE=MySQL;DB_CLOSE_DELAY=-1',
        DB_USERNAME: 'sa',
        DB_PASSWORD: '',
        DB_INIT_MODE: 'always',
        BOOK_STORAGE: './target/e2e-books',
        COOKIE_SECURE: 'false',
      },
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
