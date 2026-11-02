<template>
  <div class="skill-view">
    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="tag-tabs">
        <button v-for="t in allTags" :key="t"
                class="tag-btn" :class="{ active: activeTag === t }"
                @click="activeTag = t">
          {{ t === 'all' ? '全部' : t }}
          <span class="tag-count">{{ tagCount(t) }}</span>
        </button>
      </div>
      <div class="toolbar-right">
        <button class="refresh-btn" :class="{ spinning: loading }" aria-label="刷新 Skill 列表" title="刷新 Skill 列表" @click="load">
          <i class="fas fa-sync-alt" />
        </button>
        <button class="import-btn" @click="openImport">
          <i class="fas fa-file-import" /> 从 MD 导入
        </button>
        <button class="create-btn" @click="openCreate">
          <i class="fas fa-plus" /> 新建 Skill
        </button>
      </div>
    </div>

    <!-- Skill 列表 -->
    <div v-if="filteredSkills.length" class="skill-list">
      <div v-for="skill in filteredSkills" :key="skill.id"
           class="skill-card" :class="{ disabled: !skill.enabled }">
        <div class="skill-header">
          <div class="skill-title-row">
            <span class="skill-name">{{ skill.name }}</span>
            <div class="skill-tags">
              <span v-for="tag in skill.scenario_tags" :key="tag" class="stag">{{ tag }}</span>
            </div>
            <span class="step-count" v-if="skill.steps?.length">
              <i class="fas fa-list-ol" /> {{ skill.steps.length }} 步
            </span>
          </div>
          <div class="skill-actions">
            <button class="toggle-btn" :class="skill.enabled ? 'on' : 'off'" @click="toggle(skill.id)">
              {{ skill.enabled ? '启用' : '禁用' }}
            </button>
            <button class="icon-btn edit-btn" @click="openEdit(skill)">
              <i class="fas fa-pen" />
            </button>
            <button class="icon-btn del-btn" @click="remove(skill.id)">
              <i class="fas fa-trash" />
            </button>
          </div>
        </div>

        <p class="skill-desc">{{ skill.description || '暂无描述' }}</p>

        <!-- 整体策略 -->
        <div v-if="skill.overall_strategy" class="strategy-box">
          <i class="fas fa-bullseye" />
          {{ skill.overall_strategy }}
        </div>

        <!-- 步骤展示 -->
        <div v-if="skill.steps?.length" class="steps-preview">
          <div v-for="(step, i) in skill.steps" :key="step.step_id" class="step-chip">
            <span class="step-num">{{ i + 1 }}</span>
            <span class="step-chip-name">{{ step.name }}</span>
            <span v-for="t in step.forced_tools" :key="t" class="tool-badge forced">{{ t }}</span>
            <span v-for="t in step.tool_hints" :key="t" class="tool-badge hint">{{ t }}</span>
          </div>
        </div>

        <!-- 触发词 -->
        <div class="meta-group" style="margin-top:var(--space-2)">
          <span class="meta-label">触发词</span>
          <span v-for="kw in skill.trigger_keywords" :key="kw" class="kw-badge">{{ kw }}</span>
          <span v-if="!skill.trigger_keywords.length" class="meta-empty">-</span>
        </div>
      </div>
    </div>

    <EmptyState
        v-else-if="!loading"
        icon="fas fa-magic"
        title="暂无 Skill"
        sub="点击右上角「新建 Skill」或「从 MD 导入」"
      />
    <SkeletonList v-if="loading" :rows="6" variant="cards" />

    <!-- ── 编辑/新建弹窗 ── -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal-box">
        <div class="modal-header">
          <span class="modal-title">{{ editingId ? '编辑 Skill' : '新建 Skill' }}</span>
          <button class="modal-close" @click="closeModal"><i class="fas fa-times" /></button>
        </div>
        <div class="modal-body">
          <!-- 基本信息 -->
          <div class="section-title"><i class="fas fa-info-circle" /> 基本信息</div>
          <div class="form-row">
            <label>名称 <span class="req">*</span></label>
            <input v-model="form.name" placeholder="例：代码审查助手" />
          </div>
          <div class="form-row">
            <label>描述</label>
            <input v-model="form.description" placeholder="简短说明用途" />
          </div>
          <div class="form-row">
            <label>场景标签 <span class="tip">逗号分隔</span></label>
            <input v-model="form.scenario_tags_str" placeholder="例：math, file, github" />
          </div>
          <div class="form-row">
            <label>触发关键词 <span class="tip">逗号分隔</span></label>
            <input v-model="form.trigger_keywords_str" placeholder="例：计算,算一下,等于几" />
          </div>
          <div class="form-row">
            <label>整体目标</label>
            <textarea v-model="form.overall_strategy" rows="2"
                      placeholder="例：帮助用户进行数学计算，必须调用工具精确计算，不能估算。" />
          </div>
          <div class="form-row toggle-row">
            <label>启用</label>
            <button class="toggle-btn" :class="form.enabled ? 'on' : 'off'"
                    @click="form.enabled = !form.enabled">
              {{ form.enabled ? '启用' : '禁用' }}
            </button>
          </div>

          <!-- 执行步骤 -->
          <div class="section-title steps-title">
            <span><i class="fas fa-list-ol" /> 执行步骤</span>
            <button class="add-step-btn" @click="addStep">
              <i class="fas fa-plus" /> 添加步骤
            </button>
          </div>
          <div class="steps-empty" v-if="!form.steps.length">
            暂无步骤，点击「添加步骤」定义执行流程
          </div>

          <div v-for="(step, idx) in form.steps" :key="step._key" class="step-editor">
            <div class="step-editor-header">
              <span class="step-editor-num">第 {{ idx + 1 }} 步</span>
              <input v-model="step.name" class="step-name-input" placeholder="步骤名称（如：搜索代码）" />
              <div class="step-order-btns">
                <button class="order-btn" :disabled="idx === 0" @click="moveStep(idx, -1)">
                  <i class="fas fa-chevron-up" />
                </button>
                <button class="order-btn" :disabled="idx === form.steps.length - 1" @click="moveStep(idx, 1)">
                  <i class="fas fa-chevron-down" />
                </button>
                <button class="del-step-btn" @click="removeStep(idx)">
                  <i class="fas fa-trash" />
                </button>
              </div>
            </div>
            <div class="step-editor-body">
              <div class="form-row">
                <label>步骤说明</label>
                <input v-model="step.description" placeholder="这一步要做什么" />
              </div>
              <div class="form-row">
                <label>建议工具 <span class="tip">优先传给模型</span></label>
                <ToolSelect
                  v-model="step.tool_hints"
                  :tools="toolList"
                  badge-class="hint"
                  :dropdown-key="`step_${idx}_hint`"
                  :open-key="openDropKey"
                  :search="dropSearch"
                  @open="openDrop(`step_${idx}_hint`)"
                  @search="dropSearch = $event"
                />
              </div>
              <div class="form-row">
                <label>强制工具 <span class="tip">必须调用</span></label>
                <ToolSelect
                  v-model="step.forced_tools"
                  :tools="toolList"
                  badge-class="forced"
                  :dropdown-key="`step_${idx}_forced`"
                  :open-key="openDropKey"
                  :search="dropSearch"
                  @open="openDrop(`step_${idx}_forced`)"
                  @search="dropSearch = $event"
                />
              </div>
              <div class="form-row">
                <label>本步策略</label>
                <textarea v-model="step.strategy_prompt" rows="2"
                          placeholder="例：expression 填入数学表达式，不要加单位" />
              </div>
            </div>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn-cancel" @click="closeModal">取消</button>
          <button class="btn-confirm" :disabled="!form.name || saving" @click="save">
            <i v-if="saving" class="fas fa-circle-notch fa-spin" />
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ── MD 导入弹窗 ── -->
    <div v-if="showImport" class="modal-overlay" @click.self="showImport = false">
      <div class="modal-box import-box">
        <div class="modal-header">
          <span class="modal-title">从 Markdown 导入 Skill</span>
          <button class="modal-close" @click="showImport = false"><i class="fas fa-times" /></button>
        </div>
        <div class="modal-body">
          <div class="import-hint">
            支持字段：<code>name / description / tags / keywords / overall_strategy / strategy</code>
            <br>步骤用 <code>### 步骤N</code> 定义，步骤内支持：<code>tool_hints / forced_tools / strategy</code>
          </div>
          <div class="import-example">
            <div class="example-label">示例格式</div>
            <pre class="example-code">## 代码审查助手

