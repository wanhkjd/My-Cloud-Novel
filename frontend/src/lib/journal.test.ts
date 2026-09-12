import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createJournal, GUEST_KEY } from './journal';
import type { Progress, ReadingSession } from './types';

const progress: Progress = {
  bookId: 'b1',
  bookTitle: '原创',
  chapterCount: 2,
  chapterIndex: 1,
  paragraphIndex: 0,
  chapterTitle: '回信',
  updatedAt: 1,
};
const session: ReadingSession = {
  id: 's1',
  bookId: 'b1',
  bookTitle: '原创',
  chapterIndex: 1,
  paragraphIndex: 0,
  chapterTitle: '回信',
  startedAt: Date.now() - 120000,
  elapsedSeconds: 60,
  updatedAt: 1,
};
beforeEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
});
describe('guest journal isolation and durability', () => {
  it('restores progress and only counts the largest cumulative session once', async () => {
    const journal = createJournal(false);
    await journal.saveProgress(progress);
    for (const seconds of [60, 60, 90, 30])
      await journal.saveSession({ ...session, elapsedSeconds: seconds });
    const reloaded = createJournal(false);
    expect(await reloaded.progress('b1')).toEqual(progress);
    expect((await reloaded.stats()).totalSeconds).toBe(90);
    expect((await reloaded.history()).length).toBe(1);
  });
  it('recovers safely from malformed or wrongly shaped browser data', async () => {
    for (const broken of [
      '{',
      'null',
      '{"version":1,"progress":null}',
      '{"version":1,"progress":[{}],"sessions":[null],"bookmarks":[false]}',
    ]) {
      localStorage.setItem(GUEST_KEY, broken);
      expect(await createJournal(false).listProgress()).toEqual([]);
      expect((await createJournal(false).stats()).totalSeconds).toBe(0);
    }
  });
  it('never falls back to guest storage when administrator sync fails', async () => {
    await createJournal(false).saveProgress(progress);
    const before = localStorage.getItem(GUEST_KEY);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"message":"未登录"}', {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    await expect(
      createJournal(true).saveProgress({ ...progress, paragraphIndex: 1 }),
    ).rejects.toThrow();
    expect(localStorage.getItem(GUEST_KEY)).toBe(before);
  });
});
