import { vi } from 'vitest'
// jsdom 不带 IndexedDB（角色本地存储 roleStorage.js 依赖）
import 'fake-indexeddb/auto'

// jsdom 缺失的浏览器 API 兜底（Element Plus / 响应式组件依赖）
if (!window.matchMedia) {
  vi.stubGlobal('matchMedia', (query) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }))
}

if (!window.ResizeObserver) {
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
  })
}

if (!window.IntersectionObserver) {
  vi.stubGlobal('IntersectionObserver', class {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() { return [] }
  })
}

if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

if (!URL.createObjectURL) {
  URL.createObjectURL = () => 'blob:mock'
  URL.revokeObjectURL = () => {}
}
