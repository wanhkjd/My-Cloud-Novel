export interface Book {
  id: string;
  title: string;
  author: string;
  description: string;
  encoding: string;
  chapterCount: number;
  volumeCount: number;
  characterCount: number;
  catalogPublished: boolean;
  textPublished: boolean;
  createdAt: number;
  canRead: boolean;
  preface: string;
}
export interface ChapterSummary {
  index: number;
  title: string;
  volume: string;
  characterCount: number;
}
export interface Chapter extends ChapterSummary {
  paragraphs: string[];
}
export interface Progress {
  bookId: string;
  bookTitle: string;
  chapterCount: number;
  chapterIndex: number;
  paragraphIndex: number;
  chapterTitle: string;
  updatedAt: number;
}
export interface ReadingSession {
  id: string;
  bookId: string;
  bookTitle: string;
  chapterIndex: number;
  paragraphIndex: number;
  chapterTitle: string;
  startedAt: number;
  elapsedSeconds: number;
  updatedAt: number;
}
export interface Bookmark {
  id: string;
  bookId: string;
  bookTitle: string;
  chapterIndex: number;
  paragraphIndex: number;
  chapterTitle: string;
  note: string;
  published: boolean;
  createdAt: number;
  updatedAt: number;
}
export interface Stats {
  totalSeconds: number;
  todaySeconds: number;
  sessionCount: number;
  days: { date: string; seconds: number }[];
  books: { bookId: string; title: string; seconds: number }[];
}
