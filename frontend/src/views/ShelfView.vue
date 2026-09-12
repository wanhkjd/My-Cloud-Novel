<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ArrowUpRight, BookOpen, Search } from 'lucide-vue-next';
import { request, errorMessage } from '../lib/api';
import { auth } from '../lib/auth';
import { createJournal } from '../lib/journal';
import { number, readingLink, words } from '../lib/format';
import type { Book, Progress } from '../lib/types';
import BookCover from '../components/BookCover.vue';
const books = ref<Book[]>([]);
const recent = ref<Progress | null>(null);
const query = ref('');
const filter = ref('all');
const loading = ref(true);
const error = ref('');
const visible = computed(() =>
  books.value.filter(
    (b) =>
      (!query.value ||
        (b.title + ' ' + b.author).toLowerCase().includes(query.value.trim().toLowerCase())) &&
      (filter.value !== 'readable' || b.canRead),
  ),
);
async function load() {
  loading.value = true;
  error.value = '';
  try {
    books.value = await request<Book[]>('/api/books');
    const progress = await createJournal(auth.authenticated).listProgress();
    recent.value =
      progress.find((p) => books.value.some((b) => b.id === p.bookId && b.canRead)) ?? null;
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page shelf-page">
    <section class="shelf-intro">
      <div>
        <p class="eyebrow">一本书，一段时光</p>
        <h1>书，和读书的日子。</h1>
        <p class="intro-copy">
          在这里收藏故事，也留住阅读时的片刻心绪。<br />不必匆忙，翻开一本，慢慢读。
        </p>
      </div>
      <div class="intro-aside">
        <span class="vertical-note">闲 来 一 页 书</span><span class="small-seal">书房</span>
      </div>
    </section>
    <div v-if="auth.authenticated" class="notice subtle">
      <BookOpen :size="16" /> 主人视角 · 你可以看到私有草稿，访客只会看到已公开的书目。<RouterLink
        to="/admin"
        >管理书房 →</RouterLink
      >
    </div>
    <RouterLink
      v-if="recent"
      class="continue-strip"
      :to="readingLink(recent.bookId, recent.chapterIndex, recent.paragraphIndex)"
    >
      <BookOpen :size="23" />
      <div>
        <span class="eyebrow">接着上次的故事</span><strong>{{ recent.bookTitle }}</strong
        ><span class="muted">{{ recent.chapterTitle }}</span>
      </div>
      <span class="continue-action">继续阅读 <ArrowUpRight :size="18" /></span>
    </RouterLink>
    <section aria-labelledby="shelf-heading" class="shelf-section">
      <div class="section-top">
        <div class="heading-inline">
          <h2 id="shelf-heading">我的书架</h2>
          <span class="count-label">{{ books.length }} 部收藏</span>
        </div>
        <label class="search-box"
          ><Search :size="18" /><input
            v-model="query"
            type="search"
            aria-label="搜索书名或作者"
            placeholder="找一本书，或一位作者"
        /></label>
      </div>
      <div class="filter-row">
        <button :class="{ active: filter === 'all' }" @click="filter = 'all'">全部藏书</button
        ><button :class="{ active: filter === 'readable' }" @click="filter = 'readable'">
          可以阅读</button
        ><span class="muted">{{
          auth.authenticated ? '包括未公开的私有收藏' : '向你开放的个人书架'
        }}</span>
      </div>
      <div v-if="error" class="notice error" role="alert">
        {{ error }}<button @click="load">重试</button>
      </div>
      <div v-else-if="loading" class="empty-state" role="status">正在整理书架…</div>
      <div v-else-if="!books.length" class="empty-state">
        <BookOpen :size="36" :stroke-width="1.2" />
        <h3>书架还在整理中</h3>
        <p>
          {{
            auth.authenticated
              ? '上传第一本 TXT 小说，让书房的故事从这里开始。'
              : '书房主人尚未公开书目，欢迎下次再来坐坐。'
          }}
        </p>
        <RouterLink v-if="auth.authenticated" class="button primary" to="/admin"
          >上传第一本小说</RouterLink
        >
      </div>
      <div v-else-if="!visible.length" class="empty-state">
        <h3>没有找到这本书</h3>
        <p>换一个书名或作者试试。</p>
        <button
          @click="
            query = '';
            filter = 'all';
          "
        >
          清除筛选
        </button>
      </div>
      <div v-else class="book-grid">
        <RouterLink
          v-for="book in visible"
          :key="book.id"
          class="book-card"
          :to="'/books/' + book.id"
        >
          <BookCover :title="book.title" :author="book.author" small />
          <div class="book-card-copy">
            <span class="book-status" :class="{ private: !book.catalogPublished }">{{
              !book.catalogPublished ? '私有草稿' : book.textPublished ? '开放阅读' : '仅展示书目'
            }}</span>
            <h3>{{ book.title }}</h3>
            <p>{{ book.author }}</p>
            <p class="book-description">
              {{ book.description || '每一次翻页，都是与故事的重逢。' }}
            </p>
            <span class="book-meta"
              >{{ number(book.chapterCount) }} 章 · {{ words(book.characterCount) }}</span
            ><span class="book-card-link">翻开这本书 <ArrowUpRight :size="16" /></span>
          </div>
        </RouterLink>
      </div>
    </section>
    <div class="shelf-bottom">
      <span>关于这间书房</span>
      <p>
        这是一座个人云书库。公开的书目与感想与你分享；<br />你的阅读足迹只保存在自己的浏览器，不会写进主人的日记。
      </p>
    </div>
  </div>
</template>
