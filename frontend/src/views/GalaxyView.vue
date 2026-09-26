<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Book } from '../lib/types';
import { errorMessage, request } from '../lib/api';
import { orderByTimeline } from '../lib/timeline';
import StarfieldCanvas from '../components/StarfieldCanvas.vue';
import GalaxyVine from '../components/GalaxyVine.vue';
import TimelineBook from '../components/TimelineBook.vue';

const books = ref<Book[]>([]);
const status = ref<'loading' | 'ready' | 'error'>('loading');
const error = ref('');
const revealed = ref(new Set<string>());
const progress = ref(0);

// 升序（旧→新）；倒序渲染让最早的书落在藤蔓根部（页面底部）。
const ordered = computed(() => orderByTimeline(books.value));
const rendered = computed(() => [...ordered.value].reverse());
// 节点自底向上归一化：最早→0、最新→1。
const nodes = computed(() => {
  const n = ordered.value.length;
  return ordered.value.map((_, i) => (n <= 1 ? 0 : i / (n - 1)));
});

const reducedMotion = () =>
  window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;

let observer: IntersectionObserver | null = null;
let onScroll: (() => void) | null = null;

function updateProgress() {
  const max = document.documentElement.scrollHeight - window.innerHeight;
  // 底部（scrollY=max，最早）生长为 0；向上滚动趋近 1。
  progress.value = max <= 0 ? 1 : 1 - window.scrollY / max;
}

function revealAll() {
  revealed.value = new Set(books.value.map((b) => b.id));
}
onMounted(async () => {
  try {
    books.value = await request<Book[]>('/api/books');
    status.value = 'ready';
  } catch (e) {
    error.value = errorMessage(e);
    status.value = 'error';
    return;
  }
  document.body.classList.add('galaxy-route');
  await nextTick();
  window.scrollTo(0, document.documentElement.scrollHeight); // 初始定位到底部（最早）。
  updateProgress();

  const reduce = reducedMotion();
  if (reduce || !('IntersectionObserver' in window)) {
    revealAll(); // 降级 / 无观察者：渐进增强，全部可见。
  } else {
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const id = (entry.target as HTMLElement).dataset.book;
          if (id) revealed.value = new Set(revealed.value).add(id);
          observer?.unobserve(entry.target);
        }
      },
      { threshold: 0.2 },
    );
    for (const el of document.querySelectorAll<HTMLElement>('.timeline-book')) observer.observe(el);
  }

  if (reduce) {
    progress.value = 1; // 静态：藤蔓画满，不装滚动监听。
    return;
  }
  onScroll = () => window.requestAnimationFrame(updateProgress);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll, { passive: true });
});

onBeforeUnmount(() => {
  observer?.disconnect();
  if (onScroll) {
    window.removeEventListener('scroll', onScroll);
    window.removeEventListener('resize', onScroll);
  }
  document.body.classList.remove('galaxy-route');
});
</script>

<template>
  <div class="galaxy-view">
    <StarfieldCanvas />
    <p v-if="status === 'loading'" class="galaxy-notice" role="status">正在点亮星河…</p>
    <p v-else-if="status === 'error'" class="galaxy-notice galaxy-error" role="alert">
      {{ error }}
    </p>
    <p v-else-if="ordered.length === 0" class="galaxy-notice">星河尚在孕育，还没有公开的书。</p>
    <div v-else class="galaxy-timeline">
      <GalaxyVine :progress="progress" :nodes="nodes" />
      <ol class="timeline-list">
        <TimelineBook
          v-for="(book, i) in rendered"
          :key="book.id"
          :book="book"
          :side="i % 2 === 0 ? 'right' : 'left'"
          :revealed="revealed.has(book.id)"
          :data-book="book.id"
        />
      </ol>
    </div>
  </div>
</template>
