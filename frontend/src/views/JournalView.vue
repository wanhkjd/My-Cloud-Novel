<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ArrowUpRight, Bookmark as BookmarkIcon, Clock3, Footprints } from 'lucide-vue-next';
import { auth } from '../lib/auth';
import { errorMessage } from '../lib/api';
import { createJournal, GUEST_KEY } from '../lib/journal';
import { dateTime, duration, readingLink } from '../lib/format';
import type { Bookmark, Progress, ReadingSession, Stats } from '../lib/types';
import BookmarkEditor from '../components/BookmarkEditor.vue';
const owner = auth.authenticated;
const journal = createJournal(owner);
const stats = ref<Stats>();
const progress = ref<Progress[]>([]);
const history = ref<ReadingSession[]>([]);
const notes = ref<Bookmark[]>([]);
const error = ref('');
const loading = ref(true);
const tab = ref('history');
const selected = ref<Bookmark | null>(null);
const noteError = ref('');
const saving = ref(false);
const maxDay = computed(() => Math.max(60, ...(stats.value?.days.map((d) => d.seconds) ?? [])));
async function load() {
  loading.value = true;
  error.value = '';
  try {
    [stats.value, progress.value, history.value, notes.value] = await Promise.all([
      journal.stats(),
      journal.listProgress(),
      journal.history(),
      journal.bookmarks(),
    ]);
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
function edit(bookmark: Bookmark) {
  selected.value = bookmark;
  noteError.value = '';
}
async function saveNote(note: string, published: boolean) {
  if (!selected.value) return;
  saving.value = true;
  try {
    await journal.saveBookmark({ ...selected.value, note, published, updatedAt: Date.now() });
    selected.value = null;
    await load();
  } catch (e) {
    noteError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function removeNote() {
  if (!selected.value || !confirm('确定删除这枚书签与感想吗？')) return;
  saving.value = true;
  try {
    await journal.deleteBookmark(selected.value.id);
    selected.value = null;
    await load();
  } catch (e) {
    noteError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function clearLocal() {
  if (confirm('确定清除这个浏览器中的访客阅读记录和书签吗？此操作无法撤销。')) {
    try {
      localStorage.removeItem(GUEST_KEY);
      await load();
    } catch (e) {
      error.value = errorMessage(e);
    }
  }
}
onMounted(load);
</script>
<template>
  <div class="page journal-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">读过的日子，都有回声</p>
        <h1>阅读足迹</h1>
        <p class="muted">
          {{
            owner
              ? '这是只属于你的阅读日记，记录会同步到书房服务器。'
              : '这是你在本机的阅读足迹，不是书房主人的阅读记录。'
          }}
        </p>
      </div>
      <span class="scope-label"
        ><span class="status-dot" />{{ owner ? '主人 · 服务器记录' : '访客 · 仅本机保存' }}</span
      >
    </div>
    <div v-if="error" class="notice error" role="alert">
      {{ error }}<button @click="load">重试</button>
    </div>
    <div v-if="loading" class="empty-state" role="status">正在找回阅读的日子…</div>
    <template v-else-if="stats"
      ><div class="stat-row">
        <div>
          <Clock3 :size="19" /><span>累计阅读</span
          ><strong>{{ duration(stats.totalSeconds) }}</strong>
        </div>
        <div>
          <span>今日阅读</span><strong>{{ duration(stats.todaySeconds) }}</strong>
        </div>
        <div>
          <span>读过的书</span><strong>{{ stats.books.length }} <small>部</small></strong>
        </div>
        <div>
          <BookmarkIcon :size="18" /><span>书签与感想</span
          ><strong>{{ notes.length }} <small>枚</small></strong>
        </div>
      </div>
      <section class="activity-section">
        <div class="section-top">
          <h2>最近两周</h2>
          <span class="muted small">北京时间 · 有效前台阅读估算</span>
        </div>
        <ol class="activity-chart" aria-label="最近十四天的阅读时长">
          <li
            v-for="day in stats.days"
            :key="day.date"
            :title="day.date + '：' + duration(day.seconds)"
            :aria-label="day.date + '，' + duration(day.seconds)"
          >
            <div class="bar-space">
              <span
                :style="{ height: Math.max(2, (day.seconds / maxDay) * 100) + '%' }"
                :class="{ filled: day.seconds > 0 }"
              />
            </div>
            <small>{{ day.date.slice(5).replace('-', '/') }}</small>
          </li>
        </ol>
        <p class="muted small">
          切到后台、失去焦点、手动暂停或两分钟没有操作时，计时会暂停。不是精确的阅读能力测量。
        </p>
      </section>
      <section v-if="progress.length" class="progress-section">
        <h2>书页停在这里</h2>
        <div class="progress-grid">
          <RouterLink
            v-for="item in progress"
            :key="item.bookId"
            :to="readingLink(item.bookId, item.chapterIndex, item.paragraphIndex)"
            class="progress-card"
            ><div>
              <span class="muted small">最近读到</span>
              <h3>{{ item.bookTitle }}</h3>
              <p>{{ item.chapterTitle }}</p>
              <span class="muted small"
                >第 {{ item.paragraphIndex + 1 }} 段 · {{ dateTime(item.updatedAt) }}</span
              >
            </div>
            <ArrowUpRight :size="20" />
            <div class="progress-track">
              <span
                :style="{ width: ((item.chapterIndex + 1) / item.chapterCount) * 100 + '%' }"
              /></div
          ></RouterLink>
        </div>
      </section>
      <div class="tabs">
        <button :class="{ active: tab === 'history' }" @click="tab = 'history'">阅读历程</button
        ><button :class="{ active: tab === 'notes' }" @click="tab = 'notes'">
          书签与感想 <span>{{ notes.length }}</span></button
        ><button :class="{ active: tab === 'books' }" @click="tab = 'books'">按书统计</button>
      </div>
      <template v-if="tab === 'history'"
        ><div v-if="!history.length" class="empty-state">
          <Footprints :size="32" />
          <h3>故事从第一页开始</h3>
          <p>翻开一本书，阅读的时间和位置就会记在这里。</p>
          <RouterLink to="/" class="button secondary">去书架看看</RouterLink>
        </div>
        <div v-else class="history-list">
          <div class="section-top muted small">
            <span>最近 {{ history.length }} 段阅读记录，最多展示 200 段</span
            ><span>每段最多 30 分钟</span>
          </div>
          <article v-for="item in history" :key="item.id" class="history-row">
            <span class="history-date">{{ dateTime(item.updatedAt) }}</span
            ><RouterLink :to="readingLink(item.bookId, item.chapterIndex, item.paragraphIndex)"
              ><strong>{{ item.bookTitle }}</strong
              ><span
                >{{ item.chapterTitle }} · 第 {{ item.paragraphIndex + 1 }} 段</span
              ></RouterLink
            ><span class="time-label">{{ duration(item.elapsedSeconds) }}</span>
          </article>
        </div></template
      >
      <template v-else-if="tab === 'notes'"
        ><div v-if="!notes.length" class="empty-state">
          <h3>还没有书签</h3>
          <p>阅读时点击段落旁的书签符号，把感受记下来。</p>
        </div>
        <article v-for="note in notes" :key="note.id" class="note-card">
          <div class="section-top">
            <RouterLink :to="readingLink(note.bookId, note.chapterIndex, note.paragraphIndex)"
              ><strong>{{ note.bookTitle }}</strong> · {{ note.chapterTitle }} · 第
              {{ note.paragraphIndex + 1 }} 段</RouterLink
            ><span class="tag">{{ note.published ? '公开感想' : '私密书签' }}</span>
          </div>
          <p class="note-text">{{ note.note || '留住了这一页。' }}</p>
          <div class="section-top">
            <span class="muted small">{{ dateTime(note.updatedAt) }}</span
            ><button @click="edit(note)">编辑感想</button>
          </div>
        </article></template
      >
      <template v-else
        ><div v-if="!stats.books.length" class="empty-state">还没有可统计的阅读时间。</div>
        <div v-for="book in stats.books" :key="book.bookId" class="book-time-row">
          <RouterLink :to="'/books/' + book.bookId">{{ book.title }}</RouterLink
          ><span>{{ duration(book.seconds) }}</span>
        </div></template
      >
      <p class="privacy-footnote">
        {{
          owner
            ? '阅读历史与时长仅管理员可见；书签感想只有在你主动公开后才对访客展示。'
            : '清除浏览器数据或更换设备会丢失这些记录；登录不会将访客记录合并到主人账号。'
        }}<button v-if="!owner" class="danger-text" @click="clearLocal">清除本机记录</button>
      </p>
    </template>
    <BookmarkEditor
      :bookmark="selected"
      :owner="owner"
      :busy="saving"
      :error="noteError"
      @close="selected = null"
      @save="saveNote"
      @remove="removeNote"
    />
  </div>
</template>
