<script setup lang="ts">
defineProps<{ progress: number; nodes: number[] }>();
</script>

<template>
  <svg class="galaxy-vine" viewBox="0 0 100 1000" preserveAspectRatio="none" aria-hidden="true">
    <path
      class="vine-path"
      pathLength="1"
      :style="{ strokeDashoffset: 1 - progress }"
      d="M50 1000 C 20 800, 80 620, 50 500 S 20 260, 50 60"
    />
    <circle
      v-for="(at, i) in nodes"
      :key="i"
      class="vine-node"
      :class="{ lit: at <= progress }"
      cx="50"
      :cy="1000 - at * 1000"
      r="6"
    />
  </svg>
</template>
