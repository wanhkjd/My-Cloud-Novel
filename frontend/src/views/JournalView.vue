<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ArrowUpRight, Bookmark as BookmarkIcon, Footprints } from 'lucide-vue-next';
import { auth } from '../lib/auth';
import { errorMessage } from '../lib/api';
import { createJournal, GUEST_KEY } from '../lib/journal';
import { dateTime, readingLink } from '../lib/format';
import type { Bookmark, Progress, ReadingSession } from '../lib/types';
import BookmarkEditor from '../components/BookmarkEditor.vue';
const owner = auth.authenticated;
const journal = createJournal(owner);
const progress = ref<Progress[]>([]);
const history = ref<ReadingSession[]>([]);
const notes = ref<Bookmark[]>([]);
const error = ref('');
const loading = ref(true);
const tab = ref('history');
const selected = ref<Bookmark | null>(null);
const noteError = ref('');
const saving = ref(false);
async function load() {
  loading.value = true;
  error.value = '';
  try {
    [progress.value, history.value, notes.value] = await Promise.all([
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
  if (confirm('确定清除这台设备上的阅读足迹和书签吗？此操作无法撤销。')) {
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
        <p class="eyebrow">在书页之间</p>
        <h1>阅读札记</h1>
        <p class="muted">
          {{
            owner ? '收藏读过的故事，记下想重返的句子。' : '把读过的故事与想重返的句子，留在这里。'
          }}
        </p>
      </div>
    </div>
    <div v-if="error" class="notice error" role="alert">
      {{ error }}<button @click="load">重试</button>
    </div>
    <div v-if="loading" class="empty-state" role="status">正在找回你的书页…</div>
    <template v-else>
      <section v-if="progress.length" class="progress-section">
        <div class="section-top">
          <h2>最近翻到这里</h2>
          <span class="muted small">从上次停下的地方继续</span>
        </div>
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
          书签与感想 <span>{{ notes.length }}</span>
        </button>
      </div>
      <template v-if="tab === 'history'"
        ><div v-if="!history.length" class="empty-state">
          <Footprints :size="32" />
          <h3>故事从第一页开始</h3>
          <p>翻开一本书，留下的章节会收在这里。</p>
          <RouterLink to="/" class="button secondary">去书架看看</RouterLink>
        </div>
        <div v-else class="history-list">
          <div class="section-top muted small">
            <span>最近翻过的 {{ history.length }} 个章节</span>
          </div>
          <article v-for="item in history" :key="item.id" class="history-row">
            <span class="history-date">{{ dateTime(item.updatedAt) }}</span
            ><RouterLink :to="readingLink(item.bookId, item.chapterIndex, item.paragraphIndex)"
              ><strong>{{ item.bookTitle }}</strong
              ><span
                >{{ item.chapterTitle }} · 第 {{ item.paragraphIndex + 1 }} 段</span
              ></RouterLink
            >
          </article>
        </div></template
      >
      <template v-else
        ><div v-if="!notes.length" class="empty-state">
          <BookmarkIcon :size="32" />
          <h3>还没有书签</h3>
          <p>阅读时点击段落旁的书签符号，把感受留在这一页。</p>
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
      <p class="privacy-footnote">
        {{
          owner ? '书签与感想只有在你主动公开后，才会出现在书籍页面。' : '这些阅读足迹只为你保留。'
        }}
        <button v-if="!owner" class="danger-text" @click="clearLocal">清除足迹</button>
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
