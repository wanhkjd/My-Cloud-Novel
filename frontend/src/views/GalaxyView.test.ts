import { RouterLinkStub, flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import GalaxyView from './GalaxyView.vue';
import type { Book } from '../lib/types';

const res = (body: unknown) =>
  ({
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'application/json' }),
    json: async () => body,
    text: async () => JSON.stringify(body),
  }) as unknown as Response;

const makeBook = (over: Partial<Book>): Book => ({
  id: 'b',
  title: '书',
  author: '佚名',
  description: '',
  encoding: 'UTF-8',
  chapterCount: 1,
  volumeCount: 1,
  characterCount: 100,
  catalogPublished: true,
  textPublished: false,
  createdAt: 1,
  canRead: false,
  preface: '',
  timelineDate: null,
  hasCover: false,
  ...over,
});

const mountView = () => mount(GalaxyView, { global: { stubs: { RouterLink: RouterLinkStub } } });

beforeEach(() => {
  vi.stubGlobal('scrollTo', vi.fn());
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null as never);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});
it('renders one node per book, newest first in the DOM', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        res([
          makeBook({ id: 'old', title: '起点', timelineDate: '2018-01-01' }),
          makeBook({ id: 'new', title: '现在', timelineDate: '2022-01-01' }),
        ]),
      ),
  );
  const view = mountView();
  await flushPromises();
  await flushPromises();
  const nodes = view.findAll('.timeline-book');
  expect(nodes).toHaveLength(2);
  expect(nodes[0].text()).toContain('现在');
  expect(nodes[1].text()).toContain('起点');
});

it('shows a dark empty notice when the galaxy has no books', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res([])));
  const view = mountView();
  await flushPromises();
  await flushPromises();
  expect(view.find('.timeline-book').exists()).toBe(false);
  expect(view.get('.galaxy-notice').text()).toContain('还没有');
});

it('reveals every book when IntersectionObserver is unavailable', async () => {
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue(
        res([makeBook({ id: 'a', title: 'A' }), makeBook({ id: 'z', title: 'Z' })]),
      ),
  );
  const view = mountView();
  await flushPromises();
  await flushPromises();
  expect(view.findAll('.timeline-book.revealed')).toHaveLength(2);
});

it('reveals all and skips the observer under reduced motion', async () => {
  const IO = vi.fn(function (this: Record<string, unknown>) {
    this.observe = vi.fn();
    this.disconnect = vi.fn();
  });
  vi.stubGlobal('IntersectionObserver', IO);
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }));
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(res([makeBook({ id: 'a', title: 'A' })])));
  const view = mountView();
  await flushPromises();
  await flushPromises();
  expect(IO).not.toHaveBeenCalled();
  expect(view.findAll('.timeline-book.revealed')).toHaveLength(1);
});
