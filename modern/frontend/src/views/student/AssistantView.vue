<script setup lang="ts">
// AI 助手：POST fetch 读取 SSE；delta 只是未完成输出，result 才是最终内容；
// error 或没有 done 都算不完整。确认卡只能由按钮执行，断线后从历史和 actions 恢复。
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import {
  ArrowUp, Square, Plus, MessagesSquare, Trash2, Wrench, ChevronDown, CircleAlert, History, PanelLeft,
} from 'lucide-vue-next'
import { ai } from '@/api/endpoints'
import { ApiError, errorMessage } from '@/api/http'
import { postStream } from '@/api/sse'
import type { ActionView, Citation, Conversation, Reply, ToolTrace } from '@/api/types'
import { errorHints, fmtDateTime } from '@/lib/format'
import { renderMarkdown } from '@/lib/markdown'
import { cachedBook, loadBook } from '@/stores/bookCache'
import { toast } from '@/stores/toast'
import ActionCard from '@/components/ActionCard.vue'
import BookCover from '@/components/BookCover.vue'
import Modal from '@/components/Modal.vue'

type Status = 'done' | 'streaming' | 'error' | 'interrupted' | 'stopped'
interface UiMessage {
  key: string
  role: 'user' | 'assistant'
  content: string
  status: Status
  citations: Citation[]
  editionReferences: number[]
  actions: ActionView[]
  tools: ToolTrace[]
  mode: string
  model: string
  errorCode?: string
  errorMessage?: string
  requestId?: string
  createdAt?: string
}

const route = useRoute()
const router = useRouter()
const conversations = ref<Conversation[]>([])
const activeId = ref<number | null>(route.params.id ? Number(route.params.id) : null)
const messages = ref<UiMessage[]>([])
const orphanActions = ref<ActionView[]>([])
const loadingHistory = ref(false)
const historyError = ref<unknown>(null)
const draft = ref('')
const sending = ref(false)
const listOpen = ref(false)
const scroller = ref<HTMLElement>()
const composer = ref<HTMLTextAreaElement>()
let abort: AbortController | null = null
let seq = 0

// 历史卡片中去掉已在某条回复里展示的，避免同一张卡出现两次。
const historyCards = computed(() => {
  const shown = new Set(messages.value.flatMap((m) => m.actions.map((a) => a.id)))
  return orphanActions.value.filter((a) => !shown.has(a.id))
})
const streamingMessage = computed(() => messages.value.find((m) => m.status === 'streaming'))
const liveMode = computed(() => [...messages.value].reverse().find((m) => m.mode)?.mode ?? '')

async function loadConversations() {
  try {
    conversations.value = await ai.conversations()
  } catch {
    /* 列表失败不阻止当前对话 */
  }
}

function fromReply(r: Partial<Reply>, base: UiMessage) {
  base.content = r.content ?? base.content
  base.citations = r.citations ?? []
  base.editionReferences = r.editionReferences ?? []
  base.actions = r.actions ?? []
  base.tools = r.tools ?? base.tools
  base.mode = r.mode ?? base.mode
  base.editionReferences.forEach((id) => loadBook(id, true))
}

async function openConversation(id: number | null) {
  abort?.abort()
  activeId.value = id
  messages.value = []
  orphanActions.value = []
  historyError.value = null
  listOpen.value = false
  if (route.params.id !== String(id ?? '')) router.replace(id ? `/assistant/${id}` : '/assistant')
  if (!id) return
  loadingHistory.value = true
  try {
    const [history, actions] = await Promise.all([ai.messages(id), ai.actions(id)])
    messages.value = history.map((m) => ({
      key: `h${m.id}`,
      role: m.role === 'USER' || m.role === 'user' ? 'user' : 'assistant',
      content: m.content,
      status: 'done',
      citations: m.citations ?? [],
      editionReferences: m.editionReferences ?? [],
      actions: [],
      tools: [],
      mode: '',
      model: m.model ?? '',
      createdAt: m.createdAt,
    }))
    messages.value.forEach((m) => m.editionReferences.forEach((b) => loadBook(b, true)))
    // 历史消息不携带卡片关联；会话里的卡片统一列在末尾，状态以服务端为准。
    orphanActions.value = actions
  } catch (e) {
    historyError.value = e
  } finally {
    loadingHistory.value = false
    scrollDown()
  }
}

