<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowLeft, BookOpen, LockKeyhole, Search } from 'lucide-vue-next';
import { errorMessage, request } from '../lib/api';
import { auth } from '../lib/auth';
import { createJournal } from '../lib/journal';
import { number, readingLink, words, dateTime } from '../lib/format';
import type { Book, Bookmark, ChapterSummary, Progress } from '../lib/types';
import BookCover from '../components/BookCover.vue';
const route = useRoute();
const book = ref<Book>();
const chapters = ref<ChapterSummary[]>([]);
const notes = ref<Bookmark[]>([]);
const progress = ref<Progress | null>(null);
const error = ref('');
const loading = ref(true);
const query = ref('');
const page = ref(0);
const tab = ref('chapters');
const filtered = computed(() =>
  chapters.value.filter((c) => c.title.includes(query.value) || c.volume.includes(query.value)),
);
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / 100)));
const shown = computed(() => filtered.value.slice(page.value * 100, (page.value + 1) * 100));
const continueUrl = computed(() =>
  book.value
    ? readingLink(book.value.id, progress.value?.chapterIndex, progress.value?.paragraphIndex)
    : '/',
);
watch(query, () => (page.value = 0));
async function load() {
  loading.value = true;
  error.value = '';
  try {
    const id = encodeURIComponent(String(route.params.id));
    book.value = await request<Book>('/api/books/' + id);
    document.title = book.value.title + ' · 云上书房';
    if (book.value.canRead)
      [chapters.value, notes.value, progress.value] = await Promise.all([
        request<ChapterSummary[]>('/api/books/' + id + '/chapters'),
        request<Bookmark[]>('/api/books/' + id + '/bookmarks'),
        createJournal(auth.authenticated).progress(book.value.id),
      ]);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page">
    <RouterLink to="/" class="back-link"><ArrowLeft :size="16" /> 返回书架</RouterLink>
    <div v-if="error" class="notice error" role="alert">
      {{ error }}<button @click="load">重试</button>
    </div>
    <div v-else-if="loading" class="empty-state" role="status">正在翻开这本书…</div>
    <template v-else-if="book">
      <section class="book-detail">
        <BookCover :title="book.title" :author="book.author" />
        <div class="book-detail-copy">
          <p class="eyebrow">
            {{
              !book.catalogPublished
                ? '仅主人可见 · 私有藏书'
                : book.textPublished
                  ? '书架里的故事 · 开放阅读'
                  : '书架里的故事 · 仅展示书目'
            }}
          </p>
          <h1>{{ book.title }}</h1>
          <p class="author">{{ book.author }} <span>著</span></p>
          <div class="detail-facts">
            <span>{{ number(book.chapterCount) }} 章</span
            ><span>{{ words(book.characterCount) }}</span
            ><span>{{ book.volumeCount }} 卷</span>
          </div>
          <p class="detail-description">
            {{ book.description || '这本书还没有简介。读到有感触的地方，不妨留下一枚书签。' }}
          </p>
          <div class="actions">
            <RouterLink v-if="book.canRead" class="button primary" :to="continueUrl"
              ><BookOpen :size="17" /> {{ progress ? '继续阅读' : '开始阅读' }}</RouterLink
            ><span v-else class="button disabled"><LockKeyhole :size="16" /> 正文暂未公开</span
            ><RouterLink v-if="auth.authenticated" class="text-link" to="/admin"
              >编辑书籍 →</RouterLink
            >
          </div>
          <p v-if="progress" class="muted small">
            上次读到：{{ progress.chapterTitle }} · 第 {{ progress.paragraphIndex + 1 }} 段
          </p>
        </div>
      </section>
      <div v-if="!book.canRead" class="notice">
        <LockKeyhole :size="18" />
        <div>这里只展示书籍信息。正文、原始文件与关联书签尚未向访客开放。</div>
      </div>
      <section v-else class="detail-content">
        <div class="tabs">
          <button :class="{ active: tab === 'chapters' }" @click="tab = 'chapters'">
            章节目录 <span>{{ number(chapters.length) }}</span></button
          ><button :class="{ active: tab === 'notes' }" @click="tab = 'notes'">
            {{ auth.authenticated ? '书签与感想' : '主人的公开感想' }}
            <span>{{ notes.length }}</span>
          </button>
        </div>
        <template v-if="tab === 'chapters'"
          ><div class="section-top">
            <p class="muted">从一个章节开始，走进另一个世界。</p>
            <label class="search-box"
              ><Search :size="17" /><input
                v-model="query"
                type="search"
                aria-label="查找章节"
                placeholder="查找章节名称"
            /></label>
          </div>
          <div class="chapter-grid">
            <RouterLink
              v-for="chapter in shown"
              :key="chapter.index"
              :to="readingLink(book.id, chapter.index)"
              ><span class="chapter-number">{{ String(chapter.index + 1).padStart(2, '0') }}</span
              ><span
                >{{ chapter.title }}<small v-if="chapter.volume">{{ chapter.volume }}</small></span
              ></RouterLink
            >
          </div>
          <p v-if="!shown.length" class="empty-state">没有匹配的章节。</p>
          <div v-if="pageCount > 1" class="pagination">
            <button :disabled="page === 0" @click="page--">上一页</button
            ><span>{{ page + 1 }} / {{ pageCount }}</span
            ><button :disabled="page + 1 >= pageCount" @click="page++">下一页</button>
          </div>
          <details v-if="book.preface" class="preface">
            <summary>查看文件前言 / 卷前文字</summary>
            <pre>{{ book.preface }}</pre>
          </details>
        </template>
        <template v-else
          ><div v-if="!notes.length" class="empty-state">
            <h3>还没有留下感想</h3>
            <p>
              {{
                auth.authenticated
                  ? '在阅读器中点击段落旁的书签，就能记录此刻的想法。'
                  : '书房主人还没有公开这本书的阅读感想。'
              }}
            </p>
          </div>
          <article v-for="note in notes" :key="note.id" class="note-card">
            <div class="section-top">
              <RouterLink :to="readingLink(book.id, note.chapterIndex, note.paragraphIndex)"
                >{{ note.chapterTitle }} · 第 {{ note.paragraphIndex + 1 }} 段</RouterLink
              ><span class="tag">{{ note.published ? '公开感想' : '私密书签' }}</span>
            </div>
            <p class="note-text">{{ note.note || '留住了这一页。' }}</p>
            <span class="muted small">{{ dateTime(note.updatedAt) }}</span>
          </article></template
        >
      </section>
    </template>
  </div>
</template>