**description**: 对代码进行专业审查
**tags**: code, review
**keywords**: 审查,代码,review
**overall_strategy**: 获取真实代码后再审查，严禁编造代码内容

### 步骤1 搜索相关代码
**tool_hints**: search_code, search_repositories
**strategy**: 用关键词搜索相关代码

### 步骤2 分析并给出建议
**strategy**: 基于真实代码给出专业建议</pre>
          </div>
          <div class="form-row">
            <label>粘贴 Markdown 内容</label>
            <textarea v-model="mdContent" rows="10" class="md-input" placeholder="粘贴 Skill MD 内容..." />
          </div>
          <div v-if="parsedPreview" class="preview-box">
            <div class="preview-label">解析预览</div>
            <div class="preview-item"><span class="pk">名称</span><span class="pv">{{ parsedPreview.name }}</span></div>
            <div class="preview-item" v-if="parsedPreview.description">
              <span class="pk">描述</span><span class="pv">{{ parsedPreview.description }}</span>
            </div>
            <div class="preview-item" v-if="parsedPreview.scenario_tags?.length">
              <span class="pk">标签</span><span class="pv">{{ parsedPreview.scenario_tags.join(', ') }}</span>
            </div>
            <div class="preview-item" v-if="parsedPreview.trigger_keywords?.length">
              <span class="pk">关键词</span><span class="pv">{{ parsedPreview.trigger_keywords.join(', ') }}</span>
            </div>
            <div class="preview-item" v-if="parsedPreview.overall_strategy">
              <span class="pk">整体目标</span><span class="pv">{{ parsedPreview.overall_strategy }}</span>
            </div>
            <div class="preview-item" v-if="parsedPreview.steps?.length">
              <span class="pk">步骤数</span><span class="pv">{{ parsedPreview.steps.length }} 个步骤</span>
            </div>
          </div>
          <div v-if="parseError" class="parse-error">
            <i class="fas fa-exclamation-circle" /> {{ parseError }}
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showImport = false">取消</button>
          <button class="btn-secondary" @click="parseMd">解析预览</button>
          <button class="btn-confirm" :disabled="!parsedPreview || saving" @click="importFromMd">
            <i v-if="saving" class="fas fa-circle-notch fa-spin" />
            {{ saving ? '导入中...' : '确认导入' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, defineComponent, h, watch } from 'vue'
import { getSkills, createSkill, updateSkill, deleteSkill, toggleSkill, getTools } from '@/services/api'
import { ElMessage } from 'element-plus'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

// ── 工具多选组件（内联，支持描述展示）───────────────────
const ToolSelect = defineComponent({
  props: {
    modelValue:  { type: Array,  default: () => [] },
    tools:       { type: Array,  default: () => [] },
    badgeClass:  { type: String, default: 'hint' },
    dropdownKey: { type: String, required: true },
    openKey:     { type: String, default: '' },
    search:      { type: String, default: '' },
  },
  emits: ['update:modelValue', 'open', 'search'],
  setup(props, { emit }) {
    const isOpen = computed(() => props.openKey === props.dropdownKey)

    const filtered = computed(() => {
      const q = isOpen.value ? props.search.toLowerCase() : ''
      return q
        ? props.tools.filter(t =>
            t.name.toLowerCase().includes(q) ||
            (t.description || '').toLowerCase().includes(q)
          )
        : props.tools
    })

    const toggle = (name) => {
      const arr = [...props.modelValue]
      const i = arr.indexOf(name)
      i === -1 ? arr.push(name) : arr.splice(i, 1)
      emit('update:modelValue', arr)
    }
    const remove = (name) => emit('update:modelValue', props.modelValue.filter(x => x !== name))
    const truncate = (s, n) => s && s.length > n ? s.slice(0, n) + '…' : (s || '')

    return () => h('div', { class: 'tms-wrap' }, [
      h('div', { class: 'selected-tools', onClick: () => emit('open') }, [
        props.modelValue.length === 0
          ? h('span', { class: 'placeholder' }, '点击选择工具...')
          : props.modelValue.map(name =>
              h('span', { class: `selected-tag ${props.badgeClass}`, key: name }, [
                name + ' ',
                h('i', { class: 'fas fa-times', onClick: (e) => { e.stopPropagation(); remove(name) } })
              ])
            ),
        h('i', { class: `fas fa-chevron-down arrow ${isOpen.value ? 'open' : ''}` })
      ]),
      isOpen.value && h('div', { class: 'tool-dropdown', onClick: (e) => e.stopPropagation() }, [
        h('div', { class: 'tool-search' }, [
          h('input', {
            value: props.search,
            placeholder: '搜索工具名或描述...',
            onInput: (e) => emit('search', e.target.value),
          })
        ]),
        h('div', { class: 'tool-option-list' }, [
          ...filtered.value.map(t =>
            h('div', {
              class: `tool-option ${props.modelValue.includes(t.name) ? 'selected' : ''}`,
              key: t.name,
              onClick: () => toggle(t.name),
            }, [
              h('i', { class: 'fas fa-check check-icon' }),
              h('div', { class: 'tool-info' }, [
                h('span', { class: 'tool-name' }, t.name),
                t.description && h('span', { class: 'tool-desc' },
                  truncate(t.description, 20)
                )
              ])
            ])
          ),
          filtered.value.length === 0 && h('div', { class: 'tool-empty' }, '无匹配工具')
        ])
      ])
    ])
  }
})

// ── 状态 ─────────────────────────────────────────────────
const skills      = ref([])
const toolList    = ref([])   // [{name, description}]
const loading     = ref(false)
const saving      = ref(false)
const showModal   = ref(false)
const showImport  = ref(false)
const editingId   = ref(null)
const activeTag   = ref('all')

// 全局单一下拉控制
const openDropKey = ref('')
const dropSearch  = ref('')

const openDrop = (key) => {
  openDropKey.value = openDropKey.value === key ? '' : key
  dropSearch.value  = ''
}
const closeAllDrops = (e) => {
  if (!e.target.closest('.tms-wrap')) {
    openDropKey.value = ''
    dropSearch.value  = ''
  }
}

// ── 表单 ─────────────────────────────────────────────────
const emptyStep = () => ({
  _key:            Date.now() + Math.random(),
  step_id:         '',
  name:            '',
  description:     '',
  tool_hints:      [],
  forced_tools:    [],
  strategy_prompt: '',
})

const emptyForm = () => ({
  name:                '',
  description:         '',
  scenario_tags_str:   '',
  trigger_keywords_str: '',
  overall_strategy:    '',
  steps:               [],
  enabled:             true,
})
const form = ref(emptyForm())

const addStep   = () => form.value.steps.push(emptyStep())
const removeStep = (idx) => form.value.steps.splice(idx, 1)
const moveStep  = (idx, dir) => {
  const arr = form.value.steps
  const to  = idx + dir
  if (to < 0 || to >= arr.length) return;
  [arr[idx], arr[to]] = [arr[to], arr[idx]]
}

// ── Tag 计算 ──────────────────────────────────────────────
const splitTrim = (s) => (s || '').split(',').map(x => x.trim()).filter(Boolean)
const allTags   = computed(() => {
  const s = new Set(['all'])
  skills.value.forEach(sk => sk.scenario_tags?.forEach(t => s.add(t)))
  return [...s]
})
const tagCount  = (tag) => tag === 'all' ? skills.value.length
  : skills.value.filter(s => s.scenario_tags?.includes(tag)).length
const filteredSkills = computed(() =>
  activeTag.value === 'all' ? skills.value
    : skills.value.filter(s => s.scenario_tags?.includes(activeTag.value))
)

// ── 数据加载 ──────────────────────────────────────────────
const load = async () => {
  loading.value = true
  try {
    const [skillData, toolData] = await Promise.all([getSkills(), getTools()])
    skills.value  = skillData?.skills || []
    const tools   = toolData?.tools || toolData?.available_tools || []
    toolList.value = tools
      .map(t => ({ name: t.name || t, description: t.description || '' }))
      .filter(t => t.name)
      .sort((a, b) => a.name.localeCompare(b.name))
  } finally {
    loading.value = false
  }
}

// ── CRUD ─────────────────────────────────────────────────
const openCreate = () => {
  editingId.value = null
  form.value = emptyForm()
  openDropKey.value = ''
  showModal.value = true
}

const openEdit = (skill) => {
  editingId.value = skill.id
  form.value = {
    name:                 skill.name,
    description:          skill.description,
    scenario_tags_str:    (skill.scenario_tags || []).join(', '),
    trigger_keywords_str: (skill.trigger_keywords || []).join(', '),
    overall_strategy:     skill.overall_strategy || '',
    steps:                (skill.steps || []).map(s => ({
      _key:            Math.random(),
      step_id:         s.step_id || '',
      name:            s.name || '',
      description:     s.description || '',
      tool_hints:      [...(s.tool_hints || [])],
      forced_tools:    [...(s.forced_tools || [])],
      strategy_prompt: s.strategy_prompt || '',
    })),
    enabled: skill.enabled,
  }
  openDropKey.value = ''
  showModal.value = true
}

const closeModal = () => { showModal.value = false; openDropKey.value = '' }

const buildPayload = () => ({
  name:             form.value.name,
  description:      form.value.description,
  scenario_tags:    splitTrim(form.value.scenario_tags_str),
  trigger_keywords: splitTrim(form.value.trigger_keywords_str),
  tool_hints:       [],
  forced_tools:     [],
  overall_strategy: form.value.overall_strategy,
  steps:            form.value.steps.map(({ _key, ...s }) => s),
  enabled:          form.value.enabled,
})

const save = async () => {
  if (!form.value.name) return
  saving.value = true
  try {
    const result = editingId.value
      ? await updateSkill(editingId.value, buildPayload())
      : await createSkill(buildPayload())
    if (result?.success) { closeModal(); await load() }
    else ElMessage.error(`保存失败: ${result?.message || '未知'}`)
  } finally { saving.value = false }
}

const toggle = async (id) => { await toggleSkill(id); await load() }
const remove = async (id) => {
  const confirmDialog = useConfirmDialogStore()
  const ok = await confirmDialog.confirm('确定删除该 Skill？此操作不可恢复。', {
    title: '删除确认',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  await deleteSkill(id); await load()
}

// ── MD 导入 ───────────────────────────────────────────────
const mdContent     = ref('')
const parsedPreview = ref(null)
const parseError    = ref('')

const openImport = () => {
  mdContent.value = ''; parsedPreview.value = null; parseError.value = ''
  showImport.value = true
}

const parseMd = () => {
  parseError.value = ''; parsedPreview.value = null
  const text = mdContent.value.trim()
  if (!text) { parseError.value = '请先粘贴 Markdown 内容'; return }

  const result = {
    name: '', description: '', scenario_tags: [], trigger_keywords: [],
    overall_strategy: '', tool_hints: [], forced_tools: [], steps: []
  }

  const titleMatch = text.match(/^#{1,2}\s+(.+)/m)
  if (titleMatch) result.name = titleMatch[1].trim()

  const fieldMap = {
    name:             ['name', '名称'],
    description:      ['description', '描述', 'desc'],
    scenario_tags:    ['tags', 'tag', '标签'],
    trigger_keywords: ['keywords', '关键词', 'triggers'],
    overall_strategy: ['overall_strategy', 'strategy', '策略', '整体目标'],
  }

  // 解析步骤（### 步骤N 开头）
  const stepSections = text.split(/(?=^#{2,3}\s+步骤\d+)/m)
  const mainSection  = stepSections[0]

  // 解析主区域字段
  const parseFields = (section, target) => {
    for (const line of section.split('\n')) {
      const m = line.match(/^[\*\-\s]*\*{0,2}([^:*\n]+)\*{0,2}\s*:\s*(.+)/)
      if (!m) continue
      const key = m[1].trim().toLowerCase()
      const val = m[2].trim()
      for (const [field, aliases] of Object.entries(fieldMap)) {
        if (aliases.some(a => a.toLowerCase() === key)) {
          if (['scenario_tags', 'trigger_keywords'].includes(field)) {
            target[field] = val.split(/[,，]/).map(s => s.trim()).filter(Boolean)
          } else {
            target[field] = val
          }
        }
      }
    }
  }
  parseFields(mainSection, result)

  // 解析步骤块
  const stepFieldMap = {
    tool_hints:      ['tool_hints', '建议工具', 'hints'],
    forced_tools:    ['forced_tools', '强制工具', 'forced'],
    strategy_prompt: ['strategy', '策略', '本步策略', 'strategy_prompt'],
    description:     ['description', '描述', 'desc'],
  }
  for (let i = 1; i < stepSections.length; i++) {
    const sec   = stepSections[i]
    const nameM = sec.match(/^#{2,3}\s+步骤\d+\s*(.*)/)
    const step  = { name: nameM ? nameM[1].trim() : `步骤${i}`, description: '', tool_hints: [], forced_tools: [], strategy_prompt: '' }
    for (const line of sec.split('\n')) {
      const m = line.match(/^[\*\-\s]*\*{0,2}([^:*\n]+)\*{0,2}\s*:\s*(.+)/)
      if (!m) continue
      const key = m[1].trim().toLowerCase()
      const val = m[2].trim()
      for (const [field, aliases] of Object.entries(stepFieldMap)) {
        if (aliases.some(a => a.toLowerCase() === key)) {
          if (['tool_hints', 'forced_tools'].includes(field)) {
            step[field] = val.split(/[,，]/).map(s => s.trim()).filter(Boolean)
          } else {
            step[field] = val
          }
        }
      }
    }
    result.steps.push(step)
  }

  if (!result.name) { parseError.value = '未能解析到名称，请确保有 # 标题或 name 字段'; return }
  parsedPreview.value = result
}

const importFromMd = async () => {
  if (!parsedPreview.value) return
  saving.value = true
  try {
    const result = await createSkill(parsedPreview.value)
    if (result?.success) { showImport.value = false; await load() }
    else ElMessage.error(`导入失败: ${result?.message || '未知'}`)
  } finally { saving.value = false }
}

onMounted(() => { load(); document.addEventListener('click', closeAllDrops) })
onBeforeUnmount(() => document.removeEventListener('click', closeAllDrops))
</script>

<style scoped>
.skill-view {
  height: 100%; padding: var(--space-4-5); overflow-y: auto;
  display: flex; flex-direction: column; gap: var(--space-3-5); background: var(--color-surface-sunken);
}

/* ── 工具栏 ── */
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--space-2-5); }
.tag-tabs { display: flex; gap: var(--space-1-5); flex-wrap: wrap; }
.tag-btn {
  display: flex; align-items: center; gap: var(--space-1); padding: var(--space-1-5) var(--space-3); border-radius: var(--radius-lg);
  border: 1px solid var(--color-border); background: var(--color-surface); font-size: var(--text-sm); color: var(--color-text-secondary); cursor: pointer; transition: var(--transition-interactive);
}
.tag-btn:hover  { border-color: var(--color-primary); color: var(--color-primary); }
.tag-btn.active { background: var(--color-primary); border-color: var(--color-primary); color: var(--color-text-on-primary); }
.tag-count { background: var(--overlay-dark-subtle); padding: var(--space-px) var(--space-1-5); border-radius: var(--radius-sm); font-size: var(--text-xs); }
.tag-btn.active .tag-count { background: var(--color-surface-on-dark-strong); }
.toolbar-right { display: flex; gap: var(--space-2); }
.refresh-btn {
  padding: var(--space-2) var(--space-2-5); border-radius: var(--radius-sm); border: 1px solid var(--color-border);
  background: var(--color-surface); color: var(--color-text); cursor: pointer; transition: var(--transition-interactive);
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.import-btn {
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-2) var(--space-3-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-primary); background: var(--color-surface); color: var(--color-primary); font-size: var(--text-sm); cursor: pointer;
}
.import-btn:hover { background: var(--color-surface-raised); }
.create-btn {
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm);
  border: none; background: var(--color-primary); color: var(--color-text-on-primary); font-size: var(--text-sm); cursor: pointer;
}
.create-btn:hover { background: var(--color-primary-hover); }

/* ── Skill 卡片 ── */
.skill-list { display: flex; flex-direction: column; gap: var(--space-3); }
.skill-card {
  background: var(--color-surface); border: 0.5px solid var(--color-border); border-radius: var(--radius-md); padding: var(--space-4); transition: border-color var(--duration-base);
}
.skill-card:hover   { border-color: var(--color-primary-soft); }
.skill-card.disabled { opacity: 0.55; }
.skill-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-2); }
.skill-title-row { display: flex; align-items: center; gap: var(--space-2-5); flex-wrap: wrap; }
.skill-name   { font-size: var(--text-md); font-weight: var(--weight-medium); color: var(--color-text); }
.skill-tags   { display: flex; gap: var(--space-1); flex-wrap: wrap; }
.stag { font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); background: var(--color-surface-raised); color: var(--color-primary); border-radius: var(--radius-sm); }
.step-count { font-size: var(--text-xs); color: var(--color-text-muted); display: flex; align-items: center; gap: var(--space-1); }
.skill-actions { display: flex; gap: var(--space-1-5); align-items: center; flex-shrink: 0; }
.toggle-btn {
  font-size: var(--text-xs); padding: var(--space-1) var(--space-3); border-radius: var(--radius-lg); border: none; cursor: pointer; font-weight: var(--weight-medium);
}
.toggle-btn.on  { background: var(--color-success-soft); color: var(--color-success); }
.toggle-btn.off { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.icon-btn {
  width: 30px; height: 30px; border-radius: var(--radius-sm); border: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center; font-size: var(--text-sm-2);
}
.edit-btn { background: var(--color-surface-raised); color: var(--color-primary); }
.del-btn  { background: var(--color-danger-soft); color: var(--color-danger); }
.skill-desc { font-size: var(--text-sm); color: var(--color-text-secondary); margin: 0 0 var(--space-2); }
.strategy-box {
  background: var(--color-primary-soft); border-left: 3px solid var(--color-primary); border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  padding: var(--space-1-5) var(--space-3); font-size: var(--text-sm-2); color: var(--color-text); display: flex; gap: var(--space-2); margin-bottom: var(--space-2);
}
.strategy-box i { color: var(--color-primary); flex-shrink: 0; }

/* ── 步骤预览 ── */
.steps-preview { display: flex; flex-direction: column; gap: var(--space-1); margin-bottom: var(--space-2); }
.step-chip {
  display: flex; align-items: center; gap: var(--space-1-5);
  background: var(--color-surface-sunken); border: 0.5px solid var(--color-border); border-radius: var(--radius-sm); padding: var(--space-1) var(--space-2-5);
}
.step-num {
  width: 18px; height: 18px; border-radius: 50%;
  background: var(--color-primary); color: var(--color-text-on-primary); font-size: var(--text-2xs);
  display: flex; align-items: center; justify-content: center; font-weight: var(--weight-semibold); flex-shrink: 0;
}
.step-chip-name { font-size: var(--text-sm-2); color: var(--color-text); font-weight: var(--weight-medium); margin-right: var(--space-1); }
.tool-badge { font-size: var(--text-xs); padding: var(--space-px) var(--space-1-5); border-radius: var(--radius-sm); }
.tool-badge.hint   { background: var(--color-primary-soft); color: var(--color-primary); }
.tool-badge.forced { background: var(--color-danger-soft); color: var(--color-danger); }

.meta-group { display: flex; align-items: center; gap: var(--space-1-5); flex-wrap: wrap; }
.meta-label { font-size: var(--text-xs); color: var(--color-text-muted); min-width: 42px; flex-shrink: 0; }
.meta-empty { font-size: var(--text-xs); color: var(--color-text-muted); }
.kw-badge { font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); background: var(--color-warn-soft); color: var(--color-warn); border-radius: var(--radius-sm); }

/* ── 空/加载 ── */






/* ── 弹窗 ── */
.modal-overlay {
  position: fixed; inset: 0; background: var(--color-scrim);
  display: flex; align-items: center; justify-content: center; z-index: 200;
}
.modal-box {
  background: var(--color-surface); border-radius: var(--radius-md); width: 560px; max-height: 90vh; overflow-y: auto;
}
.import-box { width: 600px; }
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-4) var(--space-4-5) var(--space-3-5); border-bottom: 0.5px solid var(--color-border);
  position: sticky; top: 0; background: var(--color-surface); z-index: 2;
}
.modal-title { font-size: var(--text-md); font-weight: var(--weight-medium); }
.modal-close { background: none; border: none; color: var(--color-text-muted); cursor: pointer; font-size: var(--text-md); }
.modal-body  { padding: var(--space-4) var(--space-4-5); display: flex; flex-direction: column; gap: var(--space-3); }

.section-title {
  font-size: var(--text-sm); font-weight: var(--weight-semibold); color: var(--color-primary);
  display: flex; align-items: center; gap: var(--space-1-5); padding: var(--space-1) 0; border-bottom: 1px solid var(--color-surface-raised);
}
.steps-title { justify-content: space-between; margin-top: var(--space-1); }
.add-step-btn {
  display: flex; align-items: center; gap: var(--space-1); padding: var(--space-1) var(--space-3); border-radius: var(--radius-sm);
  border: 1px solid var(--color-primary); background: var(--color-surface); color: var(--color-primary); font-size: var(--text-sm-2); cursor: pointer;
}
.add-step-btn:hover { background: var(--color-surface-raised); }
.steps-empty { font-size: var(--text-sm); color: var(--color-text-muted); padding: var(--space-2-5) 0; }

/* ── 步骤编辑器 ── */
.step-editor {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm); overflow: hidden; margin-bottom: var(--space-1);
}
.step-editor-header {
  display: flex; align-items: center; gap: var(--space-2);
  background: var(--color-surface-sunken); padding: var(--space-2) var(--space-3); border-bottom: 0.5px solid var(--color-border);
}
.step-editor-num {
  font-size: var(--text-xs); font-weight: var(--weight-semibold); color: var(--color-primary);
  background: var(--color-surface-raised); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-xs); flex-shrink: 0;
}
.step-name-input {
  flex: 1; border: none; background: transparent; font-size: var(--text-sm);
  font-weight: var(--weight-medium); color: var(--color-text); outline: none;
}
.step-name-input::placeholder { color: var(--color-text-muted); }
.step-order-btns { display: flex; gap: var(--space-1); }
.order-btn {
  width: 26px; height: 26px; border-radius: var(--radius-xs); border: 1px solid var(--color-border);
  background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xs);
}
.order-btn:disabled { opacity: 0.3; cursor: not-allowed; }
.del-step-btn {
  width: 26px; height: 26px; border-radius: var(--radius-xs); border: none;
  background: var(--color-danger-soft); color: var(--color-danger); cursor: pointer; display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xs);
}
.step-editor-body { padding: var(--space-3); display: flex; flex-direction: column; gap: var(--space-2-5); }

