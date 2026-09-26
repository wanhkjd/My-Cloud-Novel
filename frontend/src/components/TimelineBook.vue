<script setup lang="ts">
import { RouterLink } from 'vue-router';
import type { Book } from '../lib/types';
import { effectiveTime } from '../lib/timeline';
import { dayLabel, number, words } from '../lib/format';
import CoverImage from './CoverImage.vue';

const props = defineProps<{ book: Book; side: 'left' | 'right'; revealed: boolean }>();
</script>

<template>
  <li class="timeline-book" :class="[props.side, { revealed: props.revealed }]">
    <time class="timeline-date" :datetime="book.timelineDate ?? undefined">{{
      dayLabel(effectiveTime(book))
    }}</time>
    <RouterLink class="timeline-card" :to="'/books/' + book.id">
      <CoverImage
        :id="book.id"
        :title="book.title"
        :author="book.author"
        :has-cover="book.hasCover"
        small
      />
      <span class="timeline-card-copy">
        <span class="timeline-title">{{ book.title }}</span>
        <span class="timeline-author">{{ book.author }}</span>
        <span v-if="book.description" class="timeline-description">{{ book.description }}</span>
        <span class="timeline-meta"
          >{{ number(book.chapterCount) }} 章 · {{ words(book.characterCount) }}
          <span v-if="book.textPublished" class="timeline-flag">可阅读</span></span
        >
      </span>
    </RouterLink>
  </li>
</template>
