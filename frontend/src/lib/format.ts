export function duration(seconds: number) {
  if (seconds < 60) return Math.max(0, Math.floor(seconds)) + ' 秒';
  const minutes = Math.floor(seconds / 60);
  return minutes < 60
    ? minutes + ' 分钟'
    : Math.floor(minutes / 60) + ' 小时 ' + (minutes % 60) + ' 分钟';
}
export const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
export const words = (value: number) =>
  value >= 10000 ? (value / 10000).toFixed(1) + ' 万字' : number(value) + ' 字';
export const dateKey = (ms = Date.now()) => new Date(ms + 8 * 3600000).toISOString().slice(0, 10);
export const dateTime = (ms: number) =>
  new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Shanghai',
  }).format(ms);
export const readingLink = (bookId: string, chapter = 0, paragraph = 0) =>
  '/read/' + encodeURIComponent(bookId) + '/' + chapter + '?p=' + paragraph;
