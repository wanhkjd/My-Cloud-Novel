import { afterEach, beforeAll, expect, it, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount, RouterLinkStub } from '@vue/test-utils';
import { resetCsrf } from '../lib/api';
import type { Book } from '../lib/types';
import AdminView from './AdminView.vue';

enableAutoUnmount(afterEach);
afterEach(() => {
  vi.unstubAllGlobals();
  resetCsrf();
});

function mountAdmin() {
  return mount(AdminView, { global: { stubs: { RouterLink: RouterLinkStub } } });
}

beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };
});

function makeBook(overrides: Partial<Book> = {}): Book {
  return {
    id: 'book-1',
    title: '测试书',
    author: '作者',
    description: '',
    encoding: 'UTF-8',
    chapterCount: 1,
    volumeCount: 1,
    characterCount: 10,
    catalogPublished: false,
    textPublished: false,
    createdAt: 0,
    timelineDate: null,
    hasCover: false,
    canRead: true,
    preface: '',
    ...overrides,
  };
}

it('submits a cleared timeline date to the API as null rather than an empty string', async () => {
  const book = makeBook({ timelineDate: '2019-05-01' });
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify([book])))
    .mockResolvedValueOnce(new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' })))
    .mockResolvedValueOnce(new Response('', { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([book])));
  vi.stubGlobal('fetch', fetchMock);
  const page = mountAdmin();
  await flushPromises();
  await page.get('[aria-label="编辑测试书"]').trigger('click');
  await flushPromises();
  await page.get('input[type="date"]').setValue('');
  await page.get('.editor-dialog form').trigger('submit');
  await flushPromises();
  const patch = fetchMock.mock.calls.find((call) => call[1]?.method === 'PATCH');
  expect(patch).toBeTruthy();
  const body = JSON.parse(patch![1].body as string);
  expect(body.timelineDate).toBeNull();
  expect(body.title).toBe('测试书');
});

it('keeps the named import result distinct while the book list is still loading', async () => {
  const book: Book = {
    id: 'draft-book',
    title: '原创测试读本',
    author: '测试作者',
    description: '',
    encoding: 'UTF-8',
    chapterCount: 2,
    volumeCount: 1,
    characterCount: 120,
    catalogPublished: false,
    textPublished: false,
    createdAt: 0,
    timelineDate: null,
    hasCover: false,
    canRead: true,
    preface: '',
  };
  // Hold the refresh response so both live regions coexist without relying on timing.
  let finishRefresh!: (response: Response) => void;
  const refresh = new Promise<Response>((resolve) => {
    finishRefresh = resolve;
  });
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(new Response('[]'))
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' })),
    )
    .mockResolvedValueOnce(new Response(JSON.stringify(book), { status: 201 }))
    .mockReturnValueOnce(refresh);
  vi.stubGlobal('fetch', fetchMock);
  const page = mountAdmin();
  await flushPromises();
  const input = page.get('input[type="file"]');
  Object.defineProperty(input.element, 'files', {
    value: [new File(['第一章 清晨\n原创正文'], '原创测试读本.txt', { type: 'text/plain' })],
    configurable: true,
  });
  await input.trigger('change');
  await page.get('form').trigger('submit');
  await flushPromises();

  try {
    expect(fetchMock).toHaveBeenNthCalledWith(
      4,
      '/api/books',
      expect.objectContaining({ method: 'GET' }),
    );
    expect(page.findAll('[role="status"]')).toHaveLength(2);
    expect(page.get('[role="status"].empty-state').text()).toBe('正在读取藏书…');
    const result = page.get('[role="status"][aria-label="小说导入结果"]');
    expect(result.text()).toContain('《原创测试读本》导入完成');
    expect(result.text()).toContain('2 章');
    expect(result.text()).toContain('尚未公开');
  } finally {
    finishRefresh(new Response(JSON.stringify([book])));
    await flushPromises();
  }

  expect(page.find('[role="status"].empty-state').exists()).toBe(false);
  expect(page.get('[role="status"][aria-label="小说导入结果"]').text()).toContain('2 章');
  expect(page.get('.manage-row').text()).toContain('原创测试读本');
});

it('uploads a chosen cover image as multipart and refreshes the list', async () => {
  const before = makeBook({ hasCover: false });
  const after = makeBook({ hasCover: true });
  const fetchMock = vi
    .fn()
    .mockResolvedValueOnce(new Response(JSON.stringify([before])))
    .mockResolvedValueOnce(new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' })))
    .mockResolvedValueOnce(new Response(JSON.stringify(after), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify([after])));
  vi.stubGlobal('fetch', fetchMock);
  const page = mountAdmin();
  await flushPromises();
  await page.get('[aria-label="编辑测试书"]').trigger('click');
  await flushPromises();
  const cover = page.get('input[aria-label="选择封面图片"]');
  Object.defineProperty(cover.element, 'files', {
    value: [new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'c.png', { type: 'image/png' })],
    configurable: true,
  });
  await cover.trigger('change');
  await flushPromises();
  const post = fetchMock.mock.calls.find((call) => String(call[0]).endsWith('/book-1/cover'));
  expect(post).toBeTruthy();
  expect(post![1].method).toBe('POST');
  expect(post![1].body).toBeInstanceOf(FormData);
});

it('describes separate MySQL and private original-file backups rather than the retired disk database', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[]')));
  const page = mountAdmin();
  await flushPromises();
  const guidance = page.get('.management-note').text();
  expect(guidance).toContain('MySQL');
  expect(guidance).toContain('原始 TXT');
  expect(guidance).toContain('私有磁盘');
  expect(guidance).toContain('分别备份');
  expect(guidance).not.toContain('阅读记录和原始文件保存在后端数据目录中');
});
