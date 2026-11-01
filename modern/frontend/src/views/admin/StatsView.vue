<script setup lang="ts">
// 统计：只展示后端已有的概况与借阅趋势，不新增排行或推荐类指标。
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { admin } from '@/api/endpoints'
import type { DayCount, Overview } from '@/api/types'
import ErrorNotice from '@/components/ErrorNotice.vue'

const overview = ref<Overview | null>(null)
const trend = ref<DayCount[]>([])
const error = ref<unknown>(null)
async function load() {
  error.value = null
  try {
    ;[overview.value, trend.value] = await Promise.all([admin.overview(), admin.loanTrend()])
  } catch (e) {
    error.value = e
  }
}
onMounted(load)

function key(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
/** 接口只返回有借阅的日期；没有记录的日子按 0 补齐，最近 30 天。 */
const days = computed(() => {
  const map = new Map(trend.value.map((d) => [d.date, d.count]))
  const out: { date: string; count: number }[] = []
  const today = new Date()
  for (let i = 29; i >= 0; i--) {
    const d = new Date(today.getFullYear(), today.getMonth(), today.getDate() - i)
    out.push({ date: key(d), count: map.get(key(d)) ?? 0 })
  }
  return out
})
const total = computed(() => days.value.reduce((s, d) => s + d.count, 0))
const max = computed(() => Math.max(1, ...days.value.map((d) => d.count)))
const ticks = computed(() => {
  const m = max.value
  const step = m <= 4 ? 1 : Math.ceil(m / 4)
  const out: number[] = []
  for (let v = 0; v <= m; v += step) out.push(v)
  if (out[out.length - 1] < m) out.push(out[out.length - 1] + step)
  return out
})
const top = computed(() => ticks.value[ticks.value.length - 1])
const W = 720
const H = 200
const PAD = { l: 32, r: 8, t: 10, b: 26 }
const bw = computed(() => (W - PAD.l - PAD.r) / days.value.length)
const y = (v: number) => PAD.t + (H - PAD.t - PAD.b) * (1 - v / top.value)
const hover = ref<number | null>(null)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <h1 class="page-title">统计</h1>
        <p class="page-sub">数字来自实时查询。学生、馆藏和借阅均为课程演示数据。</p>
      </div>
    </header>
    <ErrorNotice v-if="error" :error="error" :retry="load" />
    <template v-else>
      <dl v-if="overview" class="strip">
        <div><dt>学生</dt><dd class="num">{{ overview.students.toLocaleString() }}</dd></div>
        <div><dt>出版版本</dt><dd class="num">{{ overview.editions.toLocaleString() }}</dd></div>
        <div><dt>实体副本</dt><dd class="num">{{ overview.copies.toLocaleString() }}</dd></div>
        <div><dt>在架可借</dt><dd class="num">{{ overview.available.toLocaleString() }} <small>{{ Math.round((overview.available / Math.max(1, overview.copies)) * 100) }}%</small></dd></div>
        <div><dt>在借</dt><dd class="num"><RouterLink to="/loans?status=ACTIVE">{{ overview.activeLoans }}</RouterLink></dd></div>
        <div :class="{ hot: overview.overdueLoans }"><dt>逾期</dt><dd class="num">{{ overview.overdueLoans }}</dd></div>
      </dl>

      <section class="chart-box">
        <div class="ch-head">
          <h2 class="h">近 30 天借出册数</h2>
          <p class="muted small num">合计 {{ total }} 册</p>
        </div>
        <div class="chart-scroll">
          <svg :viewBox="`0 0 ${W} ${H}`" class="chart" role="img" :aria-label="`近30天借出共${total}册`" @mouseleave="hover = null">
            <g v-for="t in ticks" :key="t">
              <line :x1="PAD.l" :x2="W - PAD.r" :y1="y(t)" :y2="y(t)" class="grid" />
              <text :x="PAD.l - 8" :y="y(t) + 4" class="tick" text-anchor="end">{{ t }}</text>
            </g>
            <g v-for="(d, i) in days" :key="d.date" @mouseenter="hover = i">
              <rect :x="PAD.l + i * bw" :y="PAD.t" :width="bw" :height="H - PAD.t - PAD.b" fill="transparent" />
              <rect
                v-if="d.count"
                :x="PAD.l + i * bw + bw * 0.18"
                :y="y(d.count)"
                :width="bw * 0.64"
                :height="y(0) - y(d.count)"
                rx="2"
                class="bar"
                :class="{ on: hover === i }"
              />
              <text v-if="i % 5 === 4 || i === days.length - 1" :x="PAD.l + i * bw + bw / 2" :y="H - 8" class="tick" text-anchor="middle">{{ d.date.slice(5) }}</text>
            </g>
            <g v-if="hover !== null">
              <text :x="Math.min(W - 70, Math.max(PAD.l + 40, PAD.l + hover * bw + bw / 2))" :y="Math.max(22, y(days[hover].count) - 8)" class="tip" text-anchor="middle">
                {{ days[hover].date.slice(5) }} · {{ days[hover].count }} 册
              </text>
            </g>
          </svg>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  margin: 0 0 24px;
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  background: var(--surface);
}
.strip > div {
  padding: 14px 16px;
}
.strip > div + div {
  border-left: 1px solid var(--rule);
}
.strip dt {
  font-size: var(--t-xs);
  color: var(--ink-3);
}
.strip dd {
  margin: 4px 0 0;
  font-size: 22px;
  font-weight: 600;
}
.strip small {
  font-size: var(--t-sm);
  font-weight: 400;
  color: var(--ink-3);
}
.strip .hot dd {
  color: var(--danger);
}
.chart-box {
  padding: 16px 18px;
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  background: var(--surface);
  max-width: 820px;
}
.ch-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}
.h {
  font-size: var(--t-md);
}
.chart-scroll {
  overflow-x: auto;
}
.chart {
  width: 100%;
  min-width: 520px;
  height: auto;
}
.grid {
  stroke: var(--rule);
  stroke-width: 1;
}
.tick {
  fill: var(--ink-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.bar {
  fill: var(--accent);
  opacity: 0.85;
}
.bar.on {
  opacity: 1;
}
.tip {
  fill: var(--ink);
  font-size: 12px;
  font-weight: 600;
}
@media (max-width: 860px) {
  .strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .strip > div:nth-child(4) {
    border-left: 0;
  }
  .strip > div:nth-child(n + 4) {
    border-top: 1px solid var(--rule);
  }
}
</style>
