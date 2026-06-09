import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from '@/router'
import App from '@/App.vue'

// Font Awesome 图标（本地打包，无需 CDN，避免境内代理拦截）
import '@fortawesome/fontawesome-free/css/all.min.css'

// 创建Pinia实例
const pinia = createPinia()

// 创建Vue应用
const app = createApp(App)

// 使用插件
app.use(pinia)
app.use(router)

// 挂载应用
app.mount('#app')

// 注册 Service Worker（PWA）
if ('serviceWorker' in navigator) {
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