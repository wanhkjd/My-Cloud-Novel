import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

async function expectAccessible(page: Page) {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
  expect(
    result.violations.map((violation) => ({
      rule: violation.id,
      nodes: violation.nodes.map((node) => ({ target: node.target, reason: node.failureSummary })),
    })),
  ).toEqual([]);
}

const text =
  '作者：书房测试员\n第一卷 来信\n第一章 清晨\n' +
  Array.from(
    { length: 24 },
    (_, i) =>
      '这是第' +
      (i + 1) +
      '段原创验收文字。窗外的树影落在纸页上，我们在这里验证阅读位置与书签。'.repeat(3),
  ).join('\n') +
  '\n第二章 回声\n这是一段新的原创故事。\n<img src=x onerror="window.__novelXss=true">\n';
async function csrf(api: APIRequestContext) {
  const r = await api.get('/api/auth/csrf');
  expect(r.ok()).toBeTruthy();
  const t = await r.json();
  return { [t.headerName]: t.token };
}
async function apiLogin(api: APIRequestContext) {
  const r = await api.post('/api/auth/login', {
    headers: await csrf(api),
    form: { username: 'admin', password: 'isolated-e2e-test-password-only' },
  });
  expect(r.ok()).toBeTruthy();
}
async function createPublic(api: APIRequestContext, name: string) {
  await apiLogin(api);
  const r = await api.post('/api/books', {
    headers: await csrf(api),
    multipart: {
      file: { name: name + '.txt', mimeType: 'text/plain', buffer: Buffer.from(text + name) },
    },
  });
  expect(r.status()).toBe(201);
  const book = await r.json();
  const update = await api.patch('/api/books/' + book.id, {
    headers: await csrf(api),
    data: {
      title: name,
      author: '书房测试员',
      description: '仅用于自动验收的原创文本。',
      catalogPublished: true,
      textPublished: true,
    },
  });
  expect(update.ok()).toBeTruthy();
  return book;
}

// This server is isolated from the user's library; a failed test must not leak data to the next one.
test.beforeEach(async ({ request, baseURL }) => {
  expect(baseURL).toBe('http://127.0.0.1:15173');
  await apiLogin(request);
  for (const book of await (await request.get('/api/books')).json()) {
    expect(
      (await request.delete('/api/books/' + book.id, { headers: await csrf(request) })).status(),
    ).toBe(204);
  }
  expect((await request.post('/api/auth/logout', { headers: await csrf(request) })).status()).toBe(
    204,
  );
});

test('owner imports a draft, restores a bookmark, and explicitly publishes to visitors', async ({
  page,
  browser,
}) => {
  const failures: string[] = [];
  page.on('pageerror', (error) => failures.push(error.message));
  await page.goto('/login');
  await expectAccessible(page);
  await page.getByLabel('用户名', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill('isolated-e2e-test-password-only');
  await page.getByRole('button', { name: '进入书房' }).click();
  await expect(page).toHaveURL(/\/admin$/);
  await page
    .getByLabel('选择 TXT 文件', { exact: true })
    .setInputFiles({ name: '原创验收读本.txt', mimeType: 'text/plain', buffer: Buffer.from(text) });
  const responsePromise = page.waitForResponse(
    (r) => r.url().endsWith('/api/books') && r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '导入为私有草稿' }).click();
  const uploaded = await responsePromise;
  expect(uploaded.status()).toBe(201);
  const book = await uploaded.json();
  await expect(page.getByRole('status')).toContainText('2 章');
  await expectAccessible(page);
  const guest = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' });
  const visitor = await guest.newPage();
  await visitor.goto('/books/' + book.id);
  await expect(visitor.getByRole('alert')).toContainText('尚未公开');
  expect((await guest.request.get('/api/books/' + book.id + '/download')).status()).toBe(404);
  await page.bringToFront();
  await page.getByRole('link', { name: '去私有预览，核对解析结果 →' }).click();
  await expectAccessible(page);
  await page.getByRole('link', { name: '开始阅读' }).click();
  await expect(page.locator('.chapter-heading h1')).toHaveText('第一章 清晨');
  await expectAccessible(page);
  await expect
    .poll(async () =>
      Number(
        (await page.getByRole('button', { name: '暂停阅读计时' }).innerText()).match(
          /(\d+) 秒/,
        )?.[1] ?? 0,
      ),
    )
    .toBeGreaterThanOrEqual(2);
  await page.getByRole('button', { name: '添加第3段书签', exact: true }).click();
  await expectAccessible(page);
  await page.getByLabel('此刻的感想').fill('记下这束清晨的光。');
  await expect(page.getByLabel('公开这条感想（仅随已公开正文展示）')).not.toBeChecked();
  await page.getByRole('button', { name: '保存书签', exact: true }).click();
  await expect(page.locator('.editor-dialog')).not.toBeVisible();
  await page.getByRole('button', { name: '下一章', exact: true }).first().click();
  await expect(page.locator('.chapter-heading h1')).toHaveText('第二章 回声');
  expect(await page.locator('.reading-body img').count()).toBe(0);
  expect(await page.evaluate(() => Object.hasOwn(window, '__novelXss'))).toBe(false);
  await page.getByRole('button', { name: '打开目录' }).click();
  await page.getByRole('button', { name: '我的书签 1' }).click();
  await page.getByRole('button', { name: /记下这束清晨的光/ }).click();
  await expect(page.locator('.chapter-heading h1')).toHaveText('第一章 清晨');
  await expect(page.locator('.reader-save-status')).toContainText('第 3 段');
  await page.getByRole('link', { name: '原创验收读本', exact: true }).click();
  const progress = await page.request.get('/api/me/progress/' + book.id);
  expect((await progress.json()).paragraphIndex).toBe(2);
  await page.getByRole('link', { name: '继续阅读', exact: true }).click();
  await expect(page.locator('.reader-save-status')).toContainText('第 3 段');
  await page.getByRole('link', { name: '原创验收读本', exact: true }).click();
  await page.getByRole('link', { name: '管理书房', exact: true }).click();
  await page.getByRole('button', { name: '编辑原创验收读本', exact: true }).click();
  await page.getByLabel('向访客展示这本书的书目').check();
  await page.getByRole('button', { name: '保存设置' }).click();
  await expect(page.locator('.editor-dialog')).not.toBeVisible();
  await visitor.reload();
  await expect(visitor.getByText('正文暂未公开', { exact: true })).toBeVisible();
  expect((await guest.request.get('/api/books/' + book.id + '/chapters/0')).status()).toBe(404);
  await page.bringToFront();
  await page.getByRole('button', { name: '编辑原创验收读本', exact: true }).click();
  await page.getByLabel('我有权公开正文，允许访客阅读和下载').check();
  await page.getByRole('button', { name: '保存设置' }).click();
  await expect(page.locator('.editor-dialog')).not.toBeVisible();
  await visitor.reload();
  await expect(visitor.getByRole('link', { name: '开始阅读' })).toBeVisible();
  expect(await (await guest.request.get('/api/books/' + book.id + '/bookmarks')).json()).toEqual(
    [],
  );
  await page.getByRole('link', { name: '阅读足迹', exact: true }).click();
  await expectAccessible(page);
  await page.getByRole('button', { name: '书签与感想 1' }).click();
  await expectAccessible(page);
  await page.getByRole('button', { name: '编辑感想' }).click();
  await page.getByLabel('公开这条感想（仅随已公开正文展示）').check();
  await page.getByRole('button', { name: '保存书签', exact: true }).click();
  await expect(page.locator('.editor-dialog')).not.toBeVisible();
  expect(
    (await (await guest.request.get('/api/books/' + book.id + '/bookmarks')).json())[0].note,
  ).toBe('记下这束清晨的光。');
  expect((await (await page.request.get('/api/me/stats')).json()).totalSeconds).toBeGreaterThan(0);
  expect(await page.evaluate(() => localStorage.getItem('cloud-novel:guest:v1'))).toBeNull();
  const deletion = await page.request.delete('/api/books/' + book.id, {
    headers: await csrf(page.request),
  });
  expect(deletion.status()).toBe(204);
  await guest.close();
  expect(failures).toEqual([]);
});

