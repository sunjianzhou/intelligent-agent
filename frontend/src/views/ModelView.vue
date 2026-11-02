<template>
  <div class="model-view">
    <!-- 当前模型区 -->
    <div class="section current-section">
      <div class="section-title">
        <i class="fas fa-bolt" /> 当前激活模型
      </div>
      <div class="current-card">
        <div class="current-icon">
          <i :class="isCloudMode ? 'fas fa-cloud' : 'fas fa-cube'" />
        </div>
        <div class="current-info">
          <div class="current-name">{{ currentDisplayName || '未选择模型' }}</div>
          <div class="current-meta">
            <span class="badge" :class="isCloudMode ? 'badge-cloud' : 'badge-local'">
              {{ isCloudMode ? '云端推理' : '本地 Ollama' }}
            </span>
            <span class="badge badge-type">LLM</span>
            <span v-if="isCloudMode && currentProvider" class="current-provider">
              {{ PROVIDER_LABELS[currentProvider] || currentProvider }}
            </span>
          </div>
        </div>
        <div class="current-status">
          <span class="status-dot running" />
          正在运行
        </div>
      </div>
    </div>

    <!-- 云端模型区 -->
    <div class="section">
      <div class="section-title">
        <i class="fas fa-cloud" /> 云端模型
        <button class="add-btn" @click="showAddDialog = true">
          <i class="fas fa-plus" /> 添加服务商
        </button>
      </div>

      <div v-if="cloudProviders.length === 0" class="empty-tip">
        <i class="fas fa-cloud-upload-alt" />
        <p>暂无云端服务商配置</p>
        <button class="link-btn" @click="showAddDialog = true">+ 添加第一个服务商</button>
      </div>

      <div v-else class="model-grid">
        <div
          v-for="p in cloudProviders"
          :key="p.id"
          class="model-card cloud-card"
          :class="{ active: p.active }"
        >
          <div class="card-accent cloud-accent" />
          <div class="card-header">
            <div class="card-icon cloud-icon">
              <i class="fas fa-cloud" />
            </div>
            <div class="card-info">
              <div class="card-name">{{ p.name }}</div>
              <div class="card-sub">{{ PROVIDER_LABELS[p.provider] || p.provider }}</div>
            </div>
            <span v-if="p.active" class="active-badge">激活中</span>
          </div>
          <div class="card-model">
            <i class="fas fa-code-branch" /> {{ p.model }}
          </div>
          <div class="card-tags">
            <span class="tag tag-llm">LLM</span>
            <span v-for="ut in modelUsageTags(p.model)" :key="ut.label" class="tag" :class="ut.cls">{{ ut.label }}</span>
            <span class="tag" :class="p.api_key_masked !== '****' ? 'tag-key-ok' : 'tag-key-missing'">
              <i :class="p.api_key_masked !== '****' ? 'fas fa-key' : 'fas fa-exclamation-triangle'" />
              {{ p.api_key_masked !== '****' ? 'KEY 已配置' : 'KEY 缺失' }}
            </span>
          </div>
          <div class="card-actions">
            <button
              class="btn-activate"
              :class="{ 'btn-active': p.active }"
              :disabled="p.active || activating === p.id"
              @click="activateCloud(p)"
            >
              <i v-if="activating === p.id" class="fas fa-circle-notch fa-spin" />
              <i v-else-if="p.active" class="fas fa-check" />
              <i v-else class="fas fa-play" />
              {{ p.active ? '已激活' : '激活' }}
            </button>
            <button class="btn-icon" title="编辑" @click="openEdit(p)">
              <i class="fas fa-edit" />
            </button>
            <button class="btn-icon danger" title="删除" @click="removeProvider(p)">
              <i class="fas fa-trash" />
            </button>
          </div>
        </div>
      </div>

      <!-- 已激活云端时的"切回本地"按钮 -->
      <div v-if="isCloudMode" class="switch-local-row">
        <button class="switch-local-btn" @click="switchToLocal" :disabled="deactivating">
          <i v-if="deactivating" class="fas fa-circle-notch fa-spin" />
          <i v-else class="fas fa-server" />
          切换回本地 Ollama
        </button>
      </div>
    </div>

    <!-- 本地模型区 -->
    <div class="section">
      <div class="section-title">
        <i class="fas fa-server" /> 本地模型（Ollama）
        <span class="section-count">{{ localModels.length }} 个可用</span>
      </div>

      <div v-if="localModels.length === 0" class="empty-tip">
        <i class="fas fa-box-open" />
        <p>未检测到本地模型，请先通过 Ollama 拉取模型</p>
        <code class="hint-code">ollama pull dolphin-mistral</code>
      </div>

      <div v-else class="model-grid">
        <div
          v-for="m in localModels"
          :key="m.name"
          class="model-card local-card"
          :class="{ active: !isCloudMode && m.name === currentModel }"
        >
          <div class="card-accent local-accent" />
          <div class="card-header">
            <div class="card-icon local-icon">
              <i class="fas fa-cube" />
            </div>
            <div class="card-info">
              <div class="card-name">{{ m.name }}</div>
              <div class="card-sub" v-if="m.size_gb">{{ m.size_gb }} GB</div>
              <div class="card-sub" v-else>本地模型</div>
            </div>
            <span v-if="!isCloudMode && m.name === currentModel" class="active-badge">激活中</span>
          </div>
          <div class="card-tags">
            <span class="tag tag-llm">LLM</span>
            <span v-for="ut in modelUsageTags(m.name)" :key="ut.label" class="tag" :class="ut.cls">{{ ut.label }}</span>
            <span class="tag" :class="m.loaded ? 'tag-loaded' : 'tag-unloaded'">
              <i :class="m.loaded ? 'fas fa-memory' : 'fas fa-hdd'" />
              {{ m.loaded ? '显存已加载' : '未加载' }}
            </span>
          </div>
          <div class="card-actions">
            <button
              class="btn-activate"
              :class="{ 'btn-active': !isCloudMode && m.name === currentModel }"
              :disabled="(!isCloudMode && m.name === currentModel) || activating === m.name"
              @click="activateLocal(m.name)"
            >
              <i v-if="activating === m.name" class="fas fa-circle-notch fa-spin" />
              <i v-else-if="!isCloudMode && m.name === currentModel" class="fas fa-check" />
              <i v-else class="fas fa-play" />
              {{ (!isCloudMode && m.name === currentModel) ? '已激活' : '激活' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 添加/编辑服务商对话框 -->
    <div v-if="showAddDialog || editingProvider" class="dialog-backdrop" @click.self="closeDialog">
      <div class="dialog">
        <div class="dialog-title">
          {{ editingProvider ? '编辑服务商' : '添加云端服务商' }}
          <button class="dialog-close" @click="closeDialog"><i class="fas fa-times" /></button>
        </div>
        <div class="dialog-body">
          <label class="form-label">显示名称</label>
          <input v-model="form.name" class="form-input" placeholder="如：我的 DeepSeek" />

          <label class="form-label">服务商</label>
          <select v-model="form.provider" class="form-input" @change="onProviderChange">
            <option value="">请选择服务商</option>
            <option v-for="(label, key) in PROVIDER_LABELS" :key="key" :value="key">{{ label }}</option>
            <option value="custom">自定义</option>
          </select>

          <label class="form-label">API Base URL</label>
          <input v-model="form.base_url" class="form-input" placeholder="如：https://api.openai.com/v1" />

          <label class="form-label">模型名称</label>
          <input v-model="form.model" class="form-input" placeholder="如：gpt-4o" />

          <label class="form-label">API Key {{ editingProvider ? '（留空保持原值）' : '' }}</label>
          <input v-model="form.api_key" class="form-input" type="password" placeholder="sk-..." />
        </div>
        <div class="dialog-footer">
          <button class="btn-cancel" @click="closeDialog">取消</button>
          <button class="btn-save" :disabled="saving || !form.name || !form.model" @click="saveProvider">
            <i v-if="saving" class="fas fa-circle-notch fa-spin" />
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useWebSocketStore } from '@/stores/websocket'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import {
  getModels, getSystemResources,
  listCloudProviders, createCloudProvider, updateCloudProvider, deleteCloudProvider,
  activateCloudProvider, deactivateCloudProviders,
} from '@/services/api'

// 根据模型名猜测用途标签
function modelUsageTags(name = '') {
  const n = name.toLowerCase()
  const tags = []
  if (/tts|voice|speech|audio|whisper/.test(n)) tags.push({ label: '语音', cls: 'tag-voice' })
  else if (/vision|vl|img|image|multimodal|4o|claude-3/.test(n)) tags.push({ label: '图片', cls: 'tag-img' })
  else tags.push({ label: '对话', cls: 'tag-chat' })
  return tags
}

// ── 服务商标签映射（与 cloud_router.py 保持一致）─────────────
const PROVIDER_LABELS = {
  openai:      'OpenAI',
  dashscope:   '阿里云百炼 (DashScope)',
  deepseek:    'DeepSeek',
  zhipu:       '智谱 AI',
  moonshot:    'Moonshot (Kimi)',
  baidu:       '百度千帆',
  siliconflow: '硅基流动 (SiliconFlow)',
}

const PROVIDER_URLS = {
  openai:      'https://api.openai.com/v1',
  dashscope:   'https://dashscope.aliyuncs.com/compatible-mode/v1',
  deepseek:    'https://api.deepseek.com/v1',
  zhipu:       'https://open.bigmodel.cn/api/paas/v4',
  moonshot:    'https://api.moonshot.cn/v1',
  baidu:       'https://qianfan.baidubce.com/v2',
  siliconflow: 'https://api.siliconflow.cn/v1',
}

const store         = useWebSocketStore()
const confirmDialog = useConfirmDialogStore()

// ── 状态 ──────────────────────────────────────────────────────
const cloudProviders = ref([])
const allModels      = ref([])
const vramModels     = ref([])    // Ollama 当前显存中的模型
const activating     = ref('')
const deactivating   = ref(false)
const showAddDialog  = ref(false)
const editingProvider = ref(null)
const saving         = ref(false)

const form = ref({ name: '', provider: '', base_url: '', model: '', api_key: '' })

// ── 计算属性 ──────────────────────────────────────────────────
const isCloudMode       = computed(() => store.cloudMode)
const currentModel      = computed(() => store.currentModel)
const cloudModelName    = computed(() => store.cloudModel)
const currentDisplayName = computed(() => store.modelStatus?.replace(' ☁', '') || '')
const currentProvider   = computed(() => {
  const active = cloudProviders.value.find(p => p.active)
  return active?.provider || ''
})

const localModels = computed(() => {
  const cloudName = cloudModelName.value
  const vramNames = new Set(vramModels.value.map(m => m.name))
  return allModels.value
    .filter(name => name !== cloudName)
    .map(name => ({ name, size_gb: vramModels.value.find(m => m.name === name)?.size_gb || null, loaded: vramNames.has(name) }))
})

// ── 数据加载 ──────────────────────────────────────────────────
const loadData = async () => {
  const [modRes, provRes, resRes] = await Promise.allSettled([
    getModels(), listCloudProviders(), getSystemResources()
  ])
  if (modRes.status === 'fulfilled') allModels.value = [...new Set(modRes.value?.available_models || [])]
  if (provRes.status === 'fulfilled') cloudProviders.value = provRes.value?.providers || []
  if (resRes.status === 'fulfilled') vramModels.value = resRes.value?.ollama_models || []
}

// ── 操作 ──────────────────────────────────────────────────────
const activateCloud = async (p) => {
  activating.value = p.id
  try {
    const res = await activateCloudProvider(p.id)
    if (res?.success) {
      ElMessage.success(`已激活：${p.name}`)
      await loadData()
      await store.loadModels()
    } else {
      ElMessage.error(res?.message || '激活失败')
    }
  } finally {
    activating.value = ''
  }
}

const activateLocal = async (modelName) => {
  activating.value = modelName
  const result = await store.switchModel(modelName)
  if (!result?.success) ElMessage.error(`切换失败: ${result?.message || '未知错误'}`)
  activating.value = ''
}

const switchToLocal = async () => {
  deactivating.value = true
  try {
    const res = await deactivateCloudProviders()
    if (res?.success) {
      ElMessage.success('已切换回本地 Ollama')
      await loadData()
      await store.loadModels()
    } else {
      ElMessage.error(res?.message || '切换失败')
    }
  } finally {
    deactivating.value = false
  }
}

const openEdit = (p) => {
  editingProvider.value = p
  form.value = { name: p.name, provider: p.provider, base_url: p.base_url || '', model: p.model, api_key: '' }
}

const closeDialog = () => {
  showAddDialog.value = false
  editingProvider.value = null
  form.value = { name: '', provider: '', base_url: '', model: '', api_key: '' }
}

const onProviderChange = () => {
  if (PROVIDER_URLS[form.value.provider]) form.value.base_url = PROVIDER_URLS[form.value.provider]
}

const saveProvider = async () => {
  saving.value = true
  try {
    const body = { ...form.value }
    if (!body.api_key) delete body.api_key  // 编辑时留空 = 保持原值
    let res
    if (editingProvider.value) {
      res = await updateCloudProvider(editingProvider.value.id, body)
    } else {
      res = await createCloudProvider(body)
    }
    if (res?.success !== false) {
      ElMessage.success(editingProvider.value ? '已更新服务商' : '服务商已添加')
      closeDialog()
      await loadData()
    } else {
      ElMessage.error(res?.detail || '保存失败')
    }
  } finally {
    saving.value = false
  }
}

const removeProvider = async (p) => {
  confirmDialog.open({
    title: '删除服务商',
    message: `确认删除「${p.name}」？删除后无法恢复。`,
    confirmText: '删除',
    confirmType: 'danger',
    onConfirm: async () => {
      await deleteCloudProvider(p.id)
      ElMessage.success('已删除')
      await loadData()
    },
  })
}

onMounted(loadData)
</script>

<style scoped>
.model-view {
  padding: var(--space-4-5);
  max-width: var(--content-max-width);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

/* ── 分区 ─────────────────────────────────────────────── */
.section { display: flex; flex-direction: column; gap: var(--space-3-5); }
.section-title {
  font-size: var(--text-md);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.section-title i { color: var(--color-primary); font-size: var(--text-base); }
.section-count {
  margin-left: auto;
  font-size: var(--text-xs);
  color: var(--color-text-muted);
  font-weight: var(--weight-normal);
}
.add-btn {
  margin-left: auto;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-primary);
  background: var(--color-surface);
  color: var(--color-primary);
  font-size: var(--text-sm-2);
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: var(--space-1);
  transition: var(--transition-interactive);
}
.add-btn:hover { background: var(--color-surface-raised); }

/* ── 当前模型卡 ────────────────────────────────────────── */
.current-card {
  background: var(--gradient-brand);
  border-radius: var(--radius-md);
  padding: var(--space-4-5) var(--space-5);
  display: flex;
  align-items: center;
  gap: var(--space-4);
  color: var(--color-text-on-brand);
}
.current-icon {
  width: 52px; height: 52px;
  background: var(--color-surface-on-dark-strong);
  border-radius: var(--radius-md);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xl);
  flex-shrink: 0;
}
.current-info { flex: 1; }
.current-name { font-size: var(--text-xl); font-weight: var(--weight-semibold); margin-bottom: var(--space-1-5); }
.current-meta { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
.badge {
  padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-md);
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
}
.badge-cloud  { background: var(--color-surface-on-dark-strong); color: white; }
.badge-local  { background: var(--color-surface-on-dark-strong); color: white; }
.badge-type   { background: var(--color-surface-on-dark); color: var(--color-on-brand-text-soft); }
.current-provider { font-size: var(--text-sm-2); color: var(--color-on-brand-text-soft); }
.current-status {
  display: flex; align-items: center; gap: var(--space-1-5);
  font-size: var(--text-sm); color: var(--color-on-brand-text-soft);
  flex-shrink: 0;
}
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.status-dot.running { background: var(--color-success); box-shadow: 0 0 0 3px var(--color-success-soft); }

/* ── 模型网格 ──────────────────────────────────────────── */
.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-3-5);
}
.model-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2-5);
  transition: box-shadow var(--duration-base), border-color var(--duration-base);
  position: relative;
  overflow: hidden;
}
.model-card:hover { box-shadow: var(--shadow-raised); }
.model-card.active { border-color: var(--color-primary); box-shadow: var(--shadow-focus); }

