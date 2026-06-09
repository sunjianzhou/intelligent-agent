<template>
  <div class="personas-view">
    <!-- 顶部操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="page-desc">定义 AI 助手的角色与行为风格</span>
      </div>
      <button class="btn-primary" @click="openEditor()">
        <i class="fas fa-plus" /> 新建角色
      </button>
    </div>

    <!-- 角色卡片列表 -->
    <div class="personas-grid" v-if="!loading">
      <div
        v-for="p in personas"
        :key="p.name"
        class="persona-card"
        :class="{ active: p.name === currentPersona }"
      >
        <div class="card-header">
          <div class="card-icon" :class="{ 'has-emoji': extractEmoji(p.title) }">
            <span v-if="extractEmoji(p.title)" class="emoji-icon">{{ extractEmoji(p.title) }}</span>
            <i v-else class="fas fa-user-circle" />
          </div>
          <div class="card-info">
            <div class="card-title">{{ stripEmoji(p.title) }}</div>
            <div v-if="p.name !== p.title && !/^[a-zA-Z0-9_-]+$/.test(p.name)" class="card-name">{{ p.name }}</div>
          </div>
          <div class="card-badge" v-if="p.name === currentPersona">当前</div>
        </div>

        <div class="card-preview" v-if="p.content">{{ previewContent(p.content) }}</div>
        <div class="card-preview empty" v-else>点击查看内容...</div>

        <div class="card-actions">
          <button class="act-btn use" @click="handleSwitch(p.name)" :disabled="p.name === currentPersona || switching">
            <i class="fas fa-check" /> {{ p.name === currentPersona ? '使用中' : '使用' }}
          </button>
          <button class="act-btn edit" @click="openEditor(p)">
            <i class="fas fa-edit" /> 编辑
          </button>
          <button class="act-btn del" @click="handleDelete(p.name)" :disabled="p.name === 'default'">
            <i class="fas fa-trash" />
          </button>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-if="personas.length === 0" class="empty-state">
        <i class="fas fa-user-circle empty-icon" />
        <p>暂无角色，点击「新建角色」创建第一个</p>
      </div>
    </div>

    <div v-else class="loading-state">
      <i class="fas fa-circle-notch fa-spin" /> 加载中...
    </div>

    <!-- 编辑/新建弹窗 -->
    <div v-if="editorOpen" class="modal-mask" @click.self="editorOpen = false">
      <div class="modal-box">
        <div class="modal-header">
          <span class="modal-title">{{ editTarget ? '编辑角色' : '新建角色' }}</span>
          <button class="modal-close" @click="editorOpen = false"><i class="fas fa-times" /></button>
        </div>
        <div class="modal-body">
          <div class="form-row">
            <div class="form-group" style="flex:0 0 72px">
              <label>图标 <span class="hint-text">（emoji）</span></label>
              <input
                v-model="form.emoji"
                class="form-input emoji-input"
                placeholder="🤖"
                maxlength="4"
              />
            </div>
            <div class="form-group">
              <label>展示名称 <span class="hint-text">（用户可见的名称）</span></label>
              <input
                v-model="form.display_name"
                class="form-input"
                placeholder="如 英语外教、代码专家"
              />
            </div>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label>角色标识 <span class="hint-text">（内部 ID，英文/数字）</span></label>
              <input
                v-model="form.name"
                class="form-input"
                placeholder="如 english-tutor"
                :disabled="!!editTarget"
              />
            </div>
          </div>
          <div class="form-group">
            <div class="content-label-row">
              <label>角色内容 <span class="hint-text">（System Prompt，可用 Markdown）</span></label>
              <div class="editor-tabs">
                <button :class="['tab-btn', { active: editorTab === 'edit' }]" @click="editorTab = 'edit'">
                  <i class="fas fa-pen" /> 编辑
                </button>
                <button :class="['tab-btn', { active: editorTab === 'preview' }]" @click="editorTab = 'preview'">
                  <i class="fas fa-eye" /> 预览
                </button>
              </div>
            </div>
            <textarea
              v-if="editorTab === 'edit'"
              v-model="form.content"
              class="form-textarea"
              rows="10"
              placeholder="你是...（描述 AI 的角色、行为风格、专长等）"
            />
            <div
              v-else
              class="form-preview md-content"
              v-html="renderPersonaPreview(form.content)"
            />
          </div>
          <div class="content-tip">
            <i class="fas fa-lightbulb" />
            内容即 System Prompt，直接注入到 LLM 请求中。可用 Markdown 格式。
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="editorOpen = false">取消</button>
          <button class="btn-primary" @click="handleSave" :disabled="saving">
            <i :class="saving ? 'fas fa-circle-notch fa-spin' : 'fas fa-save'" />
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPersonas, getCurrentPersona, getPersonaContent, switchPersona, upsertPersona, deletePersona } from '@/services/api'
import { useWebSocketStore } from '@/stores/websocket'

