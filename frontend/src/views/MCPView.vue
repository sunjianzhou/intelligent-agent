<template>
  <div class="mcp-view">
    <div class="toolbar">
      <span class="page-desc">系统资源配置（并发 / 缓存 / 记忆上限）</span>
    </div>

    <!-- 系统资源配置 -->
    <div class="config-card">
      <div class="config-title">
        <i class="fas fa-server" /> 系统资源配置
        <button class="rc-save-btn" :disabled="rcSaving" @click="saveRuntimeConfig" style="margin-left:auto">
          <i v-if="rcSaving" class="fas fa-circle-notch fa-spin" /><i v-else class="fas fa-save" />
          {{ rcSaving ? '保存中…' : '保存配置' }}
        </button>
      </div>
      <div class="config-hint">调整并发/缓存/记忆上限，修改后立即生效（重启前有效）</div>
      <div class="rc-form-grid">

        <div class="rc-group">
          <div class="rc-group-title">推理并发控制</div>
          <div class="rc-field">
            <label>最大并发数 <span class="rc-range">[1–20]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="1" max="20" v-model.number="rcEdit.inference_concurrency" />
              <input type="number" min="1" max="20" v-model.number="rcEdit.inference_concurrency" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>等待队列上限 <span class="rc-range">[5–200]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="5" max="200" step="5" v-model.number="rcEdit.inference_queue_size" />
              <input type="number" min="5" max="200" v-model.number="rcEdit.inference_queue_size" class="rc-num" />
            </div>
          </div>
        </div>

        <div class="rc-group">
          <div class="rc-group-title">响应缓存 (L1 精确)</div>
          <div class="rc-field">
            <label>最大条目数 <span class="rc-range">[10–10000]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="10" max="2000" step="10" v-model.number="rcEdit.response_cache_max_size" />
              <input type="number" min="10" max="10000" v-model.number="rcEdit.response_cache_max_size" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>有效期 (秒) <span class="rc-range">[60–86400]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="60" max="86400" step="60" v-model.number="rcEdit.response_cache_ttl_secs" />
              <input type="number" min="60" max="86400" v-model.number="rcEdit.response_cache_ttl_secs" class="rc-num" />
            </div>
          </div>
        </div>

        <div class="rc-group">
          <div class="rc-group-title">语义缓存 (L2 向量)</div>
          <div class="rc-field">
            <label>相似度阈值 <span class="rc-range">[0.5–1.0]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="0.5" max="1.0" step="0.01" v-model.number="rcEdit.semantic_cache_threshold" />
              <input type="number" min="0.5" max="1.0" step="0.01" v-model.number="rcEdit.semantic_cache_threshold" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>最大条目数 <span class="rc-range">[100–20000]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="100" max="5000" step="100" v-model.number="rcEdit.semantic_cache_max_entries" />
              <input type="number" min="100" max="20000" v-model.number="rcEdit.semantic_cache_max_entries" class="rc-num" />
            </div>
          </div>
        </div>

        <div class="rc-group">
          <div class="rc-group-title">记忆系统</div>
          <div class="rc-field">
            <label>短期记忆上限 <span class="rc-range">[10–2000]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="10" max="500" step="10" v-model.number="rcEdit.short_term_max_size" />
              <input type="number" min="10" max="2000" v-model.number="rcEdit.short_term_max_size" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>短期记忆保留 (小时) <span class="rc-range">[1–720]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="1" max="168" v-model.number="rcEdit.short_term_ttl_hours" />
              <input type="number" min="1" max="720" v-model.number="rcEdit.short_term_ttl_hours" class="rc-num" />
            </div>
          </div>
        </div>

        <div class="rc-group">
          <div class="rc-group-title">推理参数</div>
          <div class="rc-field">
            <label>上下文窗口 (num_ctx) <span class="rc-range">[512–131072]</span></label>
            <div class="rc-hint-text">GTX1660 6GB 建议 ≤ 16384；纯CPU可到 32768</div>
            <div class="rc-slider-row">
              <input type="range" min="512" max="32768" step="512" v-model.number="rcEdit.ollama_num_ctx" />
              <input type="number" min="512" max="131072" step="512" v-model.number="rcEdit.ollama_num_ctx" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>最大输出 Tokens <span class="rc-range">[128–32768]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="128" max="8192" step="128" v-model.number="rcEdit.ollama_max_tokens" />
              <input type="number" min="128" max="32768" v-model.number="rcEdit.ollama_max_tokens" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>Temperature <span class="rc-range">[0–2]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="0" max="2" step="0.05" v-model.number="rcEdit.ollama_temperature" />
              <input type="number" min="0" max="2" step="0.05" v-model.number="rcEdit.ollama_temperature" class="rc-num" />
            </div>
          </div>
        </div>

        <div class="rc-group">
          <div class="rc-group-title">超时 / 输出</div>
          <div class="rc-field">
            <label>请求超时 (秒) <span class="rc-range">[10–600]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="10" max="600" step="10" v-model.number="rcEdit.chat_timeout" />
              <input type="number" min="10" max="600" v-model.number="rcEdit.chat_timeout" class="rc-num" />
            </div>
          </div>
          <div class="rc-field">
            <label>工具结果最大字符 <span class="rc-range">[200–50000]</span></label>
            <div class="rc-slider-row">
              <input type="range" min="200" max="10000" step="100" v-model.number="rcEdit.tool_result_max_chars" />
              <input type="number" min="200" max="50000" v-model.number="rcEdit.tool_result_max_chars" class="rc-num" />
            </div>
          </div>
        </div>

      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getRuntimeConfig, updateRuntimeConfig } from '@/services/api'

