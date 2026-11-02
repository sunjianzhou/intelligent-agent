<template>
  <div class="input-area">
    <!-- 移动端：角色/模型选择徽章（点击弹出底部抽屉） -->
    <div class="mobile-config-chips">
      <button
        class="mobile-chip mobile-chip-role"
        :aria-label="`当前角色：${activeRoleName}`"
        @click="emit('open-role-model-sheet')"
      >
        <i class="fas fa-id-card" aria-hidden="true" />
        <span>{{ activeRoleName }}</span>
      </button>
      <button
        class="mobile-chip mobile-chip-model"
        :aria-label="`当前模型：${currentModel || '默认'}`"
        @click="emit('open-role-model-sheet')"
      >
        <i class="fas fa-robot" aria-hidden="true" />
        <span>{{ currentModel || '默认' }}</span>
      </button>
    </div>
    <!-- 图片附件预览 -->
    <div v-if="attachedImagePreview" class="attached-img-row">
      <img :src="attachedImagePreview" class="attached-thumb" />
      <button class="attached-remove" @click="clearAttachedImage" title="移除图片">
        <i class="fas fa-times" />
      </button>
    </div>
    <div class="input-wrap" :class="{ 'input-wrap-thinking': isThinking || isStreaming, 'input-wrap-disconnected': !isConnected }">
      <textarea
        ref="inputRef"
        v-model="inputText"
        class="chat-input"
        :placeholder="isThinking ? '正在思考，请稍候...' : isStreaming ? '正在生成回答...' : !isConnected ? '未连接到服务器' : '输入消息，Enter 发送，Shift+Enter 换行...'"
        :disabled="!isConnected || isThinking || isStreaming"
        rows="1"
        @keydown="handleKeydown"
        @input="autoResize"
        @paste="onPasteImage"
      />
      <!-- 附图按钮 -->
      <label class="attach-btn" title="附上图片（支持粘贴）"
             :class="{ 'attach-active': attachedImagePreview, 'attach-loading': isReadingImage }"
             :style="isReadingImage ? 'pointer-events:none;opacity:0.5' : ''">
        <i :class="isReadingImage ? 'fas fa-circle-notch fa-spin' : 'fas fa-image'" />
        <input type="file" accept="image/*" style="display:none" :disabled="isReadingImage" @change="onAttachImageFile" />
      </label>
      <!-- 停止生成按钮（流式输出时显示）+ 脉冲动画 -->
      <button v-if="isStreaming || isThinking" class="stop-btn stop-btn-pulse" title="点击停止生成" @click="emit('cancel-stream')">
        <i class="fas fa-stop" />
      </button>
        <button v-else class="send-btn" :disabled="!canSend" aria-label="发送消息" title="发送消息" @click="trySend">
          <i class="fas fa-paper-plane"></i>
        </button>
    </div>
    <div class="input-meta">
      <span v-if="!isConnected" class="hint warn">
        <i class="fas fa-exclamation-circle"></i> 未连接，请检查后端服务
      </span>
      <span v-else-if="isThinking" class="hint">
        <i class="fas fa-circle-notch fa-spin"></i> 正在思考...
      </span>
      <span v-else-if="isStreaming" class="hint">
        <i class="fas fa-circle-notch fa-spin"></i> 正在生成...
        <span class="hint-tip">（点击右侧停止按钮可中止）</span>
      </span>
      <span v-else class="hint">
        已连接 · {{ modelStatus }}
        <span v-if="projectTitle" class="project-badge" @click="emit('project-click')">
          <i class="fas fa-folder-open" /> {{ projectTitle }}
        </span>
      </span>
      <div class="input-meta-right">
        <!-- Token 用量指示（WANT-001） -->
        <span v-if="messagesCount > 0" class="token-indicator"
              :style="{ color: tokenColor }"
              :class="{ 'token-warn': tokenWarning }"
              :title="`估算 token 用量: ${estimatedTokens}/${ctxLimit}`">
          <i class="fas fa-database" style="font-size:var(--text-2xs)" />
          {{ estimatedTokens }}/{{ ctxLimit }}
        </span>

        <!-- 会话操作工具条：历史 / 导出 / 撤回 / 清空（水平排列，右下角） -->
        <div class="input-toolbar">
          <button class="toolbar-btn" :class="{ active: showHistory }" title="查看历史会话" @click="emit('toggle-history')">
            <i class="fas fa-history" />
          </button>
          <div class="toolbar-export-wrap" v-if="messagesCount > 0">
            <button class="toolbar-btn" title="导出对话" @click.stop="showExportMenu = !showExportMenu">
              <i class="fas fa-download" />
            </button>
            <div v-if="showExportMenu" class="export-menu" @click.stop>
              <button @click="emit('export', 'md'); showExportMenu = false">
                <i class="fab fa-markdown" /> Markdown
              </button>
              <button @click="emit('export', 'txt'); showExportMenu = false">
                <i class="fas fa-file-alt" /> TXT
              </button>
            </div>
          </div>
          <button v-if="messagesCount > 0" class="toolbar-btn" :class="{ active: retractMode }" title="撤回消息" @click.stop="emit('toggle-retract-mode')">
            <i class="fas fa-rotate-left" />
          </button>
          <button v-if="messagesCount > 0" class="toolbar-btn toolbar-btn-danger" title="清空对话" @click.stop="emit('clear-chat')">
            <i class="fas fa-trash-alt" />
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  isConnected:      { type: Boolean, default: false },
  isThinking:       { type: Boolean, default: false },
  isStreaming:      { type: Boolean, default: false },
  modelStatus:      { type: String,  default: '' },
  currentModel:     { type: String,  default: '' },
  activeRoleName:   { type: String,  default: '默认助手' },
  showHistory:      { type: Boolean, default: false },
  retractMode:      { type: Boolean, default: false },
  messagesCount:    { type: Number,  default: 0 },
  estimatedTokens:  { type: Number,  default: 0 },
  ctxLimit:         { type: Number,  default: 8192 },
  tokenColor:       { type: String,  default: 'var(--color-text-muted)' },
  tokenWarning:     { type: Boolean, default: false },
  projectTitle:     { type: String,  default: '' },
})