async function ensureConversation(firstText: string) {
  if (activeId.value) return activeId.value
  const c = await ai.createConversation(firstText.slice(0, 30))
  conversations.value.unshift(c)
  activeId.value = c.id
  router.replace(`/assistant/${c.id}`)
  return c.id
}

async function send(textArg?: string) {
  const text = (textArg ?? draft.value).trim()
  if (!text || sending.value) return
  sending.value = true
  let id: number
  try {
    id = await ensureConversation(text)
  } catch (e) {
    toast('danger', '无法创建对话', errorMessage(e))
    sending.value = false
    return
  }
  draft.value = ''
  const user: UiMessage = { key: `u${++seq}`, role: 'user', content: text, status: 'done', citations: [], editionReferences: [], actions: [], tools: [], mode: '', model: '' }
  messages.value.push(user)
  messages.value.push({ key: `a${++seq}`, role: 'assistant', content: '', status: 'streaming', citations: [], editionReferences: [], actions: [], tools: [], mode: '', model: '' })
  const reply = messages.value[messages.value.length - 1]
  scrollDown()

  abort = new AbortController()
  try {
    const end = await postStream(
      ai.streamPath(id),
      { content: text },
      {
        onEvent(name, data) {
          const d = data as Record<string, unknown>
          if (name === 'meta') {
            reply.mode = String(d.mode ?? '')
            reply.model = String(d.model ?? '')
          } else if (name === 'tool') {
            reply.tools.push(d as unknown as ToolTrace)
          } else if (name === 'delta') {
            reply.content += String(d.text ?? '')
            scrollDown(true)
          } else if (name === 'result') {
            fromReply(d as Partial<Reply>, reply)
          }
        },
      },
      abort.signal,
    )
    if (end.kind === 'done') reply.status = 'done'
    else if (end.kind === 'error') {
      reply.status = 'error'
      reply.errorCode = end.code
      reply.errorMessage = end.message
      reply.requestId = end.requestId
    } else if (end.kind === 'interrupted') {
      reply.status = 'interrupted'
      reply.errorMessage = end.message
    } else reply.status = 'stopped'
  } catch (e) {
    reply.status = 'error'
    if (e instanceof ApiError) {
      reply.errorCode = e.code
      reply.errorMessage = errorHints[e.code] ?? e.message
      reply.requestId = e.requestId
    } else reply.errorMessage = errorMessage(e)
  } finally {
    sending.value = false
    abort = null
    scrollDown()
    loadConversations()
    nextTick(() => composer.value?.focus())
  }
}

function stop() {
  abort?.abort()
}

async function recover() {
  // 后端在一轮结束后保存消息；断线后以服务端历史和卡片状态为准。
  if (activeId.value) await openConversation(activeId.value)
}

async function archive(c: Conversation) {
  try {
    await ai.archive(c.id)
    conversations.value = conversations.value.filter((x) => x.id !== c.id)
    if (activeId.value === c.id) openConversation(null)
    toast('info', '对话已归档', '其中未确认的操作卡已取消')
  } catch (e) {
    toast('danger', '归档失败', errorMessage(e))
  }
}

function scrollDown(onlyIfNear = false) {
  nextTick(() => {
    const el = scroller.value
    if (!el) {
      window.scrollTo({ top: document.body.scrollHeight })
      return
    }
    if (onlyIfNear && el.scrollHeight - el.scrollTop - el.clientHeight > 160) return
    el.scrollTop = el.scrollHeight
  })
}

function onComposerKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}
function autosize() {
  const el = composer.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(160, el.scrollHeight) + 'px'
}
watch(draft, () => nextTick(autosize))

const examples = ['有没有讲 UNIX 系统编程的书？', '我借的书什么时候到期？', '图书馆的借阅规则是什么？']

watch(
  () => route.params.id,
  (id) => {
    const n = id ? Number(id) : null
    if (n !== activeId.value) openConversation(n)
  },
)
loadConversations()
if (activeId.value) openConversation(activeId.value)
onBeforeUnmount(() => abort?.abort())

function citeHtml(m: UiMessage) {
  return renderMarkdown(m.content)
}
</script>

