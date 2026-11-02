<template>
  <div class="mcp-view">
    <div class="toolbar">
      <span class="page-desc">MCP 服务器 · 系统资源配置</span>
    </div>

    <!-- MCP 服务器（G2） -->
    <div class="config-card">
      <div class="config-title">
        <i class="fas fa-plug" /> MCP 服务器
        <button class="rc-save-btn" style="margin-left:auto" @click="openServerModal()">
          <i class="fas fa-plus" /> 新增服务器
        </button>
      </div>
      <div class="config-hint">HTTP 远程 / stdio 本地进程两种传输；连接成功后其工具自动进入 LLM 工具集</div>

      <div v-if="servers.length === 0" class="server-empty">
        尚未配置 MCP 服务器
      </div>
      <div v-else class="server-list">
        <div v-for="server in servers" :key="server.id" class="server-row">
          <div class="server-main">
            <span class="server-name">{{ server.name }}</span>
            <span class="server-url">{{ server.transport === 'stdio' ? server.command : server.base_url }}</span>
            <span class="server-transport">{{ server.transport || 'http' }}</span>
            <span class="server-tools" v-if="server.tool_count > 0">{{ server.tool_count }} 工具</span>
          </div>
          <span class="server-status" :class="server.connected ? 'st-on' : 'st-off'">
            {{ server.connected ? '已连接' : '未连接' }}
          </span>
          <div class="server-actions">
            <button v-if="!server.connected" class="act-btn" @click="connect(server.id)">
              <i class="fas fa-plug" /> 连接
            </button>
            <button v-else class="act-btn" @click="disconnect(server.id)">
              <i class="fas fa-unlink" /> 断开
            </button>
            <button class="act-btn" @click="openServerModal(server)">
              <i class="fas fa-edit" /> 编辑
            </button>
            <button class="act-btn danger" @click="remove(server.id)">
              <i class="fas fa-trash" /> 删除
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 服务器编辑弹层 -->
    <div v-if="serverModal" class="server-modal-mask" @click.self="serverModal = false">
      <div class="server-modal">
        <div class="server-modal-title">
          {{ serverEdit.id ? '编辑 MCP 服务器' : '新增 MCP 服务器' }}
          <button class="modal-close" @click="serverModal = false">&times;</button>
        </div>
        <div class="server-form">
          <label>名称 <input v-model="serverEdit.name" class="db-input" placeholder="如 GitHub MCP" /></label>
          <label>传输方式
            <select v-model="serverEdit.transport" class="db-input">
              <option value="http">HTTP（远程服务器）</option>
              <option value="stdio">stdio（本地进程，如 npx）</option>
            </select>
          </label>
          <template v-if="serverEdit.transport === 'http'">
            <label>Base URL <input v-model="serverEdit.base_url" class="db-input" placeholder="https://example.com/mcp" /></label>
            <label>API Key（可选）
              <input v-model="serverEdit.api_key" class="db-input" type="password" placeholder="留空则不发送鉴权头" />
            </label>
          </template>
          <template v-else>
            <label>启动命令
              <input v-model="serverEdit.command" class="db-input" placeholder="如 npx" />
            </label>
            <label>参数（逗号分隔，可选）
              <input v-model="serverEdit.args" class="db-input" placeholder="如 -y,@modelcontextprotocol/server-filesystem,D:/data" />
            </label>
          </template>
          <label class="server-check">
            <input type="checkbox" v-model="serverEdit.enabled" /> 启用（启动时自动连接）
          </label>
        </div>
        <div class="server-modal-foot">
          <button class="act-btn" @click="serverModal = false">取消</button>
          <button class="rc-save-btn" :disabled="serverSaving" @click="saveServer">
            <i v-if="serverSaving" class="fas fa-circle-notch fa-spin" />
            <i v-else class="fas fa-save" /> 保存
          </button>
        </div>
      </div>
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
import {
  getRuntimeConfig, updateRuntimeConfig,
  listMcpServers, createMcpServer, updateMcpServer,
  deleteMcpServer, connectMcpServer, disconnectMcpServer,
} from '@/services/api'