const store       = useWebSocketStore()
const personas    = ref([])
const currentPersona = ref('default')
const loading     = ref(true)
const switching   = ref(false)
const editorOpen  = ref(false)
const editTarget  = ref(null)
const saving      = ref(false)
const editorTab   = ref('edit')  // 'edit' | 'preview'

const form = ref({ name: '', display_name: '', emoji: '', content: '' })

const renderPersonaPreview = (text) =>
  DOMPurify.sanitize(marked.parse(text || '*（内容为空）*'), {
    ALLOWED_TAGS: ['p','br','strong','em','code','pre','blockquote',
                   'ul','ol','li','h1','h2','h3','h4','h5','h6','span'],
    ALLOWED_ATTR: ['class'],
  })

// 从 title 里提取开头的 emoji（若有）
const EMOJI_RE = /^\p{Emoji_Presentation}\s*/u
const extractEmoji = (title) => {
  const m = (title || '').match(EMOJI_RE)
  return m ? m[0].trim() : null
}
const stripEmoji = (title) => (title || '').replace(EMOJI_RE, '').trim() || title

const previewContent = (content) => {
  const lines = content.split('\n').filter(l => l.trim() && !l.startsWith('#'))
  return lines.slice(0, 2).join(' ').slice(0, 120) + (lines.join('').length > 120 ? '...' : '')
}

const loadPersonas = async () => {
  loading.value = true
  const [listData, curData] = await Promise.all([getPersonas(), getCurrentPersona()])
  currentPersona.value = curData?.persona || 'default'

  if (listData?.personas) {
    const withContent = await Promise.all(
      listData.personas.map(async (p) => {
        const c = await getPersonaContent(p.name)
        return { ...p, content: c?.content || '' }
      })
    )
    personas.value = withContent
  }
  loading.value = false
}

const handleSwitch = async (name) => {
  switching.value = true
  const result = await switchPersona(name)
  if (result?.success) {
    currentPersona.value = name
    store.currentPersona = name
    ElMessage({ message: `已切换到「${personas.value.find(p => p.name === name)?.title || name}」`, type: 'success', duration: 2000 })
  } else {
    ElMessage({ message: '切换失败', type: 'error', duration: 2000 })
  }
  switching.value = false
}

const openEditor = async (persona = null) => {
  editTarget.value = persona
  editorTab.value  = 'edit'
  if (persona) {
    const rawTitle = persona.title || ''
    form.value = {
      name:         persona.name,
      emoji:        extractEmoji(rawTitle) || '',
      display_name: stripEmoji(rawTitle),
      content:      persona.content || '',
    }
  } else {
    form.value = { name: '', emoji: '', display_name: '', content: '' }
  }
  editorOpen.value = true
}

const handleSave = async () => {
  const name    = form.value.name.trim()
  const emoji   = form.value.emoji.trim()
  const rawName = form.value.display_name.trim()
  // 合并 emoji + 展示名，存为 display_name
  const displayName = emoji ? `${emoji} ${rawName}` : rawName
  const content     = form.value.content.trim()
  if (!name)    { ElMessage({ message: '请填写角色标识', type: 'warning' }); return }
  if (!content) { ElMessage({ message: '请填写角色内容', type: 'warning' }); return }

  saving.value = true
  const result = await upsertPersona(name, content, displayName)
  saving.value = false

  if (result?.success) {
    ElMessage({ message: '角色已保存', type: 'success', duration: 2000 })
    editorOpen.value = false
    await loadPersonas()
    await store.loadPersonas()
  } else {
    ElMessage({ message: result?.message || '保存失败', type: 'error' })
  }
}

