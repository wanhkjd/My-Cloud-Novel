<script setup lang="ts">
import { computed } from 'vue';
const props = defineProps<{ title: string; author: string; small?: boolean }>();
const tone = computed(() => [...props.title].reduce((sum, c) => sum + c.charCodeAt(0), 0) % 4);
</script>
<template>
  <div class="book-cover" :class="['tone-' + tone, { small }]" aria-hidden="true">
    <span class="cover-edition">云 上 藏 书</span>
    <div class="cover-title">{{ title }}</div>
    <span class="cover-author">{{ author }} · 著</span><span class="cover-seal">阅</span>
  </div>
</template>
