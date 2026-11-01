<script setup lang="ts">
import IdentifyPanel from '../shared/IdentifyPanel.vue'
const link = (id: number) => `/book/${id}`
</script>
<template>
  <div class="scan">
    <h1 class="t serif">识书</h1>
    <IdentifyPanel :book-link="link" />
  </div>
</template>
<style scoped>
.scan { padding-top: 20px; }
.t { font-size: 24px; margin-bottom: 14px; }
</style>