// ── MCP 服务器（G2） ──────────────────────────────────────
const servers      = ref([])
const serverModal  = ref(false)
const serverSaving = ref(false)
const serverEdit   = ref({ name: '', base_url: '', api_key: '',
  transport: 'http', command: '', args: '', enabled: true })

const loadServers = async () => {
  const res = await listMcpServers()
  servers.value = res?.servers || []
}

const openServerModal = (server) => {
  serverEdit.value = server
    ? { id: server.id, name: server.name, base_url: server.base_url,
        api_key: '', transport: server.transport || 'http',
        command: server.command || '',
        args: Array.isArray(server.args) ? server.args.join(', ') : '',
        enabled: server.enabled }
    : { name: '', base_url: '', api_key: '',
        transport: 'http', command: '', args: '', enabled: true }
  serverModal.value = true
}

const saveServer = async () => {
  if (!serverEdit.value.name) {
    ElMessage({ message: '名称必填', type: 'warning', duration: 2000 })
    return
  }
  if (serverEdit.value.transport === 'http' && !serverEdit.value.base_url) {
    ElMessage({ message: 'HTTP 传输需要填写 Base URL', type: 'warning', duration: 2000 })
    return
  }
  if (serverEdit.value.transport === 'stdio' && !serverEdit.value.command) {
    ElMessage({ message: 'stdio 传输需要填写启动命令', type: 'warning', duration: 2000 })
    return
  }
  serverSaving.value = true
  try {
    const payload = { ...serverEdit.value }
    if (!payload.api_key) delete payload.api_key
    if (payload.transport === 'stdio') {
      payload.args = String(payload.args || '').split(',')
        .map(s => s.trim()).filter(Boolean)
      delete payload.base_url
      delete payload.api_key
    }
    const res = serverEdit.value.id
      ? await updateMcpServer(serverEdit.value.id, payload)
      : await createMcpServer(payload)
    if (res?.success) {
      ElMessage({ message: '已保存', type: 'success', duration: 1500 })
      serverModal.value = false
      await loadServers()
    } else {
      ElMessage({ message: res?.message || '保存失败', type: 'error', duration: 3000 })
    }
  } finally {
    serverSaving.value = false
  }
}

const connect = async (id) => {
  const res = await connectMcpServer(id)
  if (res?.success) {
    ElMessage({ message: `已连接，注册 ${res.tool_count || 0} 个工具`, type: 'success', duration: 2500 })
  } else {
    ElMessage({ message: res?.message || '连接失败', type: 'error', duration: 3500 })
  }
  await loadServers()
}

const disconnect = async (id) => {
  await disconnectMcpServer(id)
  await loadServers()
}

const remove = async (id) => {
  await deleteMcpServer(id)
  await loadServers()
}

// ── 系统资源配置 ──────────────────────────────────────────
const rcEdit   = ref({})
const rcBase   = ref({})
const rcSaving = ref(false)

const loadRcConfig = async () => {
  const data = await getRuntimeConfig()
  if (data?.config) {
    if (Object.keys(rcEdit.value).length === 0) {
      rcEdit.value = { ...data.config }
      rcBase.value = { ...data.config }
    }
  }
}

const saveRuntimeConfig = async () => {
  const changed = {}
  for (const key of Object.keys(rcEdit.value)) {
    if (rcEdit.value[key] !== rcBase.value[key]) {
      changed[key] = rcEdit.value[key]
    }
  }
  if (Object.keys(changed).length === 0) {
    ElMessage({ message: '没有需要保存的变更', type: 'info', duration: 2000 })
    return
  }
  rcSaving.value = true
  try {
    const res = await updateRuntimeConfig(changed)
    if (res?.success) {
      ElMessage({ message: `已更新 ${Object.keys(res.updated || {}).length} 项配置`, type: 'success', duration: 2000 })
      rcBase.value = { ...rcEdit.value }
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
  loadServers()
})
</script>

<style scoped>
.mcp-view {
  height: 100%;
  padding: var(--space-4-5);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3-5);
  background: var(--color-surface-sunken);
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-desc { font-size: var(--text-sm); color: var(--color-text-secondary); }

.config-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4) var(--space-4-5);

  box-shadow: var(--shadow-card);}
