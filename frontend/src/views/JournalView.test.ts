import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const journalSource = readFileSync('src/views/JournalView.vue', 'utf8');

describe('journal privacy presentation', () => {
  it('presents reading notes without exposing reading telemetry', () => {
    expect(journalSource).toContain('阅读札记');
    expect(journalSource).toContain('书签与感想');
    expect(journalSource).not.toContain('累计阅读');
    expect(journalSource).not.toContain('今日阅读');
    expect(journalSource).not.toContain('阅读时长');
    expect(journalSource).not.toContain('有效前台阅读估算');
    expect(journalSource).not.toContain('每段最多 30 分钟');
    expect(journalSource).not.toContain('duration(');
    expect(journalSource).not.toContain('stats');
  });
});
