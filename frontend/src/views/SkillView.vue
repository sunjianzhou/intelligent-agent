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
        <button class="refresh-btn" :class="{ spinning: loading }" @click="load">
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
        <div class="meta-group" style="margin-top:8px">
          <span class="meta-label">触发词</span>
          <span v-for="kw in skill.trigger_keywords" :key="kw" class="kw-badge">{{ kw }}</span>
          <span v-if="!skill.trigger_keywords.length" class="meta-empty">-</span>
        </div>
      </div>
    </div>

    <div v-else-if="!loading" class="empty-state">
      <i class="fas fa-magic empty-icon" />
      <p class="empty-title">暂无 Skill</p>
      <p class="empty-sub">点击右上角「新建 Skill」或「从 MD 导入」</p>
    </div>
    <div v-if="loading" class="loading-state">
      <i class="fas fa-circle-notch fa-spin" /><span>加载中...</span>
    </div>

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
    else alert(`保存失败: ${result?.message || '未知'}`)
  } finally { saving.value = false }
}

const toggle = async (id) => { await toggleSkill(id); await load() }
const remove = async (id) => {
  if (!confirm('确定删除该 Skill？')) return
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
    else alert(`导入失败: ${result?.message || '未知'}`)
  } finally { saving.value = false }
}

onMounted(() => { load(); document.addEventListener('click', closeAllDrops) })
onBeforeUnmount(() => document.removeEventListener('click', closeAllDrops))
</script>

<style scoped>
.skill-view {
  height: 100%; padding: 20px; overflow-y: auto;
  display: flex; flex-direction: column; gap: 14px; background: #f8f9fa;
}

