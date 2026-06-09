import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局错误总线。
 * api.js 调用 push() 发布错误；App.vue 或任何视图可以监听 errors 列表。
 * ElMessage toast 由 api.js 直接触发（不依赖组件上下文）。
 */
export const useErrorBusStore = defineStore('errorBus', () => {
  // 最近 20 条错误记录（供调试面板等使用）
  const errors = ref([])

  function push(message, type = 'error', url = '') {
    errors.value.push({
      id:        Date.now(),
      message,
      type,
      url,
      timestamp: new Date().toLocaleTimeString('zh-CN'),
    })
    if (errors.value.length > 20) errors.value.shift()
  }

  function clear() {
    errors.value = []
  }

  return { errors, push, clear }
})
