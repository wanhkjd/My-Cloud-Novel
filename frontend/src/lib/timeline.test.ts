import { expect, it } from 'vitest';
import { effectiveTime, orderByTimeline } from './timeline';

const book = (id: string, createdAt: number, timelineDate: string | null = null) => ({
  id,
  createdAt,
  timelineDate,
});

it('prefers the custom timeline date and falls back to createdAt', () => {
  expect(effectiveTime(book('a', 1000, '2020-01-01'))).toBe(Date.parse('2020-01-01'));
  expect(effectiveTime(book('b', 1000, null))).toBe(1000);
  expect(effectiveTime(book('c', 2000, 'not-a-date'))).toBe(2000);
});

it('orders ascending with deterministic tie-breaking', () => {
  const later = book('later', 50, '2021-06-01');
  const earlier = book('earlier', 40, '2019-06-01');
  const tieB = book('b', 30);
  const tieA = book('a', 30);
  expect(orderByTimeline([later, tieB, earlier, tieA]).map((x) => x.id)).toEqual([
    'a',
    'b',
    'earlier',
    'later',
  ]);
});
