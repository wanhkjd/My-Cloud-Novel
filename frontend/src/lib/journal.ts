import { jsonRequest, request } from './api';
import { dateKey } from './format';
import type { Bookmark, Progress, ReadingSession, Stats } from './types';

export const GUEST_KEY = 'cloud-novel:guest:v1';
interface State {
  version: 1;
  progress: Progress[];
  sessions: ReadingSession[];
  bookmarks: Bookmark[];
}
export interface Journal {
  progress(book: string): Promise<Progress | null>;
  listProgress(): Promise<Progress[]>;
  saveProgress(progress: Progress, keepalive?: boolean): Promise<Progress>;
  saveSession(session: ReadingSession, keepalive?: boolean): Promise<ReadingSession>;
  history(): Promise<ReadingSession[]>;
  bookmarks(book?: string): Promise<Bookmark[]>;
  saveBookmark(bookmark: Bookmark): Promise<Bookmark>;
  deleteBookmark(id: string): Promise<void>;
  stats(): Promise<Stats>;
}
const empty = (): State => ({ version: 1, progress: [], sessions: [], bookmarks: [] });
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object';
const nonnegative = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0;
const position = (v: unknown): v is Record<string, unknown> =>
  object(v) &&
  typeof v.bookId === 'string' &&
  typeof v.bookTitle === 'string' &&
  typeof v.chapterTitle === 'string' &&
  nonnegative(v.chapterIndex) &&
  nonnegative(v.paragraphIndex) &&
  nonnegative(v.updatedAt);
const isProgress = (v: unknown): v is Progress => position(v) && nonnegative(v.chapterCount);
const isSession = (v: unknown): v is ReadingSession =>
  position(v) &&
  typeof v.id === 'string' &&
  nonnegative(v.startedAt) &&
  nonnegative(v.elapsedSeconds) &&
  Number(v.elapsedSeconds) <= 1800;
const isBookmark = (v: unknown): v is Bookmark =>
  position(v) &&
  typeof v.id === 'string' &&
  typeof v.note === 'string' &&
  typeof v.published === 'boolean' &&
  nonnegative(v.createdAt);
function load(): State {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(GUEST_KEY) ?? 'null');
    if (!object(value) || value.version !== 1) return empty();
    return {
      version: 1,
      progress: Array.isArray(value.progress) ? value.progress.filter(isProgress) : [],
      sessions: Array.isArray(value.sessions) ? value.sessions.filter(isSession) : [],
      bookmarks: Array.isArray(value.bookmarks)
        ? value.bookmarks.filter(isBookmark).map((b) => ({ ...b, published: false }))
        : [],
    };
  } catch {
    return empty();
  }
}
function save(state: State) {
  try {
    localStorage.setItem(GUEST_KEY, JSON.stringify(state));
  } catch {
    throw new Error('浏览器存储不可用或空间不足，阅读记录尚未保存。请检查隐私设置。');
  }
}
class GuestJournal implements Journal {
  async progress(book: string) {
    return load().progress.find((p) => p.bookId === book) ?? null;
  }
  async listProgress() {
    return load().progress.sort((a, b) => b.updatedAt - a.updatedAt);
  }
  async saveProgress(progress: Progress) {
    const state = load();
    state.progress = [progress, ...state.progress.filter((p) => p.bookId !== progress.bookId)];
    save(state);
    return progress;
  }
  async saveSession(session: ReadingSession) {
    const state = load();
    const previous = state.sessions.find((s) => s.id === session.id);
    if (previous && previous.elapsedSeconds >= session.elapsedSeconds) return previous;
    state.sessions = [session, ...state.sessions.filter((s) => s.id !== session.id)];
    save(state);
    return session;
  }
  async history() {
    return load()
      .sessions.sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, 200);
  }
  async bookmarks(book?: string) {
    return load()
      .bookmarks.filter((b) => !book || b.bookId === book)
      .sort((a, b) => b.updatedAt - a.updatedAt);
  }
  async saveBookmark(bookmark: Bookmark) {
    const state = load();
    const samePosition = state.bookmarks.find(
      (b) =>
        b.bookId === bookmark.bookId &&
        b.chapterIndex === bookmark.chapterIndex &&
        b.paragraphIndex === bookmark.paragraphIndex,
    );
    const result = {
      ...bookmark,
      id: bookmark.id || samePosition?.id || crypto.randomUUID(),
      published: false,
    };
    state.bookmarks = [result, ...state.bookmarks.filter((b) => b.id !== result.id)];
    save(state);
    return result;
  }
  async deleteBookmark(id: string) {
    const state = load();
    state.bookmarks = state.bookmarks.filter((b) => b.id !== id);
    save(state);
  }
  async stats(): Promise<Stats> {
    const sessions = load().sessions;
    const days = Array.from({ length: 14 }, (_, i) => {
      const date = dateKey(Date.now() - (13 - i) * 86400000);
      return {
        date,
        seconds: sessions
          .filter((s) => dateKey(s.startedAt) === date)
          .reduce((sum, s) => sum + s.elapsedSeconds, 0),
      };
    });
    const byBook = new Map<string, { bookId: string; title: string; seconds: number }>();
    sessions.forEach((s) => {
      const entry = byBook.get(s.bookId) ?? { bookId: s.bookId, title: s.bookTitle, seconds: 0 };
      entry.seconds += s.elapsedSeconds;
      byBook.set(s.bookId, entry);
    });
    return {
      totalSeconds: sessions.reduce((sum, s) => sum + s.elapsedSeconds, 0),
      todaySeconds: days.at(-1)!.seconds,
      sessionCount: sessions.length,
      days,
      books: [...byBook.values()].sort((a, b) => b.seconds - a.seconds),
    };
  }
}
class OwnerJournal implements Journal {
  progress(book: string) {
    return request<Progress | null>('/api/me/progress/' + encodeURIComponent(book));
  }
  listProgress() {
    return request<Progress[]>('/api/me/progress');
  }
  saveProgress(progress: Progress, keepalive = false) {
    return jsonRequest<Progress>(
      '/api/me/progress/' + encodeURIComponent(progress.bookId),
      'PUT',
      progress,
      keepalive,
    );
  }
  saveSession(session: ReadingSession, keepalive = false) {
    return jsonRequest<ReadingSession>(
      '/api/me/sessions/' + encodeURIComponent(session.id),
      'PUT',
      session,
      keepalive,
    );
  }
  history() {
    return request<ReadingSession[]>('/api/me/history');
  }
  async bookmarks(book?: string) {
    return (await request<Bookmark[]>('/api/me/bookmarks')).filter(
      (b) => !book || b.bookId === book,
    );
  }
  saveBookmark(bookmark: Bookmark) {
    return jsonRequest<Bookmark>(
      '/api/me/bookmarks' + (bookmark.id ? '/' + encodeURIComponent(bookmark.id) : ''),
      bookmark.id ? 'PATCH' : 'POST',
      bookmark,
    );
  }
  deleteBookmark(id: string) {
    return jsonRequest<void>('/api/me/bookmarks/' + encodeURIComponent(id), 'DELETE');
  }
  stats() {
    return request<Stats>('/api/me/stats');
  }
}
/** Scope is captured for the lifetime of a page; failed owner requests never touch guest data. */
export function createJournal(owner: boolean): Journal {
  return owner ? new OwnerJournal() : new GuestJournal();
}
