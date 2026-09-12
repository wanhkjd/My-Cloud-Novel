<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';
import type { Bookmark } from '../lib/types';
const props = defineProps<{
  bookmark: Bookmark | null;
  owner: boolean;
  busy: boolean;
  error: string;
}>();
const emit = defineEmits<{ close: []; save: [note: string, published: boolean]; remove: [] }>();
const dialog = ref<HTMLDialogElement>();
const note = ref('');
const published = ref(false);
watch(
  () => props.bookmark,
  async (b) => {
    if (b) {
      note.value = b.note;
      published.value = b.published;
      await nextTick();
      if (!dialog.value?.open) dialog.value?.showModal();
    } else dialog.value?.close();
  },
);
</script>
<template>
  <dialog
    ref="dialog"
    class="editor-dialog"
    aria-labelledby="bookmark-heading"
    @close="emit('close')"
    @cancel="emit('close')"
  >
    <form v-if="bookmark" @submit.prevent="emit('save', note, published)">
      <div class="section-top">
        <h2 id="bookmark-heading">{{ bookmark.id ? '编辑书签' : '留一枚书签' }}</h2>
        <button type="button" class="icon-button" aria-label="关闭书签编辑" @click="emit('close')">
          ×
        </button>
      </div>
      <p class="muted">{{ bookmark.chapterTitle }} · 第 {{ bookmark.paragraphIndex + 1 }} 段</p>
      <label
        >此刻的感想 <span class="muted">（选填）</span
        ><textarea
          v-model="note"
          rows="6"
          maxlength="4000"
          placeholder="把这一刻的想法，留在这一页。"
          autofocus
        /></label
      ><label v-if="owner" class="check-label"
        ><input v-model="published" type="checkbox" />公开这条感想（仅随已公开正文展示）</label
      >
      <p v-else class="muted small">仅保存在这个浏览器，不会公开或写入主人的记录。</p>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <div class="actions">
        <button class="primary" type="submit" :disabled="busy">
          {{ busy ? '正在保存…' : '保存书签' }}</button
        ><button type="button" :disabled="busy" @click="emit('close')">取消</button
        ><button
          v-if="bookmark.id"
          type="button"
          class="danger-text push-right"
          :disabled="busy"
          @click="emit('remove')"
        >
          删除书签
        </button>
      </div>
    </form>
  </dialog>
</template>
