import { RouterLinkStub, mount } from '@vue/test-utils';
import { expect, it } from 'vitest';
import TimelineBook from './TimelineBook.vue';
import CoverImage from './CoverImage.vue';
import type { Book } from '../lib/types';

const book: Book = {
  id: 'b7',
  title: '夜航',
  author: '林澈',
  description: '一段向上的旅程',
  encoding: 'UTF-8',
  chapterCount: 12,
  volumeCount: 1,
  characterCount: 42000,
  catalogPublished: true,
  textPublished: true,
  createdAt: 1700000000000,
  canRead: true,
  preface: '',
  timelineDate: '2020-03-01',
  hasCover: true,
};

const mountNode = (over: Partial<{ side: 'left' | 'right'; revealed: boolean }> = {}) =>
  mount(TimelineBook, {
    props: { book, side: 'right', revealed: false, ...over },
    global: { stubs: { RouterLink: RouterLinkStub } },
  });

it('links to the book and shows its title, author and timeline date', () => {
  const node = mountNode();
  expect(node.findComponent(RouterLinkStub).props('to')).toBe('/books/b7');
  expect(node.text()).toContain('夜航');
  expect(node.text()).toContain('林澈');
  expect(node.text()).toContain('2020');
});

it('forwards cover state and reflects side and reveal flags', () => {
  const node = mountNode({ side: 'left', revealed: true });
  expect(node.get('.timeline-book').classes()).toEqual(
    expect.arrayContaining(['left', 'revealed']),
  );
  expect(node.findComponent(CoverImage).props('hasCover')).toBe(true);
});
