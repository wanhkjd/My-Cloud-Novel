<script setup lang="ts">
import { ref, watch } from 'vue';
import BookCover from './BookCover.vue';
const props = defineProps<{
  id: string;
  title: string;
  author: string;
  hasCover: boolean;
  small?: boolean;
}>();
// 声明无封面时根本不发请求；真实封面加载失败则回退到装饰性 CSS 假封面。
const failed = ref(false);
watch(
  () => [props.id, props.hasCover],
  () => {
    failed.value = false;
  },
);
</script>
<template>
  <img
    v-if="hasCover && !failed"
    class="book-cover-image"
    :class="{ small }"
    :src="'/api/books/' + id + '/cover'"
    alt=""
    loading="lazy"
    @error="failed = true"
  />
  <BookCover v-else :title="title" :author="author" :small="small" />
</template>