<template>
  <div class="assist">
    <aside class="convs" aria-label="对话列表">
      <button class="btn new" type="button" @click="openConversation(null)"><Plus />新对话</button>
      <p class="section-title list-t">最近</p>
      <ul>
        <li v-for="c in conversations" :key="c.id" :class="{ on: c.id === activeId }">
          <button class="ci" type="button" @click="openConversation(c.id)">
            <span class="truncate">{{ c.title || '未命名对话' }}</span>
            <span class="muted xs num">{{ fmtDateTime(c.updatedAt).slice(5) }}</span>
          </button>
          <button class="btn btn-quiet btn-sm btn-icon del" type="button" :aria-label="`归档 ${c.title}`" title="归档" @click="archive(c)"><Trash2 /></button>
        </li>
        <li v-if="!conversations.length" class="muted xs empty">还没有对话</li>
      </ul>
    </aside>

    <section class="chat">
      <header class="chat-head">
        <button class="btn btn-quiet btn-icon only-mobile" type="button" aria-label="对话列表" @click="listOpen = true"><PanelLeft /></button>
        <h1 class="ct">AI 助手</h1>
        <span v-if="liveMode === 'replay'" class="badge warn">回放模式 · 非实时回答</span>
        <span v-else-if="liveMode === 'live'" class="badge accent plain">实时模型</span>
        <button class="btn btn-quiet btn-sm only-mobile" type="button" @click="openConversation(null)"><Plus />新对话</button>
      </header>

      <div ref="scroller" class="stream" aria-live="polite">
        <div v-if="!activeId && !messages.length" class="intro">
          <MessagesSquare class="ii" aria-hidden="true" />
          <p class="serif it">查书、问规则、办续借</p>
          <p class="muted small">助手可以查询馆藏和你的借阅，并为借阅、续借、归还准备确认卡。真正执行只在你点“确认”之后。</p>
          <div class="ex">
            <button v-for="e in examples" :key="e" class="btn btn-sm" type="button" @click="send(e)">{{ e }}</button>
          </div>
        </div>
        <p v-if="loadingHistory" class="muted small center">正在载入对话…</p>
        <div v-if="historyError" class="msg-error"><CircleAlert />{{ errorMessage(historyError) }}</div>

        <template v-for="m in messages" :key="m.key">
          <div v-if="m.role === 'user'" class="u-msg"><p>{{ m.content }}</p></div>
          <div v-else class="a-msg" :class="`st-${m.status}`">
            <details v-if="m.tools.length" class="tools">
              <summary><Wrench aria-hidden="true" />查询了 {{ m.tools.length }} 项<ChevronDown class="chev" aria-hidden="true" /></summary>
              <ul>
                <li v-for="(t, i) in m.tools" :key="i" class="mono xs">
                  <span>{{ t.name }}</span>
                  <span :class="t.status === 'OK' || t.status === 'SUCCEEDED' ? 'ok' : 'muted'">{{ t.code || t.status }}</span>
                  <span class="muted">{{ t.latencyMs }} ms</span>
                </li>
              </ul>
            </details>

            <!-- 生成中：纯文本 + 光标；完成后渲染消毒过的 Markdown -->
            <p v-if="m.status === 'streaming'" class="draft">{{ m.content }}<span class="caret" aria-hidden="true"></span></p>
            <!-- eslint-disable-next-line vue/no-v-html -->
            <div v-else-if="m.content && m.status === 'done'" class="md" v-html="citeHtml(m)"></div>
            <p v-else-if="m.content" class="draft partial">{{ m.content }}</p>

            <div v-if="m.status === 'streaming' && !m.content" class="thinking muted small">正在查询…</div>

            <div v-if="m.status === 'error' || m.status === 'interrupted' || m.status === 'stopped'" class="msg-error" role="alert">
              <CircleAlert aria-hidden="true" />
              <div>
                <p>
                  <template v-if="m.status === 'stopped'">已停止，本轮回答不完整。</template>
                  <template v-else-if="m.status === 'interrupted'">{{ m.errorMessage }}</template>
                  <template v-else>{{ errorHints[m.errorCode ?? ''] ?? m.errorMessage ?? '本轮回答未完成' }}</template>
                </p>
                <p v-if="m.errorCode || m.requestId" class="mono xs muted">{{ m.errorCode }}<template v-if="m.requestId"> · 请求 {{ m.requestId }}</template></p>
              </div>
              <button v-if="m.status !== 'error'" class="btn btn-sm" type="button" @click="recover"><History />从服务器恢复</button>
            </div>

            <div v-if="m.editionReferences.length" class="refs">
              <RouterLink v-for="id in m.editionReferences" :key="id" :to="`/book/${id}`" class="ref">
                <BookCover :title="cachedBook(id)?.title ?? '…'" :authors="cachedBook(id)?.authors" :cover-url="cachedBook(id)?.coverUrl" :seed="cachedBook(id)?.isbn13" :width="40" />
                <span class="rb">
                  <span class="serif truncate rt">{{ cachedBook(id)?.title ?? '载入中' }}</span>
                  <span v-if="cachedBook(id)" class="xs" :class="cachedBook(id)!.availableCount > 0 ? 'ok' : 'muted'">
                    {{ cachedBook(id)!.availableCount > 0 ? `现有 ${cachedBook(id)!.availableCount} 册可借` : '当前全部借出' }}
                  </span>
                </span>
              </RouterLink>
            </div>

            <ol v-if="m.citations.length" class="cites">
              <li v-for="c in m.citations" :key="c.id">
                <b class="mono">[{{ c.id }}]</b> {{ c.title }}
                <span class="muted">{{ c.excerpt }}</span>
                <a v-if="/^https?:\/\//.test(c.sourceUrl)" :href="c.sourceUrl" target="_blank" rel="noopener noreferrer">来源</a>
              </li>
            </ol>

            <div v-if="m.actions.length" class="cards">
              <ActionCard v-for="a in m.actions" :key="a.id" :action="a" />
            </div>
          </div>
        </template>

        <div v-if="historyCards.length" class="cards hist-cards">
          <p class="section-title">本对话中的操作卡</p>
          <ActionCard v-for="a in historyCards" :key="a.id" :action="a" />
        </div>
      </div>

      <form class="composer" @submit.prevent="send()">
        <textarea
          ref="composer"
          v-model="draft"
          rows="1"
          maxlength="2000"
          placeholder="问问助手…（Enter 发送，Shift+Enter 换行）"
          aria-label="输入消息"
          :disabled="sending"
          @keydown="onComposerKey"
        ></textarea>
        <button v-if="streamingMessage" class="btn btn-icon send stop" type="button" aria-label="停止生成" @click="stop"><Square /></button>
        <button v-else class="btn btn-primary btn-icon send" type="submit" aria-label="发送" :disabled="!draft.trim() || sending"><ArrowUp /></button>
      </form>
      <p class="disclaimer xs muted">回答可能有误；库存和应还日期以页面实时数据为准。说“确认”不会执行操作，请点卡片上的按钮。</p>
    </section>

    <Modal :open="listOpen" title="对话" side @close="listOpen = false">
      <ul class="m-list">
        <li v-for="c in conversations" :key="c.id">
          <button class="ci" type="button" @click="openConversation(c.id)">
            <span class="truncate">{{ c.title || '未命名对话' }}</span>
            <span class="muted xs">{{ fmtDateTime(c.updatedAt) }}</span>
          </button>
        </li>
        <li v-if="!conversations.length" class="muted small">还没有对话</li>
      </ul>
    </Modal>
  </div>