.config-title {
  font-size: var(--text-base);
  font-weight: var(--weight-semibold);
  color: var(--color-text);
  margin-bottom: var(--space-1);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.config-title i { color: var(--color-primary); }
.config-hint { font-size: var(--text-xs); color: var(--color-text-muted); margin-bottom: var(--space-3-5); }
.config-grid { display: flex; flex-direction: column; gap: var(--space-3-5); }
.config-item { display: flex; flex-direction: column; gap: var(--space-1); }
.cfg-label   { font-size: var(--text-sm); font-weight: var(--weight-medium); color: var(--color-text); }
.cfg-input-row {
  display: flex; align-items: center; gap: var(--space-1-5);
}
.cfg-input {
  flex: 1;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1-5) var(--space-2-5);
  font-size: var(--text-sm);
  outline: none;
  transition: border-color var(--duration-base);
}
.cfg-input:focus { border-color: var(--color-primary); }
.cfg-eye {
  background: none; border: none; color: var(--color-text-muted); cursor: pointer;
  font-size: var(--text-sm); padding: var(--space-1-5);
}
.cfg-eye:hover { color: var(--color-primary); }
.cfg-desc { font-size: var(--text-xs); color: var(--color-text-muted); }
.config-footer {
  display: flex; align-items: center; gap: var(--space-3-5); margin-top: var(--space-4);
  border-top: 1px solid var(--color-border); padding-top: var(--space-3);
}
.cfg-save-btn {
  display: flex; align-items: center; gap: var(--space-1-5);
  background: var(--color-primary); color: var(--color-text-on-primary);
  border: none; border-radius: var(--radius-sm);
  padding: var(--space-2) var(--space-4); font-size: var(--text-sm); cursor: pointer;
  transition: background var(--duration-base);
}
.cfg-save-btn:hover:not(:disabled) { background: var(--color-primary); }
.cfg-save-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.cfg-tip { font-size: var(--text-xs); color: var(--color-text-muted); }

.param-grid { display: flex; flex-direction: column; gap: var(--space-3-5); }
.param-item { display: flex; flex-direction: column; gap: var(--space-1-5); }
.param-item label {
  display: flex; justify-content: space-between;
  font-size: var(--text-sm); color: var(--color-text);
}
.param-val { font-weight: var(--weight-semibold); color: var(--color-primary); }
.param-slider {
  width: 100%; accent-color: var(--color-primary); cursor: pointer;
}
.param-range {
  display: flex; justify-content: space-between;
  font-size: var(--text-xs); color: var(--color-text-muted);
}

