<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import {
  ArrowLeft,
  ChevronLeft,
  ChevronRight,
  List,
  Moon,
  Sun,
  Minus,
  Plus,
  Bookmark as BookmarkIcon,
  X,
  Search,
} from 'lucide-vue-next';
import { errorMessage, request } from '../lib/api';
import { auth } from '../lib/auth';
import { createJournal } from '../lib/journal';
import { ReadingClock } from '../lib/reading-clock';
import { dateKey, readingLink } from '../lib/format';
import type {
  Book,
  Bookmark,
  Chapter,
  ChapterSummary,
  Progress,
  ReadingSession,
} from '../lib/types';
import BookmarkEditor from '../components/BookmarkEditor.vue';
const route = useRoute();
const router = useRouter();
const owner = auth.authenticated;
const journal = createJournal(owner);
const book = ref<Book>();
const chapters = ref<ChapterSummary[]>([]);
const chapter = ref<Chapter>();
const paragraph = ref(0);
const error = ref('');
const loading = ref(true);
const syncError = ref('');
const font = ref(20);
const theme = ref('paper');
const bookmarks = ref<Bookmark[]>([]);
const selected = ref<Bookmark | null>(null);
const noteError = ref('');
const noteBusy = ref(false);
const toc = ref<HTMLDialogElement>();
const tocOpen = ref(false);
const tocTab = ref('chapters');
const query = ref('');
const tocPage = ref(0);
const filtered = computed(() =>
  chapters.value.filter((c) => c.title.includes(query.value) || c.volume.includes(query.value)),
);
const tocPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / 100)));
const tocChapters = computed(() =>
  filtered.value.slice(tocPage.value * 100, (tocPage.value + 1) * 100),
);
const progressPercent = computed(() =>
  book.value && chapter.value
    ? Math.round(((chapter.value.index + 1) / book.value.chapterCount) * 100)
    : 0,
);
let clock = new ReadingClock(performance.now());
let segment = { id: crypto.randomUUID(), startedAt: Date.now(), base: 0 };
let queue: Promise<boolean> = Promise.resolve(true);
const pending = new Map<string, ReadingSession>();
let generation = 0;
let restoring = false;
let frame = 0;
let tickTimer = 0;
let saveTimer = 0;
let disposed = false;
watch(query, () => (tocPage.value = 0));
try {
  const settings = JSON.parse(localStorage.getItem('cloud-novel:reader-settings') ?? 'null');
  if (settings) {
    font.value = Math.min(28, Math.max(16, Number(settings.font) || 20));
    theme.value = settings.theme === 'night' ? 'night' : 'paper';
  }
} catch {
  /* Defaults work even when browser storage is unavailable. */
}
watch([font, theme], () => {
  try {
    localStorage.setItem(
      'cloud-novel:reader-settings',
      JSON.stringify({ font: font.value, theme: theme.value }),
    );
  } catch {
    /* Reading still works without preference persistence. */
  }
});
function sample() {
  const active =
    !loading.value &&
    !!chapter.value &&
    !selected.value &&
    !tocOpen.value &&
    document.visibilityState === 'visible' &&
    document.hasFocus();
  clock.tick(performance.now(), active);
}
function interact() {
  sample();
  clock.touch(performance.now());
  sample();
}
function markPosition() {
  if (restoring || loading.value || !chapter.value) return;
  const nodes = document.querySelectorAll<HTMLElement>('.reader-paragraph');
  let current = 0;
  for (const node of nodes) {
    if (node.getBoundingClientRect().top <= 150) current = Number(node.dataset.paragraph);
    else break;
  }
  paragraph.value = current;
}
function onScroll() {
  interact();
  if (!frame)
    frame = requestAnimationFrame(() => {
      frame = 0;
      markPosition();
    });
}
async function scrollToParagraph(index: number) {
  restoring = true;
  await nextTick();
  paragraph.value = Math.min(
    Math.max(0, index),
    Math.max(0, (chapter.value?.paragraphs.length ?? 1) - 1),
  );
  if (paragraph.value === 0) window.scrollTo({ top: 0, behavior: 'instant' });
  else
    document
      .getElementById('paragraph-' + paragraph.value)
      ?.scrollIntoView({ block: 'start', behavior: 'instant' });
  await new Promise<void>((resolve) =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  );
  restoring = false;
}
function flush(keepalive = false): Promise<boolean> {
  sample();
  if (!book.value || !chapter.value || loading.value) return queue;
  const now = Date.now();
  const progress: Progress = {
    bookId: book.value.id,
    bookTitle: book.value.title,
    chapterCount: book.value.chapterCount,
    chapterIndex: chapter.value.index,
    paragraphIndex: paragraph.value,
    chapterTitle: chapter.value.title,
    updatedAt: now,
  };
  const elapsed = clock.seconds - segment.base;
  if (elapsed > 0) {
    pending.set(segment.id, {
      ...progress,
      id: segment.id,
      startedAt: segment.startedAt,
      elapsedSeconds: Math.min(1800, elapsed),
    });
    if (elapsed >= 1800 || dateKey(now) !== dateKey(segment.startedAt)) {
      segment = { id: crypto.randomUUID(), startedAt: now, base: clock.seconds };
    }
  }
  queue = queue
    .catch(() => false)
    .then(async () => {
      try {
        await journal.saveProgress(progress, keepalive);
        for (const item of [...pending.values()]) {
          await journal.saveSession(item, keepalive);
          if ((pending.get(item.id)?.elapsedSeconds ?? 0) <= item.elapsedSeconds)
            pending.delete(item.id);
        }
        syncError.value = '';
        return true;
      } catch (e) {
        syncError.value = errorMessage(e);
        return false;
      }
    });
  return queue;
}
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = '';
  chapter.value = undefined;
  sample();
  try {
    const id = String(route.params.id);
    const index = Number(route.params.chapter);
    if (!Number.isInteger(index) || index < 0) throw new Error('章节编号不正确。');
    if (!book.value) {
      book.value = await request<Book>('/api/books/' + encodeURIComponent(id));
      if (!book.value.canRead) throw new Error('这本书的正文尚未公开。');
      chapters.value = await request<ChapterSummary[]>(
        '/api/books/' + encodeURIComponent(id) + '/chapters',
      );
    }
    const [content, notes, saved] = await Promise.all([
      request<Chapter>('/api/books/' + encodeURIComponent(id) + '/chapters/' + index),
      journal.bookmarks(id),
      journal.progress(id),
    ]);
    if (current !== generation || disposed) return;
    chapter.value = content;
    bookmarks.value = notes;
    clock = new ReadingClock(performance.now());
    segment = { id: crypto.randomUUID(), startedAt: Date.now(), base: 0 };
    loading.value = false;
    const requested =
      route.query.p === undefined
        ? saved?.chapterIndex === index
          ? saved.paragraphIndex
          : 0
        : Number(route.query.p);
    await scrollToParagraph(Number.isInteger(requested) ? requested : 0);
    if (current !== generation || disposed) return;
    document.title = content.title + ' · ' + book.value.title;
    interact();
    void flush();
  } catch (e) {
    if (current === generation) {
      error.value = errorMessage(e);
      loading.value = false;
    }
  }
}
watch(() => route.fullPath, load, { immediate: true });
async function beforeNavigate() {
  const saved = await flush();
  if (!saved && !confirm('阅读记录尚未保存成功，仍要离开吗？离开后可能丢失未同步的时间与位置。'))
    return false;
  loading.value = true;
  sample();
  return true;
}
onBeforeRouteLeave(beforeNavigate);
onBeforeRouteUpdate(beforeNavigate);
async function go(index: number, p = 0) {
  if (!book.value || index < 0 || index >= book.value.chapterCount || loading.value) return;
  toc.value?.close();
  const target = readingLink(book.value.id, index, p);
  if (target === route.fullPath) {
    await scrollToParagraph(p);
    interact();
    void flush();
  } else await router.push(target);
}
function openToc(tab = 'chapters') {
  tocTab.value = tab;
  query.value = '';
  tocPage.value = tab === 'chapters' ? Math.floor((chapter.value?.index ?? 0) / 100) : 0;
  tocOpen.value = true;
  sample();
  toc.value?.showModal();
}
function closeToc() {
  tocOpen.value = false;
  interact();
}
function openBookmark(index: number) {
  if (!book.value || !chapter.value) return;
  noteError.value = '';
  const now = Date.now();
  selected.value = bookmarks.value.find(
    (b) => b.chapterIndex === chapter.value!.index && b.paragraphIndex === index,
  ) ?? {
    id: '',
    bookId: book.value.id,
    bookTitle: book.value.title,
    chapterIndex: chapter.value.index,
    paragraphIndex: index,
    chapterTitle: chapter.value.title,
    note: '',
    published: false,
    createdAt: now,
    updatedAt: now,
  };
  sample();
}
async function saveNote(note: string, published: boolean) {
  if (!selected.value) return;
  noteBusy.value = true;
  try {
    await journal.saveBookmark({ ...selected.value, note, published, updatedAt: Date.now() });
    bookmarks.value = await journal.bookmarks(book.value!.id);
    selected.value = null;
    interact();
  } catch (e) {
    noteError.value = errorMessage(e);
  } finally {
    noteBusy.value = false;
  }
}
async function removeNote() {
  if (!selected.value || !confirm('确定删除这枚书签与感想吗？')) return;
  noteBusy.value = true;
  try {
    await journal.deleteBookmark(selected.value.id);
    bookmarks.value = await journal.bookmarks(book.value!.id);
    selected.value = null;
    interact();
  } catch (e) {
    noteError.value = errorMessage(e);
  } finally {
    noteBusy.value = false;
  }
}
function hasBookmark(index: number) {
  return bookmarks.value.some(
    (b) => b.chapterIndex === chapter.value?.index && b.paragraphIndex === index,
  );
}
function keyboard(event: KeyboardEvent) {
  interact();
  const el = event.target as HTMLElement;
  if (
    el.matches('input,textarea,select,button') ||
    el.isContentEditable ||
    tocOpen.value ||
    selected.value ||
    event.altKey ||
    event.ctrlKey ||
    event.metaKey
  )
    return;
  if (event.key === 'ArrowRight' && chapter.value) {
    event.preventDefault();
    void go(chapter.value.index + 1);
  }
  if (event.key === 'ArrowLeft' && chapter.value) {
    event.preventDefault();
    void go(chapter.value.index - 1);
  }
}
function visibility() {
  sample();
  void flush(true);
}
function pageHide() {
  void flush(true);
}
onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('pointerdown', interact, { passive: true });
  window.addEventListener('keydown', keyboard);
  window.addEventListener('focus', interact);
  window.addEventListener('blur', visibility);
  window.addEventListener('pagehide', pageHide);
  document.addEventListener('visibilitychange', visibility);
  tickTimer = window.setInterval(() => {
    sample();
    if (clock.seconds - segment.base >= 1800) void flush();
  }, 1000);
  saveTimer = window.setInterval(() => void flush(), 15000);
});
onBeforeUnmount(() => {
  disposed = true;
  ++generation;
  clearInterval(tickTimer);
  clearInterval(saveTimer);
  cancelAnimationFrame(frame);
  window.removeEventListener('scroll', onScroll);
  window.removeEventListener('pointerdown', interact);
  window.removeEventListener('keydown', keyboard);
  window.removeEventListener('focus', interact);
  window.removeEventListener('blur', visibility);
  window.removeEventListener('pagehide', pageHide);
  document.removeEventListener('visibilitychange', visibility);
});
</script>
<template>
  <div class="reader-shell" :class="theme" :style="{ '--reading-size': font + 'px' }">
    <header class="reader-header">
      <RouterLink :to="book ? '/books/' + book.id : '/'" class="reader-back"
        ><ArrowLeft :size="19" /><span>{{ book?.title || '返回书架' }}</span></RouterLink
      >
      <div class="reader-tools">
        <button
          class="icon-button"
          aria-label="缩小字号"
          title="缩小字号"
          :disabled="font <= 16"
          @click="font -= 2"
        >
          <Minus :size="16" /></button
        ><span class="font-size" aria-live="polite">{{ font }}</span
        ><button
          class="icon-button"
          aria-label="放大字号"
          title="放大字号"
          :disabled="font >= 28"
          @click="font += 2"
        >
          <Plus :size="16" /></button
        ><span class="tool-divider" /><button
          class="icon-button"
          :aria-label="theme === 'paper' ? '切换夜间模式' : '切换日间模式'"
          @click="theme = theme === 'paper' ? 'night' : 'paper'"
        >
          <Moon v-if="theme === 'paper'" :size="18" /><Sun v-else :size="18" /></button
        ><button aria-label="打开目录" @click="openToc()">
          <List :size="18" /><span class="tool-label">目录</span>
        </button>
      </div>
    </header>
    <div class="reader-progress" :style="{ width: progressPercent + '%' }" />
    <div v-if="error" class="reader-status">
      <div class="notice error" role="alert">{{ error }}</div>
      <button @click="load">重新加载</button>
    </div>
    <div v-else-if="loading" class="reader-status" role="status">正在翻页…</div>
    <article v-else-if="chapter" class="reading-article">
      <header class="chapter-heading">
        <p class="eyebrow">{{ chapter.volume || '正文' }}</p>
        <h1>{{ chapter.title }}</h1>
        <p>
          {{ book?.author }} <span>·</span> {{ chapter.characterCount.toLocaleString() }} 字
          <span>·</span> {{ chapter.index + 1 }} / {{ book?.chapterCount }} 章
        </p>
      </header>
      <p class="reading-hint">段落旁的书签，可以留住位置与感想。</p>
      <div class="reading-body">
        <p
          v-for="(text, index) in chapter.paragraphs"
          :id="'paragraph-' + index"
          :key="index"
          class="reader-paragraph"
          :class="{ bookmarked: hasBookmark(index) }"
          :data-paragraph="index"
        >
          <span>{{ text }}</span
          ><button
            class="paragraph-bookmark"
            :class="{ marked: hasBookmark(index) }"
            :aria-label="(hasBookmark(index) ? '编辑' : '添加') + '第' + (index + 1) + '段书签'"
            :title="hasBookmark(index) ? '编辑书签与感想' : '为这一段留下书签'"
            @click="openBookmark(index)"
          >
            <BookmarkIcon :size="16" :fill="hasBookmark(index) ? 'currentColor' : 'none'" />
          </button>
        </p>
        <p v-if="!chapter.paragraphs.length" class="muted">这一章没有正文。</p>
      </div>
      <div class="chapter-end">
        <span>本章完</span>
        <div class="actions">
          <button :disabled="chapter.index === 0" @click="go(chapter.index - 1)">
            <ChevronLeft :size="16" /> 上一章</button
          ><button
            v-if="book && chapter.index + 1 < book.chapterCount"
            class="primary"
            @click="go(chapter.index + 1)"
          >
            下一章 <ChevronRight :size="16" /></button
          ><RouterLink v-else class="button primary" to="/">回到书架</RouterLink>
        </div>
      </div>
    </article>
    <footer class="reader-footer">
      <button
        :disabled="!chapter || chapter.index === 0 || loading"
        aria-label="上一章"
        @click="chapter && go(chapter.index - 1)"
      >
        <ChevronLeft :size="20" /><span class="tool-label">上一章</span>
      </button>
      <div v-if="syncError" class="reader-save-status" aria-live="polite">
        <small class="danger-text" role="alert">
          保存未完成 <button class="retry-save" @click="flush()">重试</button>
        </small>
      </div>
      <button
        :disabled="!chapter || !book || chapter.index + 1 >= book.chapterCount || loading"
        aria-label="下一章"
        @click="chapter && go(chapter.index + 1)"
      >
        <span class="tool-label">下一章</span><ChevronRight :size="20" />
      </button>
    </footer>
    <dialog ref="toc" class="toc-dialog" aria-labelledby="toc-heading" @close="closeToc">
      <div class="section-top">
        <h2 id="toc-heading">{{ book?.title }}</h2>
        <button class="icon-button" aria-label="关闭目录" @click="toc?.close()">
          <X :size="20" />
        </button>
      </div>
      <div class="tabs">
        <button :class="{ active: tocTab === 'chapters' }" @click="tocTab = 'chapters'">
          目录 {{ chapters.length }}</button
        ><button :class="{ active: tocTab === 'notes' }" @click="tocTab = 'notes'">
          我的书签 {{ bookmarks.length }}
        </button>
      </div>
      <template v-if="tocTab === 'chapters'"
        ><label class="search-box"
          ><Search :size="16" /><input
            v-model="query"
            aria-label="搜索目录"
            placeholder="搜索章节"
            type="search"
        /></label>
        <div class="toc-list">
          <button
            v-for="item in tocChapters"
            :key="item.index"
            :class="{ current: item.index === chapter?.index }"
            @click="go(item.index)"
          >
            <small v-if="item.volume">{{ item.volume }}</small
            >{{ item.title }}
          </button>
          <p v-if="!tocChapters.length" class="empty-state">没有匹配的章节</p>
        </div>
        <div class="pagination">
          <button :disabled="tocPage === 0" @click="tocPage--">上一页</button
          ><span>{{ tocPage + 1 }} / {{ tocPages }}</span
          ><button :disabled="tocPage + 1 >= tocPages" @click="tocPage++">下一页</button>
        </div></template
      >
      <div v-else class="toc-list">
        <p v-if="!bookmarks.length" class="empty-state">还没有书签，点击段落旁的书签试试。</p>
        <button
          v-for="note in bookmarks"
          :key="note.id"
          @click="go(note.chapterIndex, note.paragraphIndex)"
        >
          <small>{{ note.chapterTitle }} · 第 {{ note.paragraphIndex + 1 }} 段</small
          >{{ note.note || '留住了这一页。' }}
        </button>
      </div>
    </dialog>
    <BookmarkEditor
      :bookmark="selected"
      :owner="owner"
      :busy="noteBusy"
      :error="noteError"
      @close="
        selected = null;
        interact();
      "
      @save="saveNote"
      @remove="removeNote"
    />
  </div>
</template>
