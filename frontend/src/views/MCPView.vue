<template>
  <div class="mcp-view">
    <div class="toolbar">
      <span class="page-desc">工具扩展配置（API Key / MCP 接入）</span>
    </div>

    <!-- API Key 配置 -->
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

    <!-- 推理参数调节 -->
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
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

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
    ElMessage({ message: '参数已更新', type: 'success', duration: 1500 })
  } catch {
    ElMessage({ message: '参数保存失败', type: 'error', duration: 2000 })
  }
}
</script>

<style scoped>
.mcp-view {
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: #f8f9fa;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-desc { font-size: 0.88rem; color: #888; }

.config-card {
  background: white;
  border: 1px solid #e8eaf0;
  border-radius: 12px;
  padding: 18px 20px;
}
.config-title {
  font-size: 0.95rem;
  font-weight: 600;
  color: #333;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.config-title i { color: #667eea; }
.config-hint { font-size: 0.78rem; color: #aaa; margin-bottom: 14px; }
.config-grid { display: flex; flex-direction: column; gap: 14px; }
.config-item { display: flex; flex-direction: column; gap: 5px; }
.cfg-label   { font-size: 0.85rem; font-weight: 500; color: #444; }
.cfg-input-row {
  display: flex; align-items: center; gap: 6px;
}
.cfg-input {
  flex: 1;
  border: 1px solid #e0e3e8;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 0.88rem;
  outline: none;
  transition: border-color 0.2s;
}
.cfg-input:focus { border-color: #667eea; }
.cfg-eye {
  background: none; border: none; color: #aaa; cursor: pointer;
  font-size: 0.9rem; padding: 6px;
}
.cfg-eye:hover { color: #667eea; }
.cfg-desc { font-size: 0.76rem; color: #aaa; }
.config-footer {
  display: flex; align-items: center; gap: 14px; margin-top: 16px;
  border-top: 1px solid #f0f0f0; padding-top: 12px;
}
.cfg-save-btn {
  display: flex; align-items: center; gap: 6px;
  background: #667eea; color: white;
  border: none; border-radius: 8px;
  padding: 8px 18px; font-size: 0.88rem; cursor: pointer;
  transition: background 0.2s;
}
.cfg-save-btn:hover:not(:disabled) { background: #5569d0; }
.cfg-save-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.cfg-tip { font-size: 0.76rem; color: #bbb; }

.param-grid { display: flex; flex-direction: column; gap: 14px; }
.param-item { display: flex; flex-direction: column; gap: 6px; }
.param-item label {
  display: flex; justify-content: space-between;
  font-size: 0.85rem; color: #555;
}
.param-val { font-weight: 600; color: #667eea; }
.param-slider {
  width: 100%; accent-color: #667eea; cursor: pointer;
}
.param-range {
  display: flex; justify-content: space-between;
  font-size: 0.72rem; color: #bbb;
}

[data-theme="dark"] .mcp-view { background: #1e1f24; }
[data-theme="dark"] .config-card { background: #2c2d32; border-color: #3a3b42; }
[data-theme="dark"] .config-title { color: #e0e1e4; }
[data-theme="dark"] .cfg-label { color: #c1c2c5; }
[data-theme="dark"] .cfg-input { background: #383940; border-color: #4a4b52; color: #c1c2c5; }
[data-theme="dark"] .param-item label { color: #a0a1ab; }
</style>
