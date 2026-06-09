<template>
  <div class="tools-view">
    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="page-desc">当前已注册 {{ tools.length }} 个工具</span>
      <button class="refresh-btn" :class="{ spinning: loading }" @click="loadTools">
        <i class="fas fa-sync-alt" />
        刷新
      </button>
    </div>

    <!-- 分类过滤 -->
    <div class="category-tabs">
      <button
        v-for="cat in categories"
        :key="cat"
        class="cat-btn"
        :class="{ active: selectedCategory === cat }"
        @click="selectedCategory = cat"
      >
        {{ cat === 'all' ? '全部' : cat }}
        <span class="cat-count">
          {{ cat === 'all' ? tools.length : tools.filter(t => t.category === cat).length }}
        </span>
      </button>
    </div>

    <!-- 工具列表 -->
    <div v-if="filteredTools.length > 0" class="tools-grid">
      <div
        v-for="tool in filteredTools"
        :key="tool.name"
        class="tool-card"
      >
        <div class="tool-icon" :style="{ background: categoryColor(tool.category) }">
          <i :class="categoryIcon(tool.category)" />
        </div>
        <div class="tool-info">
          <div class="tool-header">
            <h3 class="tool-name">{{ tool.name }}</h3>
            <span class="tool-status" :class="tool.enabled ? 'enabled' : 'disabled'">
              {{ tool.enabled ? '可用' : '禁用' }}
            </span>
          </div>
          <p class="tool-description">{{ tool.description }}</p>
          <span class="tool-category-badge">{{ tool.category }}</span>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="!loading" class="empty-state">
      <i class="fas fa-tools empty-icon" />
      <p class="empty-title">暂无可用工具</p>
      <p class="empty-sub">请检查后端服务是否正常运行</p>
    </div>

    <!-- 加载中 -->
    <div v-if="loading" class="loading-state">
      <i class="fas fa-circle-notch fa-spin" />
      <span>加载中...</span>
    </div>

    <!-- API Key 配置面板 -->
    <div class="config-card">
      <div class="config-title"><i class="fas fa-key" /> 工具 API Key 配置</div>
      <div class="config-hint">配置后写入 .env 文件，重启服务后生效</div>
      <div class="config-grid">
        <div class="config-item" v-for="cfg in apiKeyConfigs" :key="cfg.key">
          <label class="cfg-label">{{ cfg.label }}</label>
          <div class="cfg-input-row">
            <input
              :type="cfg.show ? 'text' : 'password'"
              v-model="cfg.value"
              :placeholder="cfg.placeholder"
              class="cfg-input"
            />
            <button class="cfg-eye" @click="cfg.show = !cfg.show">
              <i :class="cfg.show ? 'fas fa-eye-slash' : 'fas fa-eye'" />
            </button>
          </div>
          <span class="cfg-desc">{{ cfg.desc }}</span>
        </div>
      </div>
      <div class="config-footer">
        <button class="cfg-save-btn" :disabled="saving" @click="saveApiKeys">
          <i v-if="saving" class="fas fa-circle-notch fa-spin" />
          <i v-else class="fas fa-save" />
          {{ saving ? '保存中...' : '保存配置' }}
        </button>
        <span class="cfg-tip">保存后需重启 Python Agent 生效</span>
      </div>
    </div>

    <!-- 模型推理参数调节 -->
    <div class="config-card">
      <div class="config-title"><i class="fas fa-sliders-h" /> 推理参数调节</div>
      <div class="config-hint">调节 LLM 生成行为，即时生效（不需要重启）</div>
      <div class="param-grid">
        <div class="param-item">
          <label>Temperature（温度）<span class="param-val">{{ temperature }}</span></label>
          <input type="range" v-model.number="temperature" min="0" max="2" step="0.05"
                 class="param-slider" @change="saveParams" />
          <div class="param-range"><span>0 精确</span><span>2 创意</span></div>
        </div>
        <div class="param-item">
          <label>Max Tokens（最大长度）<span class="param-val">{{ maxTokens }}</span></label>
          <input type="range" v-model.number="maxTokens" min="256" max="8192" step="256"
                 class="param-slider" @change="saveParams" />
          <div class="param-range"><span>256</span><span>8192</span></div>
        </div>
        <div class="param-item">
          <label>Top-P（核采样）<span class="param-val">{{ topP }}</span></label>
          <input type="range" v-model.number="topP" min="0.1" max="1" step="0.05"
                 class="param-slider" @change="saveParams" />
          <div class="param-range"><span>0.1</span><span>1.0</span></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getTools } from '@/services/api'

const tools            = ref([])
const loading          = ref(false)
const selectedCategory = ref('all')

