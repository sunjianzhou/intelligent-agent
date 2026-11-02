import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from '@/router'
import App from '@/App.vue'

// Font Awesome 图标（本地打包，无需 CDN，避免境内代理拦截）
import '@fortawesome/fontawesome-free/css/all.min.css'

// Element Plus 样式（仅角色配置页使用 El 组件，需全局注册 CSS）
import 'element-plus/dist/index.css'

// 创建Pinia实例
const pinia = createPinia()

// 创建Vue应用
const app = createApp(App)

// 主题必须在挂载前落到 <html>：
// 之前只有 Header 的 onMounted 里设置，导致深色用户每次加载都会先闪一下浅色，
// 且登录页（不渲染 Header）永远停在浅色。
const savedTheme = localStorage.getItem('theme') === 'dark' ? 'dark' : 'light'
document.documentElement.setAttribute('data-theme', savedTheme)

// 使用插件
app.use(pinia)
app.use(router)

// 挂载应用
app.mount('#app')

// 注册 Service Worker（PWA）
//
// 注意：开发环境必须不注册。
// sw.js 对「非哈希静态资源」走 stale-while-revalidate，而 Vite dev server 提供的
// /src/** 模块与 /src/styles/main.css 都不带哈希 → 浏览器会先拿到上一份缓存，
// 表现为「改了代码/样式刷新还是旧的」，需要手动注销 SW 才能看到最新效果。
// 这里在 dev 下不仅跳过注册，还会主动注销历史上注册过的 SW 并清空缓存。
if (import.meta.env.DEV && 'serviceWorker' in navigator) {
  window.addEventListener('load', async () => {
    const regs = await navigator.serviceWorker.getRegistrations()
    await Promise.all(regs.map((r) => r.unregister()))
    const keys = await caches.keys()
    await Promise.all(keys.map((k) => caches.delete(k)))
    if (regs.length) console.log('[PWA] 开发环境已注销 Service Worker，避免静态资源被缓存')
  })
} else if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/sw.js')
      .then((reg) => {
        console.log('[PWA] Service Worker 注册成功:', reg.scope)
        // 检测到新版本时提示用户刷新
        reg.addEventListener('updatefound', () => {
          const newWorker = reg.installing
          newWorker?.addEventListener('statechange', () => {
            if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
              console.log('[PWA] 发现新版本，刷新页面以更新')
            }
          })
        })
      })
      .catch((err) => console.warn('[PWA] Service Worker 注册失败:', err))
  })
}
