import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const readerSource = readFileSync('src/views/ReaderView.vue', 'utf8');

describe('reader privacy presentation', () => {
  it('keeps reading telemetry private while retaining background persistence', () => {
    expect(readerSource).toContain("import { ReadingClock } from '../lib/reading-clock';");
    expect(readerSource).toContain('journal.saveSession');
    expect(readerSource).not.toContain('class="timer-button"');
    expect(readerSource).not.toContain('计时中');
    expect(readerSource).not.toContain('恢复阅读计时');
    expect(readerSource).not.toContain('已保存到本机');
    expect(readerSource).not.toContain('已同步到书房');
    expect(readerSource).not.toContain('第 {{ paragraph + 1 }} 段');
  });
});