// ── 分类配置 ──────────────────────────────────────────────
const CATEGORY_CONFIG = {
  math:      { icon: 'fas fa-calculator',   color: '#4CAF50' },
  utility:   { icon: 'fas fa-clock',        color: '#2196F3' },
  file:      { icon: 'fas fa-folder-open',  color: '#FF9800' },
  web:       { icon: 'fas fa-globe',        color: '#9C27B0' },
  system:    { icon: 'fas fa-server',       color: '#00BCD4' },
  memory:    { icon: 'fas fa-brain',        color: '#E91E63' },
  scheduler: { icon: 'fas fa-calendar-alt', color: '#FF5722' },
  general:   { icon: 'fas fa-wrench',       color: '#607D8B' },
}

const categoryIcon  = (cat) => CATEGORY_CONFIG[cat]?.icon  || 'fas fa-wrench'
const categoryColor = (cat) => CATEGORY_CONFIG[cat]?.color || '#607D8B'

// ── 计算属性 ──────────────────────────────────────────────
const categories = computed(() => {
  const cats = [...new Set(tools.value.map(t => t.category))]
  return ['all', ...cats]
})

const filteredTools = computed(() =>
  selectedCategory.value === 'all'
    ? tools.value
    : tools.value.filter(t => t.category === selectedCategory.value)
)

// ── 数据加载 ──────────────────────────────────────────────
const loadTools = async () => {
  loading.value = true
  try {
    const data = await getTools()
    if (data?.tools) {
      tools.value = data.tools
    }
  } finally {
    loading.value = false
  }
}

// ── API Key 配置 ──────────────────────────────────────────
const saving = ref(false)
const apiKeyConfigs = ref([
  {
    key: 'GITHUB_TOKEN',
    label: 'GitHub Token',
    placeholder: 'ghp_xxxxxxx 或 github_pat_xxxxxx',
    desc: '用于 GitHub 工具：搜索仓库、查看代码、PR/Issue 等',
    value: '',
    show: false,
  },
  {
    key: 'WEB_SEARCH_API_KEY',
    label: 'WebSearch API Key',
    placeholder: '搜索引擎 API Key',
    desc: '用于 WebSearchTool：联网搜索最新信息',
    value: '',
    show: false,
  },
])

const saveApiKeys = async () => {
  saving.value = true
  try {
    const updates = {}
    apiKeyConfigs.value.forEach(c => { if (c.value) updates[c.key] = c.value })
    if (!Object.keys(updates).length) {
      ElMessage({ message: '未填写任何 Key', type: 'warning', duration: 2000 })
      return
    }
    const res = await fetch('/api/config/env', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('agent_token') || ''}`,
      },
      body: JSON.stringify(updates),
    })
    if (res.ok) {
      ElMessage({ message: '配置已保存，重启服务后生效', type: 'success', duration: 3000 })
      apiKeyConfigs.value.forEach(c => { c.value = '' })
    } else {
      ElMessage({ message: '保存失败，请检查服务状态', type: 'error', duration: 3000 })
    }
  } finally {
    saving.value = false
  }
}

// ── 推理参数 ──────────────────────────────────────────────
const temperature = ref(0.7)
const maxTokens   = ref(2048)
const topP        = ref(0.9)

const saveParams = async () => {
  try {
    await fetch('/api/config/params', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('agent_token') || ''}`,
      },
      body: JSON.stringify({
        temperature: temperature.value,
        max_tokens:  maxTokens.value,
        top_p:       topP.value,
      }),
    })
    ElMessage({ message: `参数已更新`, type: 'success', duration: 1500 })
  } catch {
    ElMessage({ message: '参数保存失败', type: 'error', duration: 2000 })
  }
}

onMounted(loadTools)
</script>

<style scoped>
.tools-view {
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #f8f9fa;
}

