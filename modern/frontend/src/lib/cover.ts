// 没有封面图时生成排印封面：颜色由 ISBN/书名稳定哈希决定，同一本书每次一致。
const PALETTE = [
  { bg: '#2f4a3f', fg: '#efe7d6', rule: '#c9b98f' },
  { bg: '#6b2e2a', fg: '#f3e6d8', rule: '#d8b48a' },
  { bg: '#253b57', fg: '#e9e4d8', rule: '#b9a77a' },
  { bg: '#e9e1cf', fg: '#2a2620', rule: '#9a3b2f' },
  { bg: '#4b3b2f', fg: '#efe3cc', rule: '#c79b5a' },
  { bg: '#d9d2bf', fg: '#203a33', rule: '#203a33' },
  { bg: '#38404a', fg: '#ece6d9', rule: '#a6b5a0' },
  { bg: '#7a5a2c', fg: '#f6ecd9', rule: '#f6ecd9' },
]

function hash(s: string) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

export function coverTheme(seed: string) {
  return PALETTE[hash(seed || 'x') % PALETTE.length]
}

export function hasRealCover(url?: string | null) {
  return !!url && !url.endsWith('cover-missing.svg')
}
