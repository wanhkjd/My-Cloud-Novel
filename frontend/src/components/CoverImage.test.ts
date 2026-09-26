import { expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import CoverImage from './CoverImage.vue';

it('skips the network and shows the fallback cover when the book has none', () => {
  const page = mount(CoverImage, {
    props: { id: 'b1', title: '空封面之书', author: '作者', hasCover: false },
  });
  expect(page.find('img.book-cover-image').exists()).toBe(false);
  expect(page.get('.book-cover').text()).toContain('空封面之书');
});

it('requests the real cover then falls back when the image fails to load', async () => {
  const page = mount(CoverImage, {
    props: { id: 'b2', title: '有封面之书', author: '作者', hasCover: true },
  });
  const img = page.get('img.book-cover-image');
  expect(img.attributes('src')).toBe('/api/books/b2/cover');
  await img.trigger('error');
  expect(page.find('img.book-cover-image').exists()).toBe(false);
  expect(page.get('.book-cover').text()).toContain('有封面之书');
});