const emit = defineEmits([
  'send', 'cancel-stream', 'toggle-history', 'export',
  'toggle-retract-mode', 'clear-chat', 'open-role-model-sheet', 'project-click',
])

const inputText            = ref('')
const inputRef             = ref(null)
// 多模态图片附件
const attachedImageB64     = ref(null)   // 纯 base64 字符串（去掉 data URL 前缀）
const attachedImagePreview = ref(null)   // Data URL 用于本地显示缩略图
const isReadingImage       = ref(false)  // FileReader 进行中时禁用附图按钮
const showExportMenu       = ref(false)

const IMAGE_MAX_BYTES = 5 * 1024 * 1024  // 5 MB 上限

const _readImageFile = (file) => {
  if (!file || !file.type.startsWith('image/')) return
  if (file.size > IMAGE_MAX_BYTES) {
    ElMessage({ message: '图片大小不能超过 5MB', type: 'warning', duration: 2500 })
    return
  }
  isReadingImage.value = true
  const reader = new FileReader()
  reader.onload = (e) => {
    const dataUrl = e.target.result             // "data:image/png;base64,xxxx"
    attachedImagePreview.value = dataUrl
    // 去掉 "data:image/xxx;base64," 前缀，只保留纯 base64
    const comma = dataUrl.indexOf(',')
    attachedImageB64.value = comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl
    isReadingImage.value = false
  }
  reader.onerror = () => {
    ElMessage({ message: '图片读取失败，请重试', type: 'error', duration: 2500 })
    isReadingImage.value = false
  }
  reader.readAsDataURL(file)
}

const onAttachImageFile = (e) => {
  const file = e.target.files?.[0]
  if (file) _readImageFile(file)
}

const clearAttachedImage = () => {
  attachedImageB64.value = null
  attachedImagePreview.value = null
}

const onPasteImage = (e) => {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        e.preventDefault()
        _readImageFile(file)
      }
      break
    }
  }
}

const canSend = computed(() =>
  props.isConnected &&
  !props.isThinking &&
  !props.isStreaming &&
  inputText.value.trim().length > 0
)

const autoResize = () => {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

const handleKeydown = (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    trySend()
  }
}