/* ── 工具栏 ─────────────────────────────────────────────── */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-desc { font-size: 0.88rem; color: #888; }
.refresh-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 7px 14px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  font-size: 0.88rem; color: #555; cursor: pointer;
  transition: all 0.2s;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 分类标签 ────────────────────────────────────────────── */
.category-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.cat-btn {
  display: flex; align-items: center; gap: 5px;
  padding: 6px 12px; border-radius: 20px;
  border: 1px solid #e0e3e8; background: white;
  font-size: 0.85rem; color: #666; cursor: pointer;
  transition: all 0.2s;
}
.cat-btn:hover  { border-color: #667eea; color: #667eea; }
.cat-btn.active { background: #667eea; border-color: #667eea; color: white; }
.cat-count {
  background: rgba(0,0,0,0.1);
  padding: 1px 6px; border-radius: 10px;
  font-size: 0.78rem;
}
.cat-btn.active .cat-count { background: rgba(255,255,255,0.25); }

/* ── 工具网格 ────────────────────────────────────────────── */
.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

/* ── 工具卡片 ────────────────────────────────────────────── */
.tool-card {
  background: white;
  border: 1px solid #e8eaed;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  align-items: flex-start;
  gap: 14px;
  transition: all 0.2s;
}
.tool-card:hover {
  border-color: #c5caf5;
  box-shadow: 0 4px 12px rgba(102,126,234,0.1);
  transform: translateY(-1px);
}

.tool-icon {
  width: 44px; height: 44px;
  border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  font-size: 1.2rem; color: white;
  flex-shrink: 0;
}

.tool-info  { flex: 1; min-width: 0; }

.tool-header {
  display: flex; align-items: center;
  justify-content: space-between;
  gap: 8px; margin-bottom: 6px;
}
.tool-name {
  font-size: 0.95rem; font-weight: 500;
  color: #333; margin: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.tool-status {
  font-size: 0.75rem; padding: 2px 8px;
  border-radius: 10px; font-weight: 500; flex-shrink: 0;
}
.tool-status.enabled  { background: #e8f5e9; color: #2e7d32; }
.tool-status.disabled { background: #fce4e4; color: #c62828; }

.tool-description {
  font-size: 0.83rem; color: #666;
  line-height: 1.5; margin: 0 0 8px;
}

.tool-category-badge {
  font-size: 0.75rem; padding: 2px 8px;
  background: #f0f2ff; color: #667eea;
  border-radius: 4px;
}

/* ── 空状态 ───────────────────────────────────────────────── */
.empty-state {
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: 8px; padding: 60px 20px;
  background: white; border-radius: 12px;
  border: 1px solid #e8eaed;
}
.empty-icon  { font-size: 2.5rem; color: #ddd; }
.empty-title { font-size: 1rem; font-weight: 500; color: #666; margin: 0; }
.empty-sub   { font-size: 0.85rem; color: #aaa; margin: 0; }

/* ── 加载中 ───────────────────────────────────────────────── */
.loading-state {
  display: flex; align-items: center;
  justify-content: center; gap: 10px;
  padding: 40px; color: #888; font-size: 0.9rem;
}

/* ── 配置面板 ─────────────────────────────────────────────── */
.config-card {
  background: white; border-radius: 12px;
  border: 0.5px solid #e8eaed; padding: 18px;
}
.config-title {
  font-size: 0.95rem; font-weight: 500; color: #333;
  display: flex; align-items: center; gap: 8px; margin-bottom: 4px;
}
.config-title i { color: #667eea; }
.config-hint { font-size: 0.8rem; color: #aaa; margin-bottom: 14px; }
.config-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.config-item { display: flex; flex-direction: column; gap: 5px; }
.cfg-label { font-size: 0.85rem; color: #555; font-weight: 500; }
.cfg-input-row { display: flex; gap: 6px; align-items: center; }
.cfg-input {
  flex: 1; padding: 8px 12px; border: 1px solid #e0e3e8;
  border-radius: 8px; font-size: 0.85rem; outline: none;
  transition: border-color 0.2s;
}
.cfg-input:focus { border-color: #667eea; }
.cfg-eye {
  padding: 8px 10px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white;
  color: #888; cursor: pointer;
}
.cfg-desc { font-size: 0.75rem; color: #aaa; }
.config-footer { display: flex; align-items: center; gap: 12px; margin-top: 14px; }
.cfg-save-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 18px; border-radius: 8px;
  border: none; background: #667eea; color: white;
  font-size: 0.88rem; cursor: pointer; transition: background 0.2s;
}
.cfg-save-btn:hover:not(:disabled) { background: #5a6fd6; }
.cfg-save-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.cfg-tip { font-size: 0.78rem; color: #aaa; }

/* ── 参数调节 ─────────────────────────────────────────────── */
.param-grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 18px; }
.param-item { display: flex; flex-direction: column; gap: 6px; }
.param-item label { font-size: 0.85rem; color: #555; font-weight: 500; display: flex; align-items: center; gap: 8px; }
.param-val { background: #f0f2ff; color: #667eea; padding: 1px 8px; border-radius: 8px; font-size: 0.78rem; }
.param-slider { width: 100%; accent-color: #667eea; }
.param-range { display: flex; justify-content: space-between; font-size: 0.72rem; color: #bbb; }

@media (max-width: 768px) {
  .config-grid { grid-template-columns: 1fr; }
  .param-grid  { grid-template-columns: 1fr; }
}
</style>