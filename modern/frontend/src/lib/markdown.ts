// 模型输出与书目文本都是不可信内容：markdown-it 关闭 HTML，再经 DOMPurify 消毒；链接只允许 http/https。
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
md.validateLink = (url: string) => /^https?:\/\//i.test(url.trim())

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    const href = node.getAttribute('href') ?? ''
    if (!/^https?:\/\//i.test(href)) node.removeAttribute('href')
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer nofollow')
  }
})

export function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(md.render(text ?? ''), {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'code', 'pre', 'ul', 'ol', 'li', 'blockquote', 'a', 'h3', 'h4', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'sup'],
    ALLOWED_ATTR: ['href', 'target', 'rel'],
  })
}