const trySend = () => {
  const text = inputText.value.trim()
  if (!text || !canSend.value) return
  emit('send', text, attachedImageB64.value, attachedImagePreview.value)
  inputText.value = ''
  nextTick(() => { if (inputRef.value) inputRef.value.style.height = 'auto' })
  clearAttachedImage()
}

// 点击其他地方关闭导出菜单（UX-009）
const closeExportMenu = (e) => {
  if (!e.target.closest('.export-float') && !e.target.closest('.export-menu')) {
    showExportMenu.value = false
  }
}

/** 供父组件从空态建议卡片填入输入框并聚焦 */
const fillSuggestion = (text) => {
  inputText.value = text
  nextTick(() => {
    inputRef.value?.focus()
    autoResize()
  })
}

defineExpose({ fillSuggestion })

onMounted(() => {
  inputRef.value?.focus()
  document.addEventListener('click', closeExportMenu)
})

onUnmounted(() => {
  document.removeEventListener('click', closeExportMenu)
})
</script>

<style scoped>
/* ── 输入区 ───────────────────────────────────────────────── */
.input-area { border-top: 1px solid var(--color-border); background: var(--color-surface); padding: var(--space-3) var(--space-4); }
.input-wrap {
  display: flex;
  align-items: flex-end;
  gap: var(--space-2-5);
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-2-5);
  transition: border-color var(--duration-base);
}
.input-wrap:focus-within { border-color: var(--color-primary); box-shadow: var(--shadow-focus); }
.input-wrap-thinking    { border-color: var(--color-primary); background: var(--color-primary-soft); }
.input-wrap-disconnected{ border-color: var(--color-danger-border); background: var(--color-danger-soft); }
.chat-input {
  flex: 1;
  border: none;
  background: transparent;
  resize: none;
  font-size: var(--text-base);
  line-height: var(--leading-normal);
  color: var(--color-text);
  outline: none;
  max-height: 160px;
  overflow-y: auto;
  font-family: inherit;
}
.chat-input::placeholder     { color: var(--color-text-muted); }
.chat-input:disabled         { opacity: 0.7; cursor: not-allowed; }
.input-wrap-thinking .chat-input::placeholder { color: var(--color-primary); font-style: italic; }
.input-wrap-disconnected .chat-input::placeholder { color: var(--color-danger); }

/* ── 图片附件 ─────────────────────────────────────────────── */
.attached-img-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-1-5) var(--space-1) var(--space-0-5);
}
.attached-thumb {
  max-height: 80px;
  max-width: 120px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  object-fit: contain;
}
.attached-remove {
  background: none;
  border: none;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: var(--text-sm-2);
  padding: var(--space-1) var(--space-1);
  border-radius: var(--radius-xs);
  transition: color var(--duration-fast), background var(--duration-fast);
  line-height: var(--leading-none);
}
.attached-remove:hover { color: var(--color-danger); background: var(--color-danger-soft); }
.attach-btn {
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-sm);
  flex-shrink: 0;
  transition: var(--transition-interactive);
}
.attach-btn:hover  { border-color: var(--color-primary); color: var(--color-primary); background: var(--color-surface-raised); }
.attach-btn.attach-active { border-color: var(--color-primary); color: var(--color-primary); background: var(--color-surface-raised); }

/* 停止按钮脉冲动画 */
.stop-btn-pulse {
  animation: pulse-red 1.4s infinite;
}
@keyframes pulse-red {
  0%, 100% { box-shadow: 0 0 0 0 var(--color-danger-soft); }
  50%       { box-shadow: 0 0 0 6px transparent; }
}
.send-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  border: none;
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-sm);
  transition: background var(--duration-base), transform var(--duration-fast);
  flex-shrink: 0;
}
.send-btn:hover:not(:disabled)  { background: var(--color-primary-hover); }
.send-btn:active:not(:disabled) { transform: scale(0.95); }
.send-btn:disabled              { background: var(--color-text-muted); cursor: not-allowed; }
.stop-btn {
  width: 36px; height: 36px; border-radius: var(--radius-sm);
  border: none; background: var(--color-danger); color: white;
  cursor: pointer; font-size: var(--text-sm);
  display: flex; align-items: center; justify-content: center;
  transition: background var(--duration-base);
  flex-shrink: 0;
}
.stop-btn:hover { background: var(--color-danger); }