// ── 系统资源配置 ──────────────────────────────────────────
const rcEdit   = ref({})
const rcSaving = ref(false)

const loadRcConfig = async () => {
  const data = await getRuntimeConfig()
  if (data?.config) {
    if (Object.keys(rcEdit.value).length === 0) {
      rcEdit.value = { ...data.config }
    }
  }
}

const saveRuntimeConfig = async () => {
  rcSaving.value = true
  try {
    const res = await updateRuntimeConfig(rcEdit.value)
    if (res?.success) {
      ElMessage({ message: `已更新 ${Object.keys(res.updated || {}).length} 项配置`, type: 'success', duration: 2000 })
    } else {
      const errKeys = Object.keys(res?.errors || {})
      ElMessage({ message: errKeys.length ? `${errKeys[0]}: ${res.errors[errKeys[0]]}` : '保存失败', type: 'error', duration: 3000 })
    }
  } finally {
    rcSaving.value = false
  }
}

onMounted(() => {
  loadRcConfig()
})
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

/* 系统资源配置表单 */
.rc-form-grid { display: flex; flex-direction: column; gap: 16px; }
.rc-group { background: #f8f9fa; border-radius: 10px; padding: 14px 16px; }
.rc-group-title {
  font-size: 0.82rem; font-weight: 600; color: #555;
  margin-bottom: 10px; padding-bottom: 6px; border-bottom: 1px solid #e8eaed;
}
.rc-field { display: flex; flex-direction: column; gap: 4px; margin-top: 10px; }
.rc-field label { font-size: 0.82rem; color: #555; display: flex; align-items: center; gap: 6px; }
.rc-range { font-size: 0.72rem; color: #aaa; }
.rc-hint-text { font-size: 0.75rem; color: #aaa; margin-bottom: 2px; }
.rc-slider-row { display: flex; align-items: center; gap: 10px; }
.rc-slider-row input[type="range"] { flex: 1; accent-color: #667eea; cursor: pointer; }
.rc-num {
  width: 70px; border: 1px solid #e0e3e8; border-radius: 6px;
  padding: 4px 6px; font-size: 0.82rem; text-align: center;
}
.rc-save-btn {
  padding: 6px 16px; background: #667eea; color: white;
  border: none; border-radius: 8px; font-size: 0.85rem; cursor: pointer;
  display: flex; align-items: center; gap: 6px; transition: opacity 0.2s;
}
.rc-save-btn:hover:not(:disabled) { opacity: 0.85; }
.rc-save-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* 数据库配置 */
.db-status {
  margin-left: auto;
  font-size: 0.75rem;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 10px;
}
.db-status--on  { background: #d1fae5; color: #065f46; }
.db-status--off { background: #fee2e2; color: #991b1b; }
.db-form { display: flex; flex-direction: column; gap: 10px; }
.db-row  { display: flex; gap: 10px; }
.db-field { display: flex; flex-direction: column; gap: 4px; flex: 1; }
.db-field label { font-size: 0.82rem; color: #555; }
.db-field--wide { flex: 2; }
.db-input {
  border: 1px solid #e0e3e8;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 0.88rem;
  outline: none;
  transition: border-color 0.2s;
}
.db-input:focus { border-color: #667eea; }
.db-input--port { width: 90px; }
.db-select {
  border: 1px solid #e0e3e8;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 0.88rem;
  background: white;
  outline: none;
  cursor: pointer;
}
.db-select:focus { border-color: #667eea; }

[data-theme="dark"] .mcp-view { background: #1e1f24; }
[data-theme="dark"] .config-card { background: #2c2d32; border-color: #3a3b42; }
[data-theme="dark"] .config-title { color: #e0e1e4; }
[data-theme="dark"] .cfg-label { color: #c1c2c5; }
[data-theme="dark"] .cfg-input { background: #383940; border-color: #4a4b52; color: #c1c2c5; }
[data-theme="dark"] .param-item label { color: #a0a1ab; }
[data-theme="dark"] .rc-group { background: #383940; }
[data-theme="dark"] .rc-group-title { color: #b0b1bb; border-color: #4a4b52; }
[data-theme="dark"] .rc-field label { color: #a0a1ab; }
[data-theme="dark"] .rc-num { background: #2c2d32; border-color: #4a4b52; color: #c1c2c5; }
[data-theme="dark"] .db-field label { color: #a0a1ab; }
[data-theme="dark"] .db-input { background: #383940; border-color: #4a4b52; color: #c1c2c5; }
[data-theme="dark"] .db-select { background: #383940; border-color: #4a4b52; color: #c1c2c5; }
[data-theme="dark"] .db-status--on  { background: #064e3b; color: #6ee7b7; }
[data-theme="dark"] .db-status--off { background: #7f1d1d; color: #fca5a5; }
</style>
