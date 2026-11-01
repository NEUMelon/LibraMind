<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { coverTheme, hasRealCover } from '@/lib/cover'

const props = withDefaults(
  defineProps<{
    title: string
    authors?: string
    coverUrl?: string | null
    seed?: string
    width?: number
  }>(),
  { authors: '', coverUrl: null, seed: '', width: 96 },
)

const failed = ref(false)
watch(() => props.coverUrl, () => (failed.value = false))
const showImage = computed(() => hasRealCover(props.coverUrl) && !failed.value)
const theme = computed(() => coverTheme(props.seed || props.title))
const short = computed(() => {
  // 去掉括号里的版次，让排印封面更干净；完整书名在旁边的文字里。
  const t = props.title.replace(/[（(][^)）]*[)）]\s*$/, '').trim()
  return t.length > 22 ? t.slice(0, 21) + '…' : t
})
const firstAuthor = computed(() => props.authors.split(/[、,，;；]/)[0]?.trim() ?? '')
const scale = computed(() => props.width / 96)
</script>

<template>
  <div
    class="cover"
    :style="{ width: `${width}px`, '--s': scale }"
    :class="{ 'is-generated': !showImage }"
    role="img"
    :aria-label="`《${title}》封面${showImage ? '' : '（无封面图）'}`"
  >
    <img
      v-if="showImage"
      :src="coverUrl!"
      alt=""
      loading="lazy"
      decoding="async"
      @error="failed = true"
    />
    <div v-else class="gen" :style="{ background: theme.bg, color: theme.fg }">
      <span class="rule" :style="{ background: theme.rule }"></span>
      <span class="t serif">{{ short }}</span>
      <span class="a">{{ firstAuthor }}</span>
    </div>
  </div>
</template>

<style scoped>
.cover {
  position: relative;
  flex: none;
  aspect-ratio: 2 / 3;
  border-radius: 3px 5px 5px 3px;
  overflow: hidden;
  background: var(--paper-2);
  box-shadow: 0 1px 1px rgba(30, 24, 14, 0.12), 0 4px 10px -4px rgba(30, 24, 14, 0.28);
}
.cover::after {
  /* 书脊折痕 */
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: calc(6px * var(--s));
  background: linear-gradient(90deg, rgba(0, 0, 0, 0.16), rgba(255, 255, 255, 0.06) 60%, transparent);
  pointer-events: none;
}
img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.gen {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: calc(12px * var(--s)) calc(9px * var(--s)) calc(9px * var(--s)) calc(13px * var(--s));
}
.rule {
  display: block;
  width: 38%;
  height: max(1px, calc(2px * var(--s)));
  margin-bottom: calc(9px * var(--s));
  opacity: 0.9;
}
.t {
  font-size: calc(13px * var(--s));
  line-height: 1.32;
  font-weight: 600;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 5;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.a {
  margin-top: auto;
  font-size: calc(9.5px * var(--s));
  opacity: 0.78;
  line-height: 1.3;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
