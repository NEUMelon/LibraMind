// 书目短时缓存：只用于封面与书名展示；库存等实时事实仍以接口返回为准。
import { reactive } from 'vue'
import { books } from '@/api/endpoints'
import type { BookView } from '@/api/types'
import { onSessionReset } from './session'

const cache = reactive(new Map<number, BookView>())
const inflight = new Map<number, Promise<BookView | null>>()

export function cachedBook(id: number) {
  return cache.get(id) ?? null
}

export function loadBook(id: number, fresh = false): Promise<BookView | null> {
  if (!fresh && cache.has(id)) return Promise.resolve(cache.get(id)!)
  if (!inflight.has(id)) {
    inflight.set(
      id,
      books
        .get(id)
        .then((b) => {
          cache.set(id, b)
          return b
        })
        .catch(() => null)
        .finally(() => inflight.delete(id)),
    )
  }
  return inflight.get(id)!
}

onSessionReset(() => cache.clear())