/* ── 底部提示 ─────────────────────────────────────────────── */
.input-meta { margin-top: var(--space-1-5); padding: 0 var(--space-1); display: flex; align-items: center; justify-content: space-between; gap: var(--space-2-5); }
.hint       { font-size: var(--text-xs); color: var(--color-text-muted); display: flex; align-items: center; gap: var(--space-1); min-width: 0; }
.hint.warn  { color: var(--color-warn); }
.hint i     { font-size: var(--text-xs); }
.hint-tip   { font-size: var(--text-xs); color: var(--color-text-muted); }
.project-badge { background: var(--color-primary-soft); color: var(--color-primary); border-radius: var(--radius-xs); padding: var(--space-px) var(--space-1-5); font-size: var(--text-xs); display: inline-flex; align-items: center; gap: var(--space-1); cursor: pointer; }
.project-badge:hover { background: var(--color-info-soft); }
.input-meta-right { display: flex; align-items: center; gap: var(--space-3); flex-shrink: 0; }
.token-indicator { font-size: var(--text-xs); display: flex; align-items: center; gap: var(--space-1); }
.token-warn      { animation: blink 1.5s ease-in-out infinite; }
@keyframes blink { 50% { opacity: 0.6; } }

/* ── 会话操作工具条（历史/导出/清空，input-meta 右下角水平排列） ── */
.input-toolbar { display: flex; align-items: center; gap: var(--space-1-5); }
.toolbar-btn {
  width: 30px; height: 30px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xs);
  transition: var(--transition-interactive);
}
.toolbar-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.toolbar-btn.active { background: var(--color-primary); color: var(--color-text-on-primary); border-color: var(--color-primary); }
.toolbar-btn-danger { border-color: var(--color-danger-border); color: var(--color-danger); }
.toolbar-btn-danger:hover { border-color: var(--color-danger); background: var(--color-danger-soft); }
.toolbar-export-wrap { position: relative; }

/* ── 导出菜单（从右下角工具条的导出按钮展开） ── */
.export-menu {
  position: absolute;
  bottom: calc(100% + 6px);
  right: 0;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-raised);
  overflow: hidden;
  z-index: 100;
  min-width: 130px;
}
.export-menu button {
  display: flex; align-items: center; gap: var(--space-2);
  width: 100%; padding: var(--space-2-5) var(--space-3-5);
  border: none; background: none;
  font-size: var(--text-sm); color: var(--color-text);
  cursor: pointer; text-align: left;
  transition: background var(--duration-fast);
}
.export-menu button:hover { background: var(--color-surface-sunken); }
.export-menu button i { color: var(--color-primary); width: 14px; }

/* 移动端徽章（仅在移动端通过父元素显示） */
.mobile-config-chips { display: none; }
.mobile-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2-5);
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--text-xs);
  cursor: pointer;
  max-width: min(140px, 35vw);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  -webkit-tap-highlight-color: transparent;
  transition: border-color var(--duration-fast);
}
.mobile-chip:hover { border-color: var(--color-primary); }
.mobile-chip-role {
  background: var(--color-primary-soft);
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}
.mobile-chip-role i { color: var(--color-primary); font-size: var(--text-xs); }
.mobile-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .chat-input        { font-size: var(--text-md) !important; } /* 防止 iOS 自动缩放 */
  .input-area        { padding: var(--space-2) !important; }
  .mobile-config-chips {
    display: flex;
    gap: var(--space-2);
    padding: var(--space-1-5) 0 var(--space-1);
  }

  /*
   * 移动端 input-toolbar 按钮可见性：
   *   历史（fa-history） — 保留：高频操作，拇指友好
   *   清空（fa-trash）  — 保留：需要确认对话框，由 useConfirmDialogStore 实现
   *   导出（fa-download）— 隐藏：低频，通过 MorePanel 访问
   *
   * ⚠️  清空按钮的确认必须使用 useConfirmDialogStore，严禁 window.confirm()。
   *     window.confirm() 在 PWA/WebView 模式下被浏览器静默拦截，曾是 7 次重复 bug 的根因。
   *     ChatView.vue 中的 handleClearChat() 已正确使用 confirmDialog.confirm()。
   */
  .toolbar-export-wrap { display: none; }
}
</style>