test('mobile visitor reads public text, keeps local notes, and never writes the owner journal', async ({
  page,
  request,
}, testInfo) => {
  const book = await createPublic(request, '手机验收读本');
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '我的书架' })).toBeVisible();
  await expectAccessible(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByLabel('搜索书名或作者').fill('手机验收');
  await page.locator('.book-card').click();
  await expectAccessible(page);
  await page.getByRole('link', { name: '开始阅读' }).click();
  await expect(page.locator('.chapter-heading h1')).toHaveText('第一章 清晨');
  await page.getByRole('button', { name: '切换夜间模式' }).click();
  await expect(page.locator('.reader-shell')).toHaveClass(/night/);
  await expectAccessible(page);
  await page.getByRole('button', { name: '添加第3段书签', exact: true }).click();
  await expectAccessible(page);
  await page.getByLabel('此刻的感想').fill('访客自己的小记');
  await expect(page.getByLabel('公开这条感想（仅随已公开正文展示）')).toHaveCount(0);
  await page.getByRole('button', { name: '保存书签', exact: true }).click();
  await expect(page.locator('.editor-dialog')).not.toBeVisible();
  await page.getByRole('button', { name: '打开目录' }).click();
  await page.getByRole('button', { name: '我的书签 1' }).click();
  await page.getByRole('button', { name: /访客自己的小记/ }).click();
  await expect(page.locator('.reader-save-status')).toContainText('第 3 段');
  await page.getByRole('link', { name: '手机验收读本', exact: true }).click();
  await page.getByRole('link', { name: '继续阅读', exact: true }).click();
  await expect(page.locator('.reader-save-status')).toContainText('第 3 段');
  await page.reload();
  await expect(page.locator('.reader-shell')).toHaveClass(/night/);
  await expect(page.getByRole('button', { name: '编辑第3段书签', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('mobile-reader.png') });
  expect((await page.request.get('/api/me/history')).status()).toBe(401);
  expect(await (await request.get('/api/me/progress/' + book.id)).text()).toBe('');
  expect(await (await request.get('/api/me/bookmarks')).json()).toEqual([]);
  expect(
    await page.evaluate(
      () => JSON.parse(localStorage.getItem('cloud-novel:guest:v1')!).bookmarks[0].note,
    ),
  ).toBe('访客自己的小记');
  expect(errors).toEqual([]);
  expect(
    (await request.delete('/api/books/' + book.id, { headers: await csrf(request) })).status(),
  ).toBe(204);
});

test('empty public shelf has no detected WCAG A/AA violations', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '书架还在整理中' })).toBeVisible();
  await expectAccessible(page);
});
