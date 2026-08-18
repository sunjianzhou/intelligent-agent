import { marked } from 'marked'
import hljs from 'highlight.js/lib/core'
import 'highlight.js/styles/github.css'
import DOMPurify from 'dompurify'

// 按需注册常用语言（替代全量 import，大幅减小 chunk）
import langJs   from 'highlight.js/lib/languages/javascript'
import langTs   from 'highlight.js/lib/languages/typescript'
import langPy   from 'highlight.js/lib/languages/python'
import langJava from 'highlight.js/lib/languages/java'
import langBash from 'highlight.js/lib/languages/bash'
import langSql  from 'highlight.js/lib/languages/sql'
import langJson from 'highlight.js/lib/languages/json'
import langXml  from 'highlight.js/lib/languages/xml'
import langCss  from 'highlight.js/lib/languages/css'
import langGo   from 'highlight.js/lib/languages/go'
import langRust from 'highlight.js/lib/languages/rust'
import langCpp  from 'highlight.js/lib/languages/cpp'
import langYaml from 'highlight.js/lib/languages/yaml'
import langMd   from 'highlight.js/lib/languages/markdown'

hljs.registerLanguage('javascript', langJs);  hljs.registerLanguage('js', langJs)
hljs.registerLanguage('typescript', langTs);  hljs.registerLanguage('ts', langTs)
hljs.registerLanguage('python', langPy);      hljs.registerLanguage('py', langPy)
hljs.registerLanguage('java', langJava)
hljs.registerLanguage('bash', langBash);      hljs.registerLanguage('sh', langBash)
hljs.registerLanguage('shell', langBash)
hljs.registerLanguage('sql', langSql)
hljs.registerLanguage('json', langJson)
hljs.registerLanguage('xml', langXml);        hljs.registerLanguage('html', langXml)
hljs.registerLanguage('css', langCss)
hljs.registerLanguage('go', langGo)
hljs.registerLanguage('rust', langRust)
hljs.registerLanguage('cpp', langCpp);        hljs.registerLanguage('c', langCpp)
hljs.registerLanguage('yaml', langYaml);      hljs.registerLanguage('yml', langYaml)
hljs.registerLanguage('markdown', langMd);    hljs.registerLanguage('md', langMd)

marked.setOptions({
  highlight: (code, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  },
  breaks: true,
  gfm: true,
})

const _MD_ALLOWED = {
  ALLOWED_TAGS: ['p','br','strong','em','code','pre','blockquote','ul','ol','li',
                 'h1','h2','h3','h4','h5','h6','a','img','span','div','table',
                 'thead','tbody','tr','th','td','mark'],
  ALLOWED_ATTR: ['href','src','alt','class','title','target'],
}

// 缓存已完成消息的渲染结果，避免每次 Vue 重渲染重跑 parse（流式消息不缓存）
const _mdCache = new Map()

export function renderMarkdown(text, streaming = false) {
  if (!text) return ''
  // 流式进行时跳过 marked.parse（O(n) per token），只做安全转义显示原始文本
  if (streaming) {
    return DOMPurify.sanitize(text.replace(/</g, '&lt;').replace(/>/g, '&gt;'), _MD_ALLOWED)
  }
  if (_mdCache.has(text)) return _mdCache.get(text)
  const html = DOMPurify.sanitize(marked.parse(text), _MD_ALLOWED)
  if (_mdCache.size > 300) _mdCache.delete(_mdCache.keys().next().value) // LRU 上限
  _mdCache.set(text, html)
  return html
}

// 转义 HTML 特殊字符防 XSS，再插入高亮标记
const escapeHtml = (s) => s
  .replace(/&/g, '&amp;').replace(/</g, '&lt;')
  .replace(/>/g, '&gt;').replace(/"/g, '&quot;')

export function highlightSearch(text, keyword) {
  if (!keyword || !text) return escapeHtml(text || '')
  const kw = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const escaped = escapeHtml(text)
  const kwEscaped = escapeHtml(keyword).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return escaped.replace(new RegExp(kwEscaped, 'gi'),
    m => `<mark class="search-hl">${m}</mark>`)
}
