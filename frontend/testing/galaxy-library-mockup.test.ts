import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// 独立静态视觉稿：参考 reactbits <Particles/> 的粒子星野（去掉银河），
// 书籍是明亮可点的星，点击浮出书名与「进入」。jsdom 无法渲染 canvas，
// 这里以静态结构断言守护核心要求。vitest 从 frontend/ 运行，按工作目录解析路径。
const htmlPath = resolve(process.cwd(), 'mockups/galaxy-library.html');

describe('galaxy library UI mockup', () => {
  const html = existsSync(htmlPath) ? readFileSync(htmlPath, 'utf8') : '';

  it('exists as a self-contained HTML file with a particle field canvas', () => {
    expect(existsSync(htmlPath)).toBe(true);
    expect(html).toContain('<!doctype html>');
    expect(html).toContain('<canvas');
    expect(html).toContain('id="field"');
  });

  it('runs a particle starfield (reactbits Particles) with an animation loop', () => {
    expect(html).toContain('requestAnimationFrame');
    expect(html.toLowerCase()).toContain('reactbits');
    expect(html).toContain('prefers-reduced-motion');
  });

  it('drops the galaxy band entirely', () => {
    expect(html).not.toContain('银河');
    expect(html.toLowerCase()).not.toContain('nebula');
  });

  it('renders books as bright stars that reveal a title and an enter link on click', () => {
    expect(html).toContain('const BOOKS');
    expect(html).toContain('山海拾遗'); // 示例书名
    expect(html).toContain('book-pop'); // 点击浮出的卡片
    expect(html).toContain('进入'); // 进入入口
    expect(html).toContain("addEventListener('click'");
    // 旧版封面 / 时间轴布局已移除。
    expect(html).not.toContain('book-cover');
    expect(html).not.toContain('timeline-book');
  });

  it('drops the 云上书房 brand icon and the footer tagline', () => {
    expect(html).not.toContain('云上书房');
    expect(html).not.toContain('A PERSONAL READING ROOM');
    expect(html).not.toContain('让阅读有迹可循');
    expect(html).not.toContain('不辜负每一页');
  });
});
