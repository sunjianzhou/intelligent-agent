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
  padding: 20px;
  max-width: 1100px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ── 分区 ─────────────────────────────────────────────── */
.section { display: flex; flex-direction: column; gap: 14px; }
.section-title {
  font-size: 1rem;
  font-weight: 600;
  color: #333;
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-title i { color: #667eea; font-size: 0.95rem; }
.section-count {
  margin-left: auto;
  font-size: 0.78rem;
  color: #aaa;
  font-weight: 400;
}
.add-btn {
  margin-left: auto;
  padding: 5px 12px;
  border-radius: 7px;
  border: 1px solid #667eea;
  background: white;
  color: #667eea;
  font-size: 0.82rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 5px;
  transition: all 0.2s;
}
.add-btn:hover { background: #f0f2ff; }

/* ── 当前模型卡 ────────────────────────────────────────── */
.current-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 14px;
  padding: 20px 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  color: white;
}
.current-icon {
  width: 52px; height: 52px;
  background: rgba(255,255,255,0.2);
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 1.4rem;
  flex-shrink: 0;
}
.current-info { flex: 1; }
.current-name { font-size: 1.3rem; font-weight: 600; margin-bottom: 6px; }
.current-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.badge {
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 0.73rem;
  font-weight: 500;
}
.badge-cloud  { background: rgba(255,255,255,0.2); color: white; }
.badge-local  { background: rgba(255,255,255,0.2); color: white; }
.badge-type   { background: rgba(255,255,255,0.15); color: rgba(255,255,255,0.9); }
.current-provider { font-size: 0.8rem; color: rgba(255,255,255,0.75); }
.current-status {
  display: flex; align-items: center; gap: 6px;
  font-size: 0.85rem; color: rgba(255,255,255,0.85);
  flex-shrink: 0;
}
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.status-dot.running { background: #4ade80; box-shadow: 0 0 0 3px rgba(74,222,128,0.3); }

/* ── 模型网格 ──────────────────────────────────────────── */
.model-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
}
.model-card {
  background: white;
  border: 1px solid #e8eaf0;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: box-shadow 0.2s, border-color 0.2s;
  position: relative;
  overflow: hidden;
}
.model-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.08); }
.model-card.active { border-color: #667eea; box-shadow: 0 0 0 2px rgba(102,126,234,0.2); }

.card-accent {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 3px;
}
.cloud-accent { background: linear-gradient(90deg, #667eea, #764ba2); }
.local-accent { background: linear-gradient(90deg, #4fc3a1, #38bdf8); }

.card-header { display: flex; align-items: flex-start; gap: 10px; margin-top: 4px; }
.card-icon {
  width: 38px; height: 38px;
  border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  font-size: 1rem;
  flex-shrink: 0;
}
.cloud-icon { background: #f0f2ff; color: #667eea; }
.local-icon { background: #f0fdf4; color: #4fc3a1; }

.card-info { flex: 1; min-width: 0; }
.card-name { font-size: 0.92rem; font-weight: 600; color: #222; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card-sub  { font-size: 0.75rem; color: #888; margin-top: 2px; }

.active-badge {
  background: #f0f2ff;
  color: #4f46e5;
  font-size: 0.7rem;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 10px;
  flex-shrink: 0;
}

.card-model {
  font-size: 0.8rem;
  color: #555;
  background: #f8f9fa;
  padding: 4px 8px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 5px;
}
.card-model i { color: #aaa; }

.card-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.tag {
  padding: 2px 7px;
  border-radius: 10px;
  font-size: 0.7rem;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}
.tag-llm      { background: #f0f2ff; color: #4f46e5; }
.tag-key-ok   { background: #f0fdf4; color: #16a34a; }
.tag-key-missing { background: #fff7ed; color: #ea580c; }
.tag-loaded   { background: #ecfdf5; color: #059669; }
.tag-unloaded { background: #f9fafb; color: #9ca3af; }

.card-actions { display: flex; gap: 6px; align-items: center; margin-top: 4px; }
.btn-activate {
  flex: 1;
  padding: 6px 12px;
  border-radius: 7px;
  border: 1px solid #667eea;
  background: white;
  color: #667eea;
  font-size: 0.8rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  transition: all 0.2s;
}
.btn-activate:hover:not(:disabled) { background: #f0f2ff; }
.btn-activate.btn-active { background: #f0f2ff; color: #4f46e5; cursor: default; }
.btn-activate:disabled { opacity: 0.6; cursor: not-allowed; }
.btn-icon {
  width: 30px; height: 30px;
  border-radius: 6px;
  border: 1px solid #e8eaf0;
  background: white;
  color: #888;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  font-size: 0.78rem;
  transition: all 0.18s;
  flex-shrink: 0;
}
.btn-icon:hover { border-color: #667eea; color: #667eea; }
.btn-icon.danger:hover { border-color: #f87171; color: #ef4444; }

/* ── 切回本地 ──────────────────────────────────────────── */
.switch-local-row { display: flex; }
.switch-local-btn {
  padding: 7px 16px;
  border-radius: 8px;
  border: 1px solid #d1d5db;
  background: white;
  color: #555;
  font-size: 0.85rem;
  cursor: pointer;
  display: flex; align-items: center; gap: 7px;
  transition: all 0.2s;
}
.switch-local-btn:hover { border-color: #4fc3a1; color: #4fc3a1; }

/* ── 空状态 ───────────────────────────────────────────── */
.empty-tip {
  text-align: center;
  padding: 30px;
  color: #aaa;
  background: #fafbfc;
  border-radius: 10px;
  border: 1px dashed #e0e3e8;
}
.empty-tip i { font-size: 2rem; margin-bottom: 8px; color: #ccc; display: block; }
.empty-tip p { margin: 6px 0; font-size: 0.88rem; }
.hint-code {
  display: inline-block;
  background: #f1f3f5;
  border-radius: 5px;
  padding: 4px 10px;
  font-size: 0.82rem;
  color: #555;
  margin-top: 8px;
  font-family: monospace;
}
.link-btn {
  background: none; border: none; color: #667eea; cursor: pointer;
  font-size: 0.85rem; text-decoration: underline; margin-top: 4px;
}

/* ── 对话框 ───────────────────────────────────────────── */
.dialog-backdrop {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center;
  z-index: 200;
}
.dialog {
  background: white;
  border-radius: 14px;
  width: min(480px, 92vw);
  box-shadow: 0 8px 40px rgba(0,0,0,0.15);
  display: flex; flex-direction: column;
}
.dialog-title {
  padding: 18px 20px 14px;
  font-size: 1rem; font-weight: 600; color: #222;
  border-bottom: 1px solid #f0f0f0;
  display: flex; align-items: center;
}
.dialog-close {
  margin-left: auto; background: none; border: none;
  color: #aaa; font-size: 0.95rem; cursor: pointer;
  padding: 2px 6px; border-radius: 4px;
}
.dialog-close:hover { color: #555; }
.dialog-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 10px; }
.form-label { font-size: 0.82rem; color: #555; font-weight: 500; }
.form-input {
  padding: 8px 10px;
  border: 1px solid #e0e3e8;
  border-radius: 7px;
  font-size: 0.88rem;
  width: 100%;
  box-sizing: border-box;
  outline: none;
  transition: border-color 0.2s;
}
.form-input:focus { border-color: #667eea; }
.dialog-footer {
  padding: 12px 20px 18px;
  display: flex; justify-content: flex-end; gap: 10px;
  border-top: 1px solid #f0f0f0;
}
.btn-cancel {
  padding: 7px 16px; border-radius: 7px;
  border: 1px solid #e0e3e8; background: white;
  color: #666; cursor: pointer; font-size: 0.85rem;
}
.btn-cancel:hover { border-color: #aaa; }
.btn-save {
  padding: 7px 18px; border-radius: 7px;
  border: none; background: #667eea;
  color: white; cursor: pointer; font-size: 0.85rem;
  display: flex; align-items: center; gap: 6px;
  transition: background 0.2s;
}
.btn-save:hover:not(:disabled) { background: #5569d0; }
.btn-save:disabled { opacity: 0.6; cursor: not-allowed; }

/* 暗色主题 */
[data-theme="dark"] .model-card { background: #2c2d32; border-color: #3a3b42; color: #c1c2c5; }
[data-theme="dark"] .card-name { color: #e0e1e4; }
[data-theme="dark"] .card-sub  { color: #6b6c75; }
[data-theme="dark"] .card-model { background: #383940; color: #a0a1ab; }
[data-theme="dark"] .dialog { background: #2c2d32; }
[data-theme="dark"] .dialog-title { color: #e0e1e4; border-color: #3a3b42; }
[data-theme="dark"] .form-input { background: #383940; border-color: #4a4b52; color: #c1c2c5; }
[data-theme="dark"] .form-label { color: #9ca3af; }
[data-theme="dark"] .dialog-footer { border-color: #3a3b42; }
[data-theme="dark"] .btn-cancel { background: #383940; border-color: #4a4b52; color: #aaa; }
[data-theme="dark"] .empty-tip { background: #383940; border-color: #4a4b52; }
[data-theme="dark"] .section-title { color: #c1c2c5; }
</style>