/* ── 工具栏 ── */
.toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
.tag-tabs { display: flex; gap: 6px; flex-wrap: wrap; }
.tag-btn {
  display: flex; align-items: center; gap: 5px; padding: 6px 12px; border-radius: 20px;
  border: 1px solid #e0e3e8; background: white; font-size: 0.85rem; color: #666; cursor: pointer; transition: all 0.2s;
}
.tag-btn:hover  { border-color: #667eea; color: #667eea; }
.tag-btn.active { background: #667eea; border-color: #667eea; color: white; }
.tag-count { background: rgba(0,0,0,0.1); padding: 1px 6px; border-radius: 10px; font-size: 0.75rem; }
.tag-btn.active .tag-count { background: rgba(255,255,255,0.25); }
.toolbar-right { display: flex; gap: 8px; }
.refresh-btn {
  padding: 8px 10px; border-radius: 8px; border: 1px solid #e0e3e8;
  background: white; color: #555; cursor: pointer; transition: all 0.2s;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.import-btn {
  display: flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 8px;
  border: 1px solid #667eea; background: white; color: #667eea; font-size: 0.88rem; cursor: pointer;
}
.import-btn:hover { background: #f0f2ff; }
.create-btn {
  display: flex; align-items: center; gap: 6px; padding: 8px 16px; border-radius: 8px;
  border: none; background: #667eea; color: white; font-size: 0.88rem; cursor: pointer;
}
.create-btn:hover { background: #5a6fd6; }

/* ── Skill 卡片 ── */
.skill-list { display: flex; flex-direction: column; gap: 12px; }
.skill-card {
  background: white; border: 0.5px solid #e8eaed; border-radius: 12px; padding: 16px; transition: border-color 0.2s;
}
.skill-card:hover   { border-color: #c5caf5; }
.skill-card.disabled { opacity: 0.55; }
.skill-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.skill-title-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.skill-name   { font-size: 1rem; font-weight: 500; color: #333; }
.skill-tags   { display: flex; gap: 5px; flex-wrap: wrap; }
.stag { font-size: 0.75rem; padding: 2px 8px; background: #f0f2ff; color: #667eea; border-radius: 8px; }
.step-count { font-size: 0.75rem; color: #aaa; display: flex; align-items: center; gap: 4px; }
.skill-actions { display: flex; gap: 6px; align-items: center; flex-shrink: 0; }
.toggle-btn {
  font-size: 0.78rem; padding: 4px 12px; border-radius: 20px; border: none; cursor: pointer; font-weight: 500;
}
.toggle-btn.on  { background: #e8f5e9; color: #2e7d32; }
.toggle-btn.off { background: #f5f5f5; color: #888; }
.icon-btn {
  width: 30px; height: 30px; border-radius: 6px; border: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center; font-size: 0.8rem;
}
.edit-btn { background: #f0f2ff; color: #667eea; }
.del-btn  { background: #fce4e4; color: #c62828; }
.skill-desc { font-size: 0.88rem; color: #666; margin: 0 0 8px; }
.strategy-box {
  background: #f8f9ff; border-left: 3px solid #667eea; border-radius: 0 6px 6px 0;
  padding: 7px 12px; font-size: 0.82rem; color: #555; display: flex; gap: 8px; margin-bottom: 8px;
}
.strategy-box i { color: #667eea; flex-shrink: 0; }

/* ── 步骤预览 ── */
.steps-preview { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
.step-chip {
  display: flex; align-items: center; gap: 6px;
  background: #fafafa; border: 0.5px solid #ececec; border-radius: 6px; padding: 4px 10px;
}
.step-num {
  width: 18px; height: 18px; border-radius: 50%;
  background: #667eea; color: white; font-size: 0.7rem;
  display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0;
}
.step-chip-name { font-size: 0.82rem; color: #444; font-weight: 500; margin-right: 4px; }
.tool-badge { font-size: 0.72rem; padding: 1px 7px; border-radius: 6px; }
.tool-badge.hint   { background: #e3f2fd; color: #1976d2; }
.tool-badge.forced { background: #fce4e4; color: #c62828; }

.meta-group { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.meta-label { font-size: 0.78rem; color: #aaa; min-width: 42px; flex-shrink: 0; }
.meta-empty { font-size: 0.78rem; color: #ddd; }
.kw-badge { font-size: 0.75rem; padding: 2px 8px; background: #fff8e1; color: #f57f17; border-radius: 8px; }

/* ── 空/加载 ── */
.empty-state {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 60px 20px; background: white; border-radius: 12px; border: 0.5px solid #e8eaed;
}
.empty-icon  { font-size: 2.5rem; color: #ddd; }
.empty-title { font-size: 1rem; font-weight: 500; color: #666; margin: 0; }
.empty-sub   { font-size: 0.85rem; color: #aaa; margin: 0; }
.loading-state {
  display: flex; align-items: center; justify-content: center; gap: 10px; padding: 40px; color: #888;
}

/* ── 弹窗 ── */
.modal-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center; z-index: 200;
}
.modal-box {
  background: white; border-radius: 14px; width: 560px; max-height: 90vh; overflow-y: auto;
}
.import-box { width: 600px; }
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 20px 14px; border-bottom: 0.5px solid #e8eaed;
  position: sticky; top: 0; background: white; z-index: 2;
}
.modal-title { font-size: 1rem; font-weight: 500; }
.modal-close { background: none; border: none; color: #aaa; cursor: pointer; font-size: 1rem; }
.modal-body  { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }

.section-title {
  font-size: 0.85rem; font-weight: 600; color: #667eea;
  display: flex; align-items: center; gap: 6px; padding: 4px 0; border-bottom: 1px solid #f0f2ff;
}
.steps-title { justify-content: space-between; margin-top: 4px; }
.add-step-btn {
  display: flex; align-items: center; gap: 4px; padding: 4px 12px; border-radius: 6px;
  border: 1px solid #667eea; background: white; color: #667eea; font-size: 0.82rem; cursor: pointer;
}
.add-step-btn:hover { background: #f0f2ff; }
.steps-empty { font-size: 0.85rem; color: #bbb; padding: 10px 0; }

/* ── 步骤编辑器 ── */
.step-editor {
  border: 1px solid #e8eaed; border-radius: 10px; overflow: hidden; margin-bottom: 4px;
}
.step-editor-header {
  display: flex; align-items: center; gap: 8px;
  background: #f8f9fa; padding: 8px 12px; border-bottom: 0.5px solid #e8eaed;
}
.step-editor-num {
  font-size: 0.75rem; font-weight: 600; color: #667eea;
  background: #f0f2ff; padding: 2px 8px; border-radius: 4px; flex-shrink: 0;
}
.step-name-input {
  flex: 1; border: none; background: transparent; font-size: 0.9rem;
  font-weight: 500; color: #333; outline: none;
}
.step-name-input::placeholder { color: #bbb; }
.step-order-btns { display: flex; gap: 4px; }
.order-btn {
  width: 26px; height: 26px; border-radius: 4px; border: 1px solid #e0e3e8;
  background: white; color: #888; cursor: pointer; display: flex; align-items: center; justify-content: center;
  font-size: 0.75rem;
}
.order-btn:disabled { opacity: 0.3; cursor: not-allowed; }
.del-step-btn {
  width: 26px; height: 26px; border-radius: 4px; border: none;
  background: #fce4e4; color: #c62828; cursor: pointer; display: flex; align-items: center; justify-content: center;
  font-size: 0.75rem;
}
.step-editor-body { padding: 12px; display: flex; flex-direction: column; gap: 10px; }

/* ── 表单 ── */
.form-row { display: flex; flex-direction: column; gap: 4px; }
.form-row label {
  font-size: 0.82rem; color: #666; font-weight: 500; display: flex; align-items: center; gap: 6px;
}
.req { color: #f44336; }
.tip { font-size: 0.75rem; color: #aaa; font-weight: 400; }
.form-row input,
.form-row textarea {
  padding: 7px 11px; border: 1px solid #e0e3e8; border-radius: 7px;
  font-size: 0.88rem; outline: none; transition: border-color 0.2s;
  font-family: inherit; resize: vertical;
}
.form-row input:focus,
.form-row textarea:focus { border-color: #667eea; }
.toggle-row { flex-direction: row; align-items: center; gap: 12px; }
.modal-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 14px 20px; border-top: 0.5px solid #e8eaed;
  position: sticky; bottom: 0; background: white; z-index: 2;
}
.btn-cancel {
  padding: 8px 18px; border-radius: 8px; border: 1px solid #e0e3e8;
  background: white; color: #555; cursor: pointer; font-size: 0.88rem;
}
.btn-secondary {
  padding: 8px 18px; border-radius: 8px; border: 1px solid #667eea;
  background: white; color: #667eea; cursor: pointer; font-size: 0.88rem;
}
.btn-confirm {
  padding: 8px 18px; border-radius: 8px; border: none; background: #667eea;
  color: white; cursor: pointer; font-size: 0.88rem; display: flex; align-items: center; gap: 6px;
}
.btn-confirm:hover:not(:disabled) { background: #5a6fd6; }
.btn-confirm:disabled { opacity: 0.5; cursor: not-allowed; }

/* ── 工具多选（ToolSelect 组件样式全局生效） ── */
:deep(.tms-wrap) { position: relative; }
:deep(.selected-tools) {
  min-height: 36px; padding: 4px 32px 4px 8px; border: 1px solid #e0e3e8;
  border-radius: 7px; cursor: pointer; display: flex; flex-wrap: wrap;
  gap: 4px; align-items: center; background: white; transition: border-color 0.2s; position: relative;
}
:deep(.selected-tools:hover) { border-color: #667eea; }
:deep(.placeholder) { font-size: 0.85rem; color: #bbb; padding: 2px 4px; }
:deep(.selected-tag) {
  display: flex; align-items: center; gap: 4px; font-size: 0.78rem; padding: 2px 8px; border-radius: 5px;
}
:deep(.selected-tag.hint)   { background: #e3f2fd; color: #1976d2; }
:deep(.selected-tag.forced) { background: #fce4e4; color: #c62828; }
:deep(.selected-tag i) { cursor: pointer; opacity: 0.7; font-size: 0.7rem; }
:deep(.arrow) {
  position: absolute; right: 10px; top: 50%; transform: translateY(-50%);
  color: #aaa; font-size: 0.72rem; transition: transform 0.2s; pointer-events: none;
}
:deep(.arrow.open) { transform: translateY(-50%) rotate(180deg); }
:deep(.tool-dropdown) {
  position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 100;
  background: white; border: 1px solid #e0e3e8; border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.1);
}
:deep(.tool-search) { padding: 8px; border-bottom: 0.5px solid #f0f0f0; }
:deep(.tool-search input) {
  width: 100%; padding: 5px 10px; border: 1px solid #e0e3e8; border-radius: 6px;
  font-size: 0.83rem; outline: none; box-sizing: border-box;
}
:deep(.tool-option-list) { max-height: 180px; overflow-y: auto; }
:deep(.tool-option) {
  display: flex; align-items: center; gap: 8px; padding: 7px 12px;
  font-size: 0.85rem; color: #555; cursor: pointer; transition: background 0.12s;
}
:deep(.tool-option:hover)    { background: #f8f9fa; }
:deep(.tool-option.selected) { background: #f0f2ff; color: #667eea; }
:deep(.check-icon)           { font-size: 0.72rem; opacity: 0; width: 12px; flex-shrink: 0; }
:deep(.tool-option.selected .check-icon) { opacity: 1; }
:deep(.tool-info) { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
:deep(.tool-name) { font-size: 0.85rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
:deep(.tool-desc) { font-size: 0.72rem; color: #aaa; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
:deep(.tool-empty) { padding: 12px; font-size: 0.85rem; color: #aaa; text-align: center; }

/* ── MD 导入 ── */
.import-hint {
  font-size: 0.82rem; color: #888; background: #f8f9fa;
  padding: 10px 12px; border-radius: 8px; line-height: 1.7;
}
.import-hint code {
  background: #e8eaed; padding: 1px 5px; border-radius: 4px;
  font-family: monospace; font-size: 0.78rem; color: #333;
}
.import-example { }
.example-label { font-size: 0.78rem; color: #aaa; margin-bottom: 4px; }
.example-code {
  background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px;
  font-size: 0.78rem; line-height: 1.6; overflow-x: auto; margin: 0; font-family: 'Consolas', monospace;
}
.md-input { font-family: 'Consolas', monospace; font-size: 0.85rem; }
.preview-box {
  background: #f0f2ff; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 5px;
}
.preview-label { font-size: 0.78rem; color: #667eea; font-weight: 500; margin-bottom: 2px; }
.preview-item  { display: flex; gap: 10px; font-size: 0.85rem; }
.pk { color: #888; min-width: 56px; flex-shrink: 0; }
.pv { color: #333; }
.parse-error {
  background: #fce4e4; color: #c62828; padding: 10px 12px;
  border-radius: 8px; font-size: 0.85rem; display: flex; gap: 8px; align-items: center;
}
</style>