import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// 独立静态视觉稿：真实星空 + 中央竖直贯穿的动态银河 + 示例书籍卡片。
// jsdom 无法渲染 canvas，这里以静态结构断言守护两条硬性要求：
// 1) 保留背景 canvas、动态银河与示例卡片；2) 去掉「云上书房」图标与底部标语。
// vitest 从 frontend/ 运行，按工作目录解析视觉稿路径。
const htmlPath = resolve(process.cwd(), 'mockups/galaxy-library.html');

describe('galaxy library UI mockup', () => {
  const html = existsSync(htmlPath) ? readFileSync(htmlPath, 'utf8') : '';

  it('exists as a self-contained HTML file', () => {
    expect(existsSync(htmlPath)).toBe(true);
    expect(html).toContain('<!doctype html>');
    expect(html).toContain('<canvas');
    expect(html).toContain('id="sky"');
  });

  it('draws a dynamic, top-to-bottom galaxy band with an animation loop', () => {
    expect(html).toContain('requestAnimationFrame');
    expect(html.toLowerCase()).toContain('nebula');
    // 尊重减少动态偏好：静止用户不跑动画。
    expect(html).toContain('prefers-reduced-motion');
  });

  it('lays out sample book cards along the timeline', () => {
    const cards = html.match(/class="timeline-book/g) ?? [];
    expect(cards.length).toBeGreaterThanOrEqual(5);
    expect(html).toContain('timeline-card');
    expect(html).toContain('timeline-title');
  });

  it('drops the 云上书房 brand icon and the footer tagline', () => {
    expect(html).not.toContain('云上书房');
    expect(html).not.toContain('A PERSONAL READING ROOM');
    expect(html).not.toContain('让阅读有迹可循');
    expect(html).not.toContain('不辜负每一页');
  });
});
