<script setup lang="ts">
import IdentifyPanel from '../shared/IdentifyPanel.vue'
const link = (id: number) => `/books?id=${id}`
</script>
<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">条码与封面识书</h1>
        <p class="page-sub">拿不准是哪一本时用来查找；借还仍以扫描登录号为准。</p>
      </div>
    </header>
    <IdentifyPanel :book-link="link" />
  </div>
</template>