const handleDelete = async (name) => {
  try {
    await ElMessageBox.confirm(`确定删除角色「${name}」吗？此操作不可恢复。`, '删除确认', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }

  const result = await deletePersona(name)
  if (result?.success) {
    ElMessage({ message: '已删除', type: 'success', duration: 2000 })
    if (currentPersona.value === name) {
      currentPersona.value = 'default'
      store.currentPersona = 'default'
    }
    await loadPersonas()
    await store.loadPersonas()
  } else {
    ElMessage({ message: result?.message || '删除失败', type: 'error' })
  }
}

onMounted(loadPersonas)
</script>

<style scoped>
.personas-view {
  height: 100%;
  overflow-y: auto;
  padding: 24px;
  background: #f8f9fa;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
.page-desc { font-size: 0.9rem; color: #888; }

.btn-primary {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border: none; border-radius: 8px;
  background: #667eea; color: white; font-size: 0.88rem;
  cursor: pointer; transition: background 0.2s;
}
.btn-primary:hover { background: #5a6fd6; }
.btn-primary:disabled { background: #ccc; cursor: not-allowed; }

.btn-secondary {
  padding: 8px 16px; border: 1px solid #e0e3e8; border-radius: 8px;
  background: white; color: #555; font-size: 0.88rem;
  cursor: pointer; transition: all 0.2s;
}
.btn-secondary:hover { background: #f5f5f5; }

/* ── 卡片网格 ─────────────────────────────────────────── */
.personas-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.persona-card {
  background: white;
  border-radius: 14px;
  border: 2px solid #e8eaed;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: all 0.2s;
  cursor: default;
}
.persona-card:hover { box-shadow: 0 4px 16px rgba(102,126,234,0.12); border-color: #d0d5f5; }
.persona-card.active { border-color: #667eea; background: #f8f9ff; }

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.card-icon {
  width: 44px; height: 44px; border-radius: 12px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  display: flex; align-items: center; justify-content: center;
  color: white; font-size: 1.2rem; flex-shrink: 0;
}
.card-icon.has-emoji { background: #f5f5f8; }
.emoji-icon { font-size: 1.5rem; line-height: 1; }
.persona-card.active .card-icon:not(.has-emoji) { background: linear-gradient(135deg, #4fc3a1, #45b39d); }

.emoji-input { text-align: center; font-size: 1.4rem; padding: 6px 4px; }

.card-info { flex: 1; min-width: 0; }
.card-title { font-weight: 600; font-size: 0.95rem; color: #333; }
.card-name  { font-size: 0.75rem; color: #aaa; margin-top: 2px; font-family: monospace; }
.card-badge {
  font-size: 0.7rem; color: #4fc3a1; background: #e8f8f5;
  border: 1px solid #b2ead7; border-radius: 20px; padding: 2px 8px;
  white-space: nowrap;
}

.card-preview {
  font-size: 0.82rem; color: #666; line-height: 1.5;
  background: #f8f9fa; border-radius: 8px; padding: 10px 12px;
  flex: 1; min-height: 56px;
}
.card-preview.empty { color: #ccc; font-style: italic; }

.card-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.act-btn {
  display: flex; align-items: center; gap: 5px;
  padding: 6px 12px; border-radius: 7px; border: 1px solid;
  font-size: 0.8rem; cursor: pointer; transition: all 0.15s;
}
.act-btn.use {
  flex: 1; justify-content: center;
  background: #667eea; border-color: #667eea; color: white;
}
.act-btn.use:hover:not(:disabled) { background: #5a6fd6; }
.act-btn.use:disabled { background: #e8f8f5; border-color: #b2ead7; color: #4fc3a1; cursor: default; }
.act-btn.edit {
  background: white; border-color: #e0e3e8; color: #555;
}
.act-btn.edit:hover { border-color: #667eea; color: #667eea; }
.act-btn.del {
  background: white; border-color: #e0e3e8; color: #ccc;
  padding: 6px 10px;
}
.act-btn.del:hover:not(:disabled) { border-color: #e53935; color: #e53935; }
.act-btn.del:disabled { opacity: 0.3; cursor: not-allowed; }

/* ── 空 / 加载 ──────────────────────────────────────── */
.empty-state {
  grid-column: 1 / -1;
  text-align: center;
  padding: 60px 20px;
  color: #aaa;
  display: flex; flex-direction: column; align-items: center; gap: 12px;
}
.empty-icon { font-size: 3rem; color: #d0d5f5; }
.loading-state { text-align: center; padding: 60px; color: #aaa; font-size: 0.9rem; }

/* ── 弹窗 ───────────────────────────────────────────── */
.modal-mask {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: white; border-radius: 16px;
  width: 560px; max-width: 95vw; max-height: 90vh;
  display: flex; flex-direction: column;
  box-shadow: 0 8px 40px rgba(0,0,0,0.15);
}
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 22px; border-bottom: 1px solid #f0f0f0;
}
.modal-title { font-weight: 600; font-size: 1rem; color: #333; }
.modal-close {
  background: none; border: none; color: #aaa;
  font-size: 1rem; cursor: pointer; padding: 4px 6px;
  border-radius: 4px; transition: color 0.15s;
}
.modal-close:hover { color: #333; }

.modal-body {
  padding: 22px; overflow-y: auto; flex: 1;
  display: flex; flex-direction: column; gap: 16px;
}
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group label { font-size: 0.88rem; font-weight: 500; color: #555; }
.hint-text { font-weight: 400; color: #aaa; font-size: 0.8rem; }
.form-input {
  padding: 9px 12px; border: 1px solid #e0e3e8; border-radius: 8px;
  font-size: 0.9rem; outline: none; transition: border-color 0.2s;
  font-family: monospace;
}
.form-input:focus { border-color: #667eea; }
.form-input:disabled { background: #f8f9fa; color: #999; }
.form-textarea {
  padding: 10px 12px; border: 1px solid #e0e3e8; border-radius: 8px;
  font-size: 0.88rem; font-family: inherit; resize: vertical;
  outline: none; transition: border-color 0.2s; line-height: 1.6;
  min-height: 200px;
}
.form-textarea:focus { border-color: #667eea; }
.content-label-row {
  display: flex; align-items: center; justify-content: space-between;
}
.editor-tabs {
  display: flex; gap: 2px;
  background: #f0f2f5; border-radius: 7px; padding: 3px;
}
.tab-btn {
  display: flex; align-items: center; gap: 5px;
  padding: 4px 12px; border: none; border-radius: 5px;
  font-size: 0.8rem; cursor: pointer; transition: all 0.15s;
  background: transparent; color: #888;
}
.tab-btn.active { background: white; color: #667eea; box-shadow: 0 1px 4px rgba(0,0,0,0.1); }
.tab-btn:hover:not(.active) { color: #555; }

.form-preview {
  min-height: 200px; padding: 12px 14px;
  border: 1px solid #e0e3e8; border-radius: 8px;
  background: #fafafa; overflow-y: auto;
  font-size: 0.88rem; line-height: 1.7; color: #333;
}
.form-preview :deep(p)          { margin: 0 0 0.6em; }
.form-preview :deep(h1),
.form-preview :deep(h2),
.form-preview :deep(h3)         { margin: 0.8em 0 0.4em; font-size: 1em; color: #444; }
.form-preview :deep(code)       { background: #eff0f2; border-radius: 3px; padding: 1px 5px; font-size: 0.85em; }
.form-preview :deep(pre)        { background: #eff0f2; border-radius: 6px; padding: 10px; overflow-x: auto; }
.form-preview :deep(blockquote) { border-left: 3px solid #667eea; margin: 0; padding-left: 12px; color: #888; }
.form-preview :deep(ul),
.form-preview :deep(ol)         { margin: 0 0 0.6em; padding-left: 1.4em; }

.content-tip {
  font-size: 0.78rem; color: #aaa; background: #f8f9fa;
  border-radius: 8px; padding: 8px 12px;
  display: flex; gap: 6px; align-items: flex-start;
}
.content-tip i { color: #f5a623; margin-top: 1px; flex-shrink: 0; }

.modal-footer {
  display: flex; gap: 10px; justify-content: flex-end;
  padding: 14px 22px; border-top: 1px solid #f0f0f0;
}

@media (max-width: 768px) {
  .personas-view { padding: 12px; }
  .personas-grid { grid-template-columns: 1fr; }
}
</style>
