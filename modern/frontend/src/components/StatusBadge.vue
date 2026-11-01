<script setup lang="ts">
defineProps<{ tone?: string; label: string }>()
</script>
<template>
  <span class="badge" :class="tone">{{ label }}</span>
</template>