</template>

<style scoped>
.assist {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 24px;
  height: calc(100vh - 56px);
  height: calc(100dvh - 56px);
  padding-top: 16px;
}
.convs {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding-bottom: 16px;
}
.new {
  justify-content: flex-start;
}
.list-t {
  margin: 18px 4px 6px;
}
.convs ul {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
}
.convs li {
  display: flex;
  align-items: center;
  border-radius: var(--r-2);
}
.convs li.on {
  background: var(--paper-2);
}
.convs li:hover .del {
  opacity: 1;
}
.ci {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 7px 8px;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
  color: var(--ink);
  border-radius: var(--r-2);
}
.ci .truncate {
  max-width: 100%;
}
.del {
  opacity: 0;
}
.del:focus-visible {
  opacity: 1;
}
.empty {
  padding: 8px;
}
.chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.chat-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--rule);
}
.ct {
  font-size: var(--t-lg);
}
.only-mobile {
  display: none;
}
.stream {
  flex: 1;
  overflow-y: auto;
  padding: 20px 2px 12px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.intro {
  margin: auto;
  max-width: 440px;
  text-align: center;
  padding: 24px 8px;
}
.ii {
  width: 30px;
  height: 30px;
  color: var(--accent);
  margin: 0 auto 10px;
}
.it {
  font-size: 22px;
  margin-bottom: 6px;
}
.ex {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
}
.u-msg {
  align-self: flex-end;
  max-width: 78%;
  padding: 9px 14px;
  border-radius: 14px 14px 4px 14px;
  background: var(--accent);
  color: var(--accent-ink);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.a-msg {
  max-width: 720px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.draft {
  white-space: pre-wrap;
  line-height: 1.8;
}
.draft.partial {
  color: var(--ink-2);
  border-left: 3px solid var(--warn);
  padding-left: 10px;
}
.caret {
  display: inline-block;
  width: 7px;
  height: 1.1em;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--accent);
  animation: blink 1s steps(2) infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.md {
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.md :deep(p + p) {
  margin-top: 0.7em;
}
.md :deep(ul),
.md :deep(ol) {
  padding-left: 1.4em;
  margin: 0.4em 0;
}
.md :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.9em;
  background: var(--paper-2);
  padding: 1px 4px;
  border-radius: 3px;
}
.tools {
  font-size: var(--t-sm);
  color: var(--ink-3);
}
.tools summary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  list-style: none;
}
.tools summary::-webkit-details-marker {
  display: none;
}
.tools summary svg {
  width: 14px;
  height: 14px;
}
.tools[open] .chev {
  transform: rotate(180deg);
}
.tools ul {
  list-style: none;
  padding: 8px 10px;
  margin: 6px 0 0;
  border-radius: var(--r-2);
  background: var(--surface-sunk);
  border: 1px solid var(--rule);
}
.tools li {
  display: flex;
  gap: 12px;
}
.ok {
  color: var(--ok);
}
.thinking::before {
  content: "";
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 8px;
  border-radius: 50%;
  background: var(--accent);
  animation: blink 1s infinite;
}
.msg-error {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--r-2);
  background: var(--danger-soft);
  color: var(--ink);
  font-size: var(--t-sm);
}
.st-interrupted .msg-error,
.st-stopped .msg-error {
  background: var(--warn-soft);
}
.msg-error > svg {
  width: 16px;
  height: 16px;
  flex: none;
  margin-top: 2px;
  color: var(--danger);
}
.msg-error .btn {
  margin-left: auto;
  flex: none;
}
.refs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ref {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px 8px 8px;
  border: 1px solid var(--rule);
  border-radius: var(--r-3);
  background: var(--surface);
  max-width: 300px;
  color: var(--ink);
  text-decoration: none !important;
}
.ref:hover {
  border-color: var(--rule-strong);
}
.rb {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.rt {
  font-weight: 600;
}
.cites {
  list-style: none;
  margin: 0;
  padding: 8px 0 0;
  border-top: 1px dashed var(--rule-strong);
  font-size: var(--t-sm);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.hist-cards {
  padding-top: 12px;
  border-top: 1px solid var(--rule);
}
.composer {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 8px 8px 8px 14px;
  border: 1px solid var(--rule-strong);
  border-radius: 16px;
  background: var(--surface);
  box-shadow: var(--shadow-1);
}
.composer:focus-within {
  border-color: var(--accent);
  box-shadow: var(--focus);
}
.composer textarea {
  flex: 1;
  border: 0;
  outline: 0;
  resize: none;
  background: none;
  padding: 7px 0;
  line-height: 1.5;
  max-height: 160px;
}
.composer textarea:focus-visible {
  box-shadow: none;
}
.send {
  border-radius: 50%;
  flex: none;
}
.stop {
  background: var(--ink);
  color: var(--paper);
  border-color: var(--ink);
}
.stop svg {
  width: 12px;
  height: 12px;
  fill: currentColor;
}
.disclaimer {
  text-align: center;
  padding: 8px 0 14px;
}
.small {
  font-size: var(--t-sm);
}
.xs {
  font-size: var(--t-xs);
}
.center {
  text-align: center;
}
.m-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
@media (max-width: 760px) {
  .assist {
    grid-template-columns: 1fr;
    height: calc(100dvh - 50px - 72px - env(safe-area-inset-bottom));
    padding-top: 8px;
  }
  .convs {
    display: none;
  }
  .only-mobile {
    display: inline-flex;
  }
  .chat-head .ct {
    flex: 1;
  }
  .u-msg {
    max-width: 88%;
  }
  .disclaimer {
    padding-bottom: 4px;
  }
}
</style>