.card-accent {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 3px;
}
.cloud-accent { background: linear-gradient(90deg, var(--color-primary), var(--color-primary-hover)); }
.local-accent { background: linear-gradient(90deg, var(--color-accent), var(--color-info)); }

.card-header { display: flex; align-items: flex-start; gap: var(--space-2-5); margin-top: var(--space-1); }
.card-icon {
  width: 38px; height: 38px;
  border-radius: var(--radius-sm);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-md);
  flex-shrink: 0;
}
.cloud-icon { background: var(--color-surface-raised); color: var(--color-primary); }
.local-icon { background: var(--color-success-soft); color: var(--color-accent); }

.card-info { flex: 1; min-width: 0; }
.card-name { font-size: var(--text-base); font-weight: var(--weight-semibold); color: var(--color-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card-sub  { font-size: var(--text-sm-2); color: var(--color-text-secondary); margin-top: var(--space-0-5); }

.active-badge {
  background: var(--color-surface-raised);
  color: var(--color-primary);
  font-size: var(--text-2xs);
  font-weight: var(--weight-semibold);
  padding: var(--space-0-5) var(--space-1-5);
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}

.card-model {
  font-size: var(--text-sm-2);
  color: var(--color-text);
  background: var(--color-surface-sunken);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.card-model i { color: var(--color-text-muted); }

.card-tags { display: flex; gap: var(--space-1-5); flex-wrap: wrap; }
.tag {
  padding: var(--space-0-5) var(--space-1-5);
  border-radius: var(--radius-sm);
  font-size: var(--text-2xs);
  font-weight: var(--weight-medium);
  display: flex;
  align-items: center;
  gap: var(--space-1);
}
.tag-llm      { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.tag-key-ok   { background: var(--color-success-soft); color: var(--color-success); }
.tag-key-missing { background: var(--color-warn-soft); color: var(--color-warn); }
.tag-loaded   { background: var(--color-success-soft); color: var(--color-success); }
.tag-unloaded { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.tag-chat     { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.tag-img      { background: var(--color-violet-soft); color: var(--color-violet); }
.tag-voice    { background: var(--color-warn-soft); color: var(--color-warn); }

.card-actions { display: flex; gap: var(--space-1-5); align-items: center; margin-top: var(--space-1); }
.btn-activate {
  flex: 1;
  padding: var(--space-1-5) var(--space-3);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-primary);
  background: var(--color-surface);
  color: var(--color-primary);
  font-size: var(--text-sm-2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-1);
  transition: var(--transition-interactive);
}
.btn-activate:hover:not(:disabled) { background: var(--color-surface-raised); }
.btn-activate.btn-active { background: var(--color-surface-raised); color: var(--color-primary); cursor: default; }
.btn-activate:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-icon {
  width: 30px; height: 30px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: var(--text-xs);
  transition: var(--transition-interactive);
  flex-shrink: 0;
}
.btn-icon:hover { border-color: var(--color-primary); color: var(--color-primary); }
.btn-icon.danger:hover { border-color: var(--color-danger); color: var(--color-danger); }

/* ── 切回本地 ──────────────────────────────────────────── */
.switch-local-row { display: flex; }
.switch-local-btn {
  padding: var(--space-1-5) var(--space-4);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border-strong);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: var(--text-sm);
  cursor: pointer;
  display: flex; align-items: center; gap: var(--space-1-5);
  transition: var(--transition-interactive);
}
.switch-local-btn:hover { border-color: var(--color-accent); color: var(--color-accent); }

/* ── 空状态 ───────────────────────────────────────────── */
.empty-tip {
  text-align: center;
  padding: 30px;
  color: var(--color-text-muted);
  background: var(--color-surface-sunken);
  border-radius: var(--radius-sm);
  border: 1px dashed var(--color-border);
}
.empty-tip i { font-size: 2rem; margin-bottom: var(--space-2); color: var(--color-text-muted); display: block; }
.empty-tip p { margin: var(--space-1-5) 0; font-size: var(--text-sm); }
.hint-code {
  display: inline-block;
  background: var(--color-surface-sunken);
  border-radius: var(--radius-xs);
  padding: var(--space-1) var(--space-2-5);
  font-size: var(--text-sm-2);
  color: var(--color-text);
  margin-top: var(--space-2);
  font-family: monospace;
}
.link-btn {
  background: none; border: none; color: var(--color-primary); cursor: pointer;
  font-size: var(--text-sm); text-decoration: underline; margin-top: var(--space-1);
}

/* ── 对话框 ───────────────────────────────────────────── */
.dialog-backdrop {
  position: fixed; inset: 0;
  background: var(--color-scrim);
  display: flex; align-items: center; justify-content: center;
  z-index: 200;
}
.dialog {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  width: min(480px, 92vw);
  box-shadow: var(--shadow-overlay);
  display: flex; flex-direction: column;
}
.dialog-title {
  padding: var(--space-4) var(--space-4-5) var(--space-3-5);
  font-size: var(--text-md); font-weight: var(--weight-semibold); color: var(--color-text);
  border-bottom: 1px solid var(--color-border);
  display: flex; align-items: center;
}
.dialog-close {
  margin-left: auto; background: none; border: none;
  color: var(--color-text-muted); font-size: var(--text-base); cursor: pointer;
  padding: var(--space-0-5) var(--space-1-5); border-radius: var(--radius-xs);
}
.dialog-close:hover { color: var(--color-text); }
.dialog-body { padding: var(--space-4) var(--space-4-5); display: flex; flex-direction: column; gap: var(--space-2-5); }
.form-label { font-size: var(--text-sm-2); color: var(--color-text); font-weight: var(--weight-medium); }
.form-input {
  padding: var(--space-2) var(--space-2-5);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  width: 100%;
  box-sizing: border-box;
  outline: none;
  transition: border-color var(--duration-base);
}
.form-input:focus { border-color: var(--color-primary); }
.dialog-footer {
  padding: var(--space-3) var(--space-4-5) var(--space-4);
  display: flex; justify-content: flex-end; gap: var(--space-2-5);
  border-top: 1px solid var(--color-border);
}
.btn-cancel {
  padding: var(--space-1-5) var(--space-4); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; font-size: var(--text-sm);
}
.btn-cancel:hover { border-color: var(--color-text-muted); }
.btn-save {
  padding: var(--space-1-5) var(--space-4); border-radius: var(--radius-sm);
  border: none; background: var(--color-primary);
  color: var(--color-text-on-primary); cursor: pointer; font-size: var(--text-sm);
  display: flex; align-items: center; gap: var(--space-1-5);
  transition: background var(--duration-base);
}
.btn-save:hover:not(:disabled) { background: var(--color-primary); }
.btn-save:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
