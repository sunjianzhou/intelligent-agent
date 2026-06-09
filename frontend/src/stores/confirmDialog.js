import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局确认对话框状态。
 *
 * 不用 window.confirm()：原生对话框在 PWA / WebView / 已勾选“阻止此网页
 * 创建更多对话框”的浏览器中会被静默拦截，返回 false 且不显示任何 UI——
 * 用户点击删除/取消按钮时表现为“毫无反应”。改用纯 Vue 渲染的模态框，
 * 渲染路径与页面内其它弹窗（新建/编辑任务等）完全一致，不依赖浏览器能力。
 */
export const useConfirmDialogStore = defineStore('confirmDialog', () => {
  const visible      = ref(false)
  const title        = ref('确认')
  const message      = ref('')
  const confirmText  = ref('确定')
  const cancelText   = ref('取消')
  const danger       = ref(false)
  let _resolve = null

  function confirm(msg, opts = {}) {
    title.value       = opts.title || '确认'
    message.value     = msg
    confirmText.value = opts.confirmText || '确定'
    cancelText.value  = opts.cancelText || '取消'
    danger.value      = !!opts.danger
    visible.value     = true
    return new Promise((resolve) => { _resolve = resolve })
  }

  function _settle(result) {
    visible.value = false
    if (_resolve) { _resolve(result); _resolve = null }
  }

  const onConfirm = () => _settle(true)
  const onCancel  = () => _settle(false)

  return { visible, title, message, confirmText, cancelText, danger, confirm, onConfirm, onCancel }
})
