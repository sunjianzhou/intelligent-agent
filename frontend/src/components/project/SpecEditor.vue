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
  background: var(--color-surface);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  overflow: hidden;
}

.spec-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2-5) var(--space-3-5);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.spec-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-semibold);
  color: var(--color-text-secondary);
}

.spec-actions {
  display: flex;
  gap: var(--space-1-5);
  align-items: center;
}

.action-btn {
  background: var(--color-surface-sunken);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-2-5);
  font-size: var(--text-xs);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: var(--transition-interactive);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.action-btn:hover, .action-btn.active {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border-color: var(--color-primary);
}

.save-btn {
  background: var(--color-accent);
  color: white;
  border: none;
  border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-3);
  font-size: var(--text-xs);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  transition: background var(--duration-fast);
}
.save-btn:hover:not(:disabled) { background: var(--color-accent); }
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
  padding: var(--space-3-5);
  font-size: var(--text-sm);
  line-height: var(--leading-prose);
  font-family: 'Fira Code', Consolas, monospace;
  color: var(--color-text);
  background: var(--color-surface-sunken);
}
.spec-textarea::placeholder { color: var(--color-text-muted); }

.spec-hint {
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3-5);
  background: var(--color-surface-raised);
  border-top: 1px solid var(--color-primary-border);
  font-size: var(--text-sm-2);
  color: var(--color-primary);
  flex-shrink: 0;
}
.hint-icon { color: var(--color-primary); }
.hint-items strong { color: var(--color-primary); }

.preview-wrap {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-3-5);
}

.preview-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: var(--space-2-5);
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.preview-empty i { font-size: 2rem; }

.md-content :deep(p)            { margin: 0 0 var(--space-2); }
.md-content :deep(pre)          { background: var(--color-surface-sunken); border-radius: var(--radius-sm); padding: var(--space-2-5); overflow-x: auto; }
.md-content :deep(code)         { font-family: 'Fira Code', monospace; font-size: 0.88em; }
.md-content :deep(p > code)     { background: var(--color-surface-sunken); padding: var(--space-px) var(--space-1); border-radius: var(--radius-xs); }
.md-content :deep(ul), .md-content :deep(ol) { padding-left: var(--space-4); margin: var(--space-1) 0; }
.md-content :deep(blockquote)   { border-left: 3px solid var(--color-primary); margin: var(--space-1-5) 0; padding: var(--space-1) var(--space-2-5); color: var(--color-text-secondary); }
.md-content :deep(h1), .md-content :deep(h2), .md-content :deep(h3) { margin: var(--space-2-5) 0 var(--space-1-5); }

.spec-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-1-5) var(--space-3-5);
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
}

.spec-meta {
  font-size: var(--text-xs);
  color: var(--color-text-muted);
}

.unsaved-dot {
  color: var(--color-warn);
  font-size: var(--text-2xs);
}
</style>
