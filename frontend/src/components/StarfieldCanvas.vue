<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

const canvas = ref<HTMLCanvasElement>();
let frame = 0;
let stars: { x: number; y: number; z: number; r: number }[] = [];
let onResize: (() => void) | null = null;

const reducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

onMounted(() => {
  const el = canvas.value;
  const ctx = el?.getContext('2d');
  if (!el || !ctx) return; // jsdom / 无 Canvas 环境：安全退出，渐进增强。
  const dpr = Math.min(window.devicePixelRatio || 1, 2);

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
      ctx.fillRect(s.x, s.y, s.r, s.r);
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
  if (!reducedMotion()) frame = window.requestAnimationFrame(tick);
});

onBeforeUnmount(() => {
  if (frame) window.cancelAnimationFrame(frame);
  if (onResize) window.removeEventListener('resize', onResize);
});
</script>

<template>
  <canvas ref="canvas" class="starfield" aria-hidden="true"></canvas>
</template>
