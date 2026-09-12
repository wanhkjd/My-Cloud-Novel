import { afterEach, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import ShelfView from './ShelfView.vue';
afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});
it('shows an honest empty public library rather than fake books or statistics', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('[]', { status: 200 })));
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: ShelfView }],
  });
  await router.push('/');
  await router.isReady();
  const page = mount(ShelfView, { global: { plugins: [router] } });
  await flushPromises();
  expect(page.text()).toContain('书架还在整理中');
  expect(page.findAll('.book-card')).toHaveLength(0);
  expect(page.text()).not.toContain('人道至尊');
  page.unmount();
});
