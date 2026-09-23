import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const bookSource = readFileSync('src/views/BookView.vue', 'utf8');
const styleSource = readFileSync('src/style.css', 'utf8');

describe('public book presentation', () => {
  it('keeps file implementation details out of the public book page', () => {
    expect(bookSource).not.toContain('book.encoding');
    expect(bookSource).not.toContain('下载 TXT');
    expect(bookSource).not.toContain('Download,');
  });

  it('keeps the mobile logout action available', () => {
    expect(styleSource).not.toContain('.site-header nav .icon-button {\n    display: none;');
  });
});
