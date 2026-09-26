<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue';
import { Upload, Pencil, Trash2, LockKeyhole, ArrowUpRight } from 'lucide-vue-next';
import { errorMessage, jsonRequest, request } from '../lib/api';
import { number, words } from '../lib/format';
import CoverImage from '../components/CoverImage.vue';
import type { Book } from '../lib/types';
const books = ref<Book[]>([]);
const loading = ref(true);
const error = ref('');
const message = ref('');
const file = ref<File | null>(null);
const fileInput = ref<HTMLInputElement>();
const uploading = ref(false);
const imported = ref<Book>();
const selected = ref<Book | null>(null);
const dialog = ref<HTMLDialogElement>();
const coverFile = ref<HTMLInputElement>();
const saving = ref(false);
const editError = ref('');
const form = reactive({
  title: '',
  author: '',
  description: '',
  catalogPublished: false,
  textPublished: false,
  timelineDate: '',
});
async function load() {
  loading.value = true;
  error.value = '';
  try {
    books.value = await request<Book[]>('/api/books');
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    loading.value = false;
  }
}
function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}
async function upload() {
  if (!file.value) return;
  error.value = '';
  message.value = '';
  imported.value = undefined;
  if (!file.value.name.toLowerCase().endsWith('.txt') || file.value.size > 25 * 1024 * 1024) {
    error.value = '请选择不超过 25 MiB 的 TXT 小说。';
    return;
  }
  uploading.value = true;
  try {
    const data = new FormData();
    data.append('file', file.value);
    imported.value = await request<Book>('/api/books', { method: 'POST', body: data });
    await load();
    file.value = null;
    if (fileInput.value) fileInput.value.value = '';
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    uploading.value = false;
  }
}
async function edit(book: Book) {
  selected.value = book;
  Object.assign(form, book);
  form.timelineDate = book.timelineDate ?? '';
  editError.value = '';
  await nextTick();
  dialog.value?.showModal();
}
async function save() {
  if (!selected.value) return;
  saving.value = true;
  editError.value = '';
  try {
    await jsonRequest('/api/books/' + selected.value.id, 'PATCH', {
      ...form,
      timelineDate: form.timelineDate || null,
    });
    dialog.value?.close();
    selected.value = null;
    message.value = '书籍信息与公开设置已保存。';
    await load();
  } catch (e) {
    editError.value = errorMessage(e);
  } finally {
    saving.value = false;
  }
}
async function uploadCover(event: Event) {
  const picked = (event.target as HTMLInputElement).files?.[0];
  if (!picked || !selected.value) return;
  editError.value = '';
  if (!picked.type.startsWith('image/') || picked.size > 2 * 1024 * 1024) {
    editError.value = '请选择不超过 2 MiB 的 JPEG / PNG / WebP 图片作为封面。';
    if (coverFile.value) coverFile.value.value = '';
    return;
  }
  try {
    const data = new FormData();
    data.append('file', picked);
    const updated = await request<Book>('/api/books/' + selected.value.id + '/cover', {
      method: 'POST',
      body: data,
    });
    selected.value = updated;
    Object.assign(form, updated);
    form.timelineDate = updated.timelineDate ?? '';
    message.value = '封面已更新。';
    await load();
  } catch (e) {
    editError.value = errorMessage(e);
  } finally {
    if (coverFile.value) coverFile.value.value = '';
  }
}
async function removeCover() {
  if (!selected.value) return;
  editError.value = '';
  try {
    await jsonRequest('/api/books/' + selected.value.id + '/cover', 'DELETE');
    selected.value = { ...selected.value, hasCover: false };
    message.value = '封面已移除。';
    await load();
  } catch (e) {
    editError.value = errorMessage(e);
  }
}
async function remove(book: Book) {
  if (
    !window.confirm(
      '确定删除《' + book.title + '》吗？原始文件、章节、该书的阅读记录与书签都会删除，无法撤销。',
    )
  )
    return;
  error.value = '';
  try {
    await jsonRequest('/api/books/' + book.id, 'DELETE');
    message.value = '已删除《' + book.title + '》。';
    if (imported.value?.id === book.id) imported.value = undefined;
    await load();
  } catch (e) {
    error.value = errorMessage(e);
  }
}
onMounted(load);
</script>
<template>
  <div class="page admin-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">书房日常</p>
        <h1>把书架整理好。</h1>
        <p class="muted">上传、校对、公开。每一本书的去留，由你决定。</p>
      </div>
      <RouterLink to="/" class="button secondary">查看书架 <ArrowUpRight :size="17" /></RouterLink>
    </div>
    <div class="notice">
      <LockKeyhole :size="18" /><span
        >新上传的小说一律为私有草稿。请仅公开你有权传播的正文；公开正文也会允许访客下载原始
        TXT。</span
      >
    </div>
    <section class="upload-panel panel">
      <div>
        <span class="eyebrow">01 / 添一本新书</span>
        <h2>上传小说</h2>
        <p class="muted">
          TXT · UTF-8 / GBK · 最大 25 MiB<br />自动识别章节与分卷，不会改动原始文件。
        </p>
      </div>
      <form @submit.prevent="upload">
        <label class="file-label"
          ><Upload :size="21" /><span>{{ file ? file.name : '选择 TXT 文件' }}</span
          ><input
            ref="fileInput"
            type="file"
            accept=".txt,text/plain"
            aria-label="选择 TXT 文件"
            :disabled="uploading"
            @change="selectFile" /></label
        ><button class="primary" type="submit" :disabled="!file || uploading">
          {{ uploading ? '正在上传并解析…' : '导入为私有草稿' }}</button
        ><span v-if="file" class="muted small">{{ (file.size / 1024 / 1024).toFixed(2) }} MiB</span>
      </form>
    </section>
    <div v-if="imported" class="notice success" role="status" aria-label="小说导入结果">
      <div>
        <strong>《{{ imported.title }}》导入完成</strong>
        <p>
          {{ number(imported.chapterCount) }} 章 · {{ imported.volumeCount }} 卷 ·
          {{ words(imported.characterCount) }} · {{ imported.encoding }}
        </p>
        <RouterLink :to="'/books/' + imported.id">去私有预览，核对解析结果 →</RouterLink>
      </div>
      <span class="tag">尚未公开</span>
    </div>
    <div v-if="error" class="notice error" role="alert">
      {{ error }}<button @click="load">刷新列表</button>
    </div>
    <p v-if="message" class="notice success" role="status">{{ message }}</p>
    <section>
      <div class="section-top">
        <div>
          <p class="eyebrow">02 / 整理我的收藏</p>
          <h2>
            藏书管理 <span class="count-label">{{ books.length }}</span>
          </h2>
        </div>
      </div>
      <div v-if="loading" class="empty-state" role="status">正在读取藏书…</div>
      <div v-else-if="!books.length" class="empty-state">书架还是空的，从上传第一本书开始。</div>
      <div v-else class="manage-list">
        <article v-for="book in books" :key="book.id" class="manage-row">
          <div class="manage-book">
            <RouterLink :to="'/books/' + book.id"
              ><h3>{{ book.title }}</h3></RouterLink
            ><span class="muted"
              >{{ book.author }} · {{ number(book.chapterCount) }} 章 ·
              {{ words(book.characterCount) }}</span
            >
          </div>
          <div class="publish-status">
            <span class="tag" :class="{ public: book.catalogPublished }">{{
              book.catalogPublished ? '书目公开' : '私有草稿'
            }}</span
            ><span class="tag" :class="{ public: book.textPublished }">{{
              book.textPublished ? '正文公开' : '正文私有'
            }}</span>
          </div>
          <div class="actions">
            <button :aria-label="'编辑' + book.title" @click="edit(book)">
              <Pencil :size="15" /> 编辑</button
            ><button
              class="icon-button danger-text"
              :aria-label="'删除' + book.title"
              @click="remove(book)"
            >
              <Trash2 :size="17" />
            </button>
          </div>
        </article>
      </div>
    </section>
    <aside class="management-note">
      <h3>一点整理建议</h3>
      <p>
        先在私有预览中核对书名、作者与章节，再决定是否公开。章节正文和阅读记录保存在 MySQL，原始 TXT
        保存在服务器私有磁盘。迁移或升级前请停机，并分别备份 MySQL 数据库和原件目录。
      </p>
    </aside>
    <dialog
      ref="dialog"
      class="editor-dialog"
      aria-labelledby="edit-book-heading"
      @close="selected = null"
    >
      <form v-if="selected" @submit.prevent="save">
        <div class="section-top">
          <h2 id="edit-book-heading">整理这本书</h2>
          <button class="icon-button" type="button" aria-label="关闭编辑" @click="dialog?.close()">
            ×
          </button>
        </div>
        <label>书名<input v-model="form.title" required maxlength="120" /></label
        ><label>作者<input v-model="form.author" required maxlength="100" /></label
        ><label>简介<textarea v-model="form.description" rows="4" maxlength="4000" /></label
        ><label
          >时间轴日期<input v-model="form.timelineDate" type="date" /><span class="muted small"
            >留空则按导入时间排列于银河时间轴。</span
          ></label
        >
        <fieldset class="cover-fields">
          <legend>封面</legend>
          <div class="cover-editor">
            <CoverImage
              :id="selected.id"
              :title="form.title"
              :author="form.author"
              :has-cover="selected.hasCover"
              small
            />
            <div class="cover-actions">
              <label class="file-label"
                ><Upload :size="18" /><span>{{ selected.hasCover ? '更换封面' : '上传封面' }}</span
                ><input
                  ref="coverFile"
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  aria-label="选择封面图片"
                  @change="uploadCover"
              /></label>
              <button
                v-if="selected.hasCover"
                type="button"
                class="icon-button danger-text"
                @click="removeCover"
              >
                移除封面
              </button>
              <p class="muted small">JPEG / PNG / WebP · 最大 2 MiB · 立即生效。</p>
            </div>
          </div>
        </fieldset>
        <fieldset class="publication-fields">
          <legend>公开范围</legend>
          <label class="check-label"
            ><input
              v-model="form.catalogPublished"
              type="checkbox"
              @change="!form.catalogPublished && (form.textPublished = false)"
            />向访客展示这本书的书目</label
          ><label class="check-label"
            ><input
              v-model="form.textPublished"
              type="checkbox"
              :disabled="!form.catalogPublished"
            />我有权公开正文，允许访客阅读和下载</label
          >
          <p class="muted small">
            关闭公开设置后，访客将无法继续获取相关内容；已下载或已打开的内容无法远程收回。
          </p>
        </fieldset>
        <p v-if="editError" class="notice error" role="alert">{{ editError }}</p>
        <div class="actions">
          <button class="primary" type="submit" :disabled="saving">
            {{ saving ? '正在保存…' : '保存设置' }}</button
          ><button type="button" @click="dialog?.close()">取消</button>
        </div>
      </form>
    </dialog>
  </div>
</template>