/* 系统资源配置表单 */
.rc-form-grid { display: flex; flex-direction: column; gap: var(--space-4); }
.rc-group { background: var(--color-surface-sunken); border-radius: var(--radius-sm); padding: var(--space-3-5) var(--space-4); }
.rc-group-title {
  font-size: var(--text-sm-2); font-weight: var(--weight-semibold); color: var(--color-text);
  margin-bottom: var(--space-2-5); padding-bottom: var(--space-1-5); border-bottom: 1px solid var(--color-border);
}
.rc-field { display: flex; flex-direction: column; gap: var(--space-1); margin-top: var(--space-2-5); }
.rc-field label { font-size: var(--text-sm-2); color: var(--color-text); display: flex; align-items: center; gap: var(--space-1-5); }
.rc-range { font-size: var(--text-xs); color: var(--color-text-muted); }
.rc-hint-text { font-size: var(--text-xs); color: var(--color-text-muted); margin-bottom: var(--space-0-5); }
.rc-slider-row { display: flex; align-items: center; gap: var(--space-2-5); }
.rc-slider-row input[type="range"] { flex: 1; accent-color: var(--color-primary); cursor: pointer; }
.rc-num {
  width: 70px; border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  padding: var(--space-1) var(--space-1-5); font-size: var(--text-sm-2); text-align: center;
}
.rc-save-btn {
  padding: var(--space-1-5) var(--space-4); background: var(--color-primary); color: var(--color-text-on-primary);
  border: none; border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer;
  display: flex; align-items: center; gap: var(--space-1-5); transition: opacity var(--duration-base);
}
.rc-save-btn:hover:not(:disabled) { opacity: 0.85; }
.rc-save-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* MCP 服务器 */
.server-empty { font-size: var(--text-sm); color: var(--color-text-muted); padding: var(--space-3) 0; }
.server-list { display: flex; flex-direction: column; gap: var(--space-2); }
.server-row {
  display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap;
  border: 1px solid var(--color-surface-sunken); border-radius: var(--radius-sm); padding: var(--space-2-5) var(--space-3-5);
}
.server-main { display: flex; flex-direction: column; gap: var(--space-0-5); flex: 1; min-width: 180px; }
.server-name { font-size: var(--text-sm); font-weight: var(--weight-semibold); color: var(--color-text); }
.server-url { font-size: var(--text-xs); color: var(--color-text-muted); word-break: break-all; }
.server-transport {
  font-size: var(--text-2xs); color: var(--color-accent); background: var(--color-success-soft);
  padding: var(--space-px) var(--space-2); border-radius: var(--radius-sm); text-transform: uppercase;
  align-self: flex-start;
}
.server-tools {
  font-size: var(--text-2xs); color: var(--color-primary); background: var(--color-violet-soft);
  padding: var(--space-px) var(--space-2); border-radius: var(--radius-sm); align-self: flex-start;
}
.server-status {
  font-size: var(--text-xs); font-weight: var(--weight-semibold); padding: var(--space-0-5) var(--space-2-5); border-radius: var(--radius-sm);
}
.st-on { background: var(--color-success-soft); color: var(--color-success); }
.st-off { background: var(--color-surface-sunken); color: var(--color-text-secondary); }
.server-actions { display: flex; gap: var(--space-1-5); flex-wrap: wrap; }
.act-btn {
  display: flex; align-items: center; gap: var(--space-1); font-size: var(--text-xs);
  border: 1px solid var(--color-border); background: var(--color-surface); color: var(--color-text);
  border-radius: var(--radius-sm); padding: var(--space-1) var(--space-2-5); cursor: pointer; transition: var(--transition-interactive);
}
.act-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.act-btn.danger:hover { border-color: var(--color-danger); color: var(--color-danger); }
.server-modal-mask {
  position: fixed; inset: 0; background: var(--color-scrim); z-index: 100;
  display: flex; align-items: center; justify-content: center;
}
.server-modal {
  width: min(480px, 92vw); background: var(--color-surface); border-radius: var(--radius-md); padding: var(--space-4) var(--space-4-5);
}
.server-modal-title {
  font-size: var(--text-base); font-weight: var(--weight-semibold); color: var(--color-text);
  display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-3-5);
}
.modal-close { background: none; border: none; font-size: var(--text-xl); color: var(--color-text-muted); cursor: pointer; }
.server-form { display: flex; flex-direction: column; gap: var(--space-2-5); }
.server-form label {
  display: flex; flex-direction: column; gap: var(--space-1); font-size: var(--text-sm-2); color: var(--color-text);
}
.server-form .server-check { flex-direction: row; align-items: center; gap: var(--space-2); }
.server-modal-foot { display: flex; justify-content: flex-end; gap: var(--space-2); margin-top: var(--space-4); }

/* 数据库配置 */
.db-status {
  margin-left: auto;
  font-size: var(--text-xs);
  font-weight: var(--weight-medium);
  padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-sm);
}
.db-status--on  { background: var(--color-success-soft); color: var(--color-success); }
.db-status--off { background: var(--color-danger-soft); color: var(--color-danger); }
.db-form { display: flex; flex-direction: column; gap: var(--space-2-5); }
.db-row  { display: flex; gap: var(--space-2-5); }
.db-field { display: flex; flex-direction: column; gap: var(--space-1); flex: 1; }
.db-field label { font-size: var(--text-sm-2); color: var(--color-text); }
.db-field--wide { flex: 2; }
.db-input {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1-5) var(--space-2-5);
  font-size: var(--text-sm);
  outline: none;
  transition: border-color var(--duration-base);
}
.db-input:focus { border-color: var(--color-primary); }
.db-input--port { width: 90px; }
.db-select {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1-5) var(--space-2-5);
  font-size: var(--text-sm);
  background: var(--color-surface);
  outline: none;
  cursor: pointer;
}
.db-select:focus { border-color: var(--color-primary); }
</style>
