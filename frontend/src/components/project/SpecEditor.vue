<template>
  <div class="spec-editor">
    <div class="spec-header">
      <span class="spec-title">项目规格文档</span>
      <div class="spec-actions">
        <button class="action-btn" :class="{ active: !preview }" @click="preview = false" title="编辑">
          <i class="fas fa-edit" /> 编辑
        </button>
        <button class="action-btn" :class="{ active: preview }" @click="preview = true" title="预览">
          <i class="fas fa-eye" /> 预览
        </button>
        <button class="save-btn" :disabled="saving || !dirty" @click="saveSpec">
          <i class="fas fa-save" />
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>

    <div v-if="!preview" class="editor-wrap">
      <textarea
        v-model="content"
        class="spec-textarea"
        placeholder="在这里描述项目目标、功能需求、技术约束等规格内容…&#10;&#10;AI 会周期性地参考此文档，确保回答符合项目要求。"
        @input="dirty = true"
      />
    </div>
    <div v-if="!preview" class="spec-hint">
      <i class="fas fa-lightbulb hint-icon" />
      <span class="hint-items">
        描述项目目标与技术栈 ·
        写好后点<strong>保存</strong> ·
        点右侧<strong>AI 分解</strong>按钮自动拆解任务树
      </span>
    </div>

    <div v-else class="preview-wrap">
      <div class="md-content" v-html="rendered" />
      <div v-if="!content" class="preview-empty">
        <i class="fas fa-file-alt" />
        <p>暂无规格内容，切换到编辑模式开始编写。</p>
      </div>
    </div>

    <div class="spec-footer">
      <span class="spec-meta">
        <template v-if="lastSaved">上次保存：{{ lastSaved }}</template>
        <template v-else-if="!projectId">请先选择或创建项目</template>
        <template v-else>未保存</template>
      </span>
      <span v-if="dirty" class="unsaved-dot" title="有未保存的修改">●</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useProjectStore } from '@/stores/project'

const props = defineProps({
  projectId: { type: String, default: null },
})

const projectStore = useProjectStore()
const content  = ref('')
const preview  = ref(false)
const dirty    = ref(false)
const saving   = ref(false)
const lastSaved = ref('')

const rendered = computed(() =>
  DOMPurify.sanitize(marked.parse(content.value || ''), {
    ALLOWED_TAGS: ['p','br','strong','em','code','pre','blockquote',
                   'ul','ol','li','h1','h2','h3','h4','table',
                   'thead','tbody','tr','th','td','a','span'],
    ALLOWED_ATTR: ['href','class'],
  })
)

// Load spec when project changes
watch(
  () => props.projectId,
  async (pid) => {
    if (!pid) { content.value = ''; dirty.value = false; return }
    const proj = projectStore.projects.find(p => p.id === pid)
    content.value = proj?.spec?.content || ''
    dirty.value   = false
    lastSaved.value = proj?.spec?.last_updated
      ? new Date(proj.spec.last_updated).toLocaleString('zh-CN')
      : ''
  },
  { immediate: true }
)

async function saveSpec() {
  if (!props.projectId || saving.value) return
  saving.value = true
  try {
    await projectStore.updateSpec(props.projectId, content.value)
    dirty.value     = false
    lastSaved.value = new Date().toLocaleString('zh-CN')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.spec-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: white;
  border-radius: 8px;
  border: 1px solid #e8eaed;
  overflow: hidden;
}

.spec-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.spec-title {
  font-size: 0.88rem;
  font-weight: 600;
  color: #4a5568;
}

.spec-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.action-btn {
  background: #f8f9fa;
  border: 1px solid #e0e3e8;
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 0.78rem;
  color: #666;
  cursor: pointer;
  transition: all 0.15s;
  display: flex;
  align-items: center;
  gap: 4px;
}
.action-btn:hover, .action-btn.active {
  background: #667eea;
  color: white;
  border-color: #667eea;
}

.save-btn {
  background: #4fc3a1;
  color: white;
  border: none;
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 0.78rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: background 0.15s;
}
.save-btn:hover:not(:disabled) { background: #38a98a; }
.save-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.editor-wrap {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.spec-textarea {
  flex: 1;
  border: none;
  outline: none;
  resize: none;
  padding: 14px;
  font-size: 0.88rem;
  line-height: 1.7;
  font-family: 'Fira Code', Consolas, monospace;
  color: #333;
  background: #fafafa;
}
.spec-textarea::placeholder { color: #bbb; }

.spec-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  background: #eff6ff;
  border-top: 1px solid #bfdbfe;
  font-size: 0.8rem;
  color: #1e40af;
  flex-shrink: 0;
}
.hint-icon { color: #3b82f6; }
.hint-items strong { color: #1d4ed8; }

.preview-wrap {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
}

.preview-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 10px;
  color: #ccc;
  font-size: 0.88rem;
}
.preview-empty i { font-size: 2rem; }

.md-content :deep(p)            { margin: 0 0 8px; }
.md-content :deep(pre)          { background: #f6f8fa; border-radius: 6px; padding: 10px; overflow-x: auto; }
.md-content :deep(code)         { font-family: 'Fira Code', monospace; font-size: 0.88em; }
.md-content :deep(p > code)     { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; }
.md-content :deep(ul), .md-content :deep(ol) { padding-left: 18px; margin: 4px 0; }
.md-content :deep(blockquote)   { border-left: 3px solid #667eea; margin: 6px 0; padding: 3px 10px; color: #666; }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3) { margin: 10px 0 6px; }

.spec-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  border-top: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.spec-meta {
  font-size: 0.72rem;
  color: #bbb;
}

.unsaved-dot {
  color: #f57c00;
  font-size: 0.65rem;
}
</style>
