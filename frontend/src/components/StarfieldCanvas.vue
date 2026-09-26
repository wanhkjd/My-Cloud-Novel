<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

const canvas = ref<HTMLCanvasElement>();
let frame = 0;
let stars: { x: number; y: number; z: number; r: number }[] = [];
let onResize: (() => void) | null = null;
let onPointer: ((e: PointerEvent) => void) | null = null;

const reducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
const finePointer = () => window.matchMedia?.('(pointer: fine)').matches ?? false;

onMounted(() => {
  const el = canvas.value;
  const ctx = el?.getContext('2d');
  if (!el || !ctx) return; // jsdom / 无 Canvas 环境：安全退出，渐进增强。
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const parallax = { x: 0, y: 0 }; // 指针相对屏幕中心的偏移（约 -0.5..0.5）。

  const seed = () => {
    const w = window.innerWidth;
    const h = window.innerHeight;
    el.width = Math.floor(w * dpr);
    el.height = Math.floor(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const count = Math.min(220, Math.floor((w * h) / 9000));
    stars = Array.from({ length: count }, () => ({
      x: Math.random() * w,
      y: Math.random() * h,
      z: 0.3 + Math.random() * 0.7,
      r: 0.4 + Math.random() * 1.1,
    }));
  };
  const paint = () => {
    ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
    for (const s of stars) {
      ctx.globalAlpha = 0.35 + s.z * 0.5;
      ctx.fillStyle = '#dfe7ff';
      // 近处（z 大）的星随指针偏移更多，形成纵深视差。
      ctx.fillRect(s.x + parallax.x * s.z * 18, s.y + parallax.y * s.z * 18, s.r, s.r);
    }
    ctx.globalAlpha = 1;
  };

  const tick = () => {
    for (const s of stars) {
      s.y -= s.z * 0.15; // 缓慢上升，呼应向上生长的银河。
      if (s.y < 0) s.y = window.innerHeight;
    }
    paint();
    frame = window.requestAnimationFrame(tick);
  };

  onResize = () => {
    seed();
    paint();
  };
  window.addEventListener('resize', onResize, { passive: true });
  seed();
  paint();
  if (!reducedMotion()) {
    frame = window.requestAnimationFrame(tick);
    if (finePointer()) {
      onPointer = (e) => {
        parallax.x = e.clientX / window.innerWidth - 0.5;
        parallax.y = e.clientY / window.innerHeight - 0.5;
      };
      window.addEventListener('pointermove', onPointer, { passive: true });
    }
  }
});

onBeforeUnmount(() => {
  if (frame) window.cancelAnimationFrame(frame);
  if (onResize) window.removeEventListener('resize', onResize);
  if (onPointer) window.removeEventListener('pointermove', onPointer);
});
</script>

<template>
  <canvas ref="canvas" class="starfield" aria-hidden="true"></canvas>
</template>
