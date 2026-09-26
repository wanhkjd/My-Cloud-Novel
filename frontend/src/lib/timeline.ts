import type { Book } from './types';

type Dated = Pick<Book, 'timelineDate' | 'createdAt'>;

/** 时间轴有效时刻：优先自定义日期（ISO yyyy-MM-dd），否则/非法则回退导入时间。 */
export function effectiveTime(book: Dated): number {
  const parsed = book.timelineDate ? Date.parse(book.timelineDate) : Number.NaN;
  return Number.isNaN(parsed) ? book.createdAt : parsed;
}

/** 升序（旧→新）稳定排序；同刻按 createdAt、再按 id 决胜，保证渲染确定。 */
export function orderByTimeline<T extends Dated & Pick<Book, 'id'>>(books: readonly T[]): T[] {
  return [...books].sort((a, b) => {
    const byTime = effectiveTime(a) - effectiveTime(b);
    if (byTime !== 0) return byTime;
    const byCreated = a.createdAt - b.createdAt;
    return byCreated !== 0 ? byCreated : a.id.localeCompare(b.id);
  });
}