/* ── 表单 ── */
.form-row { display: flex; flex-direction: column; gap: var(--space-1); }
.form-row label {
  font-size: var(--text-sm-2); color: var(--color-text-secondary); font-weight: var(--weight-medium); display: flex; align-items: center; gap: var(--space-1-5);
}
.req { color: var(--color-danger); }
.tip { font-size: var(--text-xs); color: var(--color-text-muted); font-weight: var(--weight-normal); }
.form-row input,
.form-row textarea {
  padding: var(--space-1-5) var(--space-2-5); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  font-size: var(--text-sm); outline: none; transition: border-color var(--duration-base);
  font-family: inherit; resize: vertical;
}
.form-row input:focus,
.form-row textarea:focus { border-color: var(--color-primary); }
.toggle-row { flex-direction: row; align-items: center; gap: var(--space-3); }
.modal-footer {
  display: flex; justify-content: flex-end; gap: var(--space-2);
  padding: var(--space-3-5) var(--space-4-5); border-top: 0.5px solid var(--color-border);
  position: sticky; bottom: 0; background: var(--color-surface); z-index: 2;
}
.btn-cancel {
  padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm); border: 1px solid var(--color-border);
  background: var(--color-surface); color: var(--color-text); cursor: pointer; font-size: var(--text-sm);
}
.btn-secondary {
  padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm); border: 1px solid var(--color-primary);
  background: var(--color-surface); color: var(--color-primary); cursor: pointer; font-size: var(--text-sm);
}
.btn-confirm {
  padding: var(--space-2) var(--space-4); border-radius: var(--radius-sm); border: none; background: var(--color-primary);
  color: var(--color-text-on-primary); cursor: pointer; font-size: var(--text-sm); display: flex; align-items: center; gap: var(--space-1-5);
}
.btn-confirm:hover:not(:disabled) { background: var(--color-primary-hover); }
.btn-confirm:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 工具多选（ToolSelect 组件样式全局生效） ── */
:deep(.tms-wrap) { position: relative; }
:deep(.selected-tools) {
  min-height: 36px; padding: var(--space-1) var(--space-6) var(--space-1) var(--space-2); border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); cursor: pointer; display: flex; flex-wrap: wrap;
  gap: var(--space-1); align-items: center; background: var(--color-surface); transition: border-color var(--duration-base); position: relative;
}
:deep(.selected-tools:hover) { border-color: var(--color-primary); }
:deep(.placeholder) { font-size: var(--text-sm); color: var(--color-text-muted); padding: var(--space-0-5) var(--space-1); }
:deep(.selected-tag) {
  display: flex; align-items: center; gap: var(--space-1); font-size: var(--text-xs); padding: var(--space-0-5) var(--space-2); border-radius: var(--radius-xs);
}
:deep(.selected-tag.hint)   { background: var(--color-primary-soft); color: var(--color-primary); }
:deep(.selected-tag.forced) { background: var(--color-danger-soft); color: var(--color-danger); }
:deep(.selected-tag i) { cursor: pointer; opacity: 0.7; font-size: var(--text-2xs); }
:deep(.arrow) {
  position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
  color: var(--color-text-muted); font-size: var(--text-xs); transition: transform var(--duration-base); pointer-events: none;
}
:deep(.arrow.open) { transform: translateY(-50%) rotate(180deg); }
:deep(.tool-dropdown) {
  position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 100;
  background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  box-shadow: var(--shadow-raised);
}
:deep(.tool-search) { padding: var(--space-2); border-bottom: 0.5px solid var(--color-border); }
:deep(.tool-search input) {
  width: 100%; padding: var(--space-1) var(--space-2-5); border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  font-size: var(--text-sm-2); outline: none; box-sizing: border-box;
}
:deep(.tool-option-list) { max-height: 180px; overflow-y: auto; }
:deep(.tool-option) {
  display: flex; align-items: center; gap: var(--space-2); padding: var(--space-1-5) var(--space-3);
  font-size: var(--text-sm); color: var(--color-text); cursor: pointer; transition: background var(--duration-fast);
}
:deep(.tool-option:hover)    { background: var(--color-surface-sunken); }
:deep(.tool-option.selected) { background: var(--color-surface-raised); color: var(--color-primary); }
:deep(.check-icon)           { font-size: var(--text-xs); opacity: 0; width: 12px; flex-shrink: 0; }
:deep(.tool-option.selected .check-icon) { opacity: 1; }
:deep(.tool-info) { display: flex; flex-direction: column; gap: var(--space-px); min-width: 0; }
:deep(.tool-name) { font-size: var(--text-sm); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
:deep(.tool-desc) { font-size: var(--text-xs); color: var(--color-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
:deep(.tool-empty) { padding: var(--space-3); font-size: var(--text-sm); color: var(--color-text-muted); text-align: center; }

/* ── MD 导入 ── */
.import-hint {
  font-size: var(--text-sm-2); color: var(--color-text-secondary); background: var(--color-surface-sunken);
  padding: var(--space-2-5) var(--space-3); border-radius: var(--radius-sm); line-height: var(--leading-prose);
}
.import-hint code {
  background: var(--color-surface-sunken); padding: var(--space-px) var(--space-1); border-radius: var(--radius-xs);
  font-family: monospace; font-size: var(--text-xs); color: var(--color-text);
}
.import-example { }
.example-label { font-size: var(--text-xs); color: var(--color-text-muted); margin-bottom: var(--space-1); }
.example-code {
  background: var(--surface-media); color: var(--color-on-media); padding: var(--space-3); border-radius: var(--radius-sm);
  font-size: var(--text-xs); line-height: var(--leading-relaxed); overflow-x: auto; margin: 0; font-family: 'Consolas', monospace;
}
.md-input { font-family: 'Consolas', monospace; font-size: var(--text-sm); }
.preview-box {
  background: var(--color-surface-raised); border-radius: var(--radius-sm); padding: var(--space-3); display: flex; flex-direction: column; gap: var(--space-1);
}
.preview-label { font-size: var(--text-xs); color: var(--color-primary); font-weight: var(--weight-medium); margin-bottom: var(--space-0-5); }
.preview-item  { display: flex; gap: var(--space-2-5); font-size: var(--text-sm); }
.pk { color: var(--color-text-secondary); min-width: 56px; flex-shrink: 0; }
.pv { color: var(--color-text); }
.parse-error {
  background: var(--color-danger-soft); color: var(--color-danger); padding: var(--space-2-5) var(--space-3);
  border-radius: var(--radius-sm); font-size: var(--text-sm); display: flex; gap: var(--space-2); align-items: center;
}
</style>
