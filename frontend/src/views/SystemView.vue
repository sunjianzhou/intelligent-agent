<template>
  <div class="system-view">
    <!-- 工具栏 -->
    <div class="toolbar">
      <span class="page-desc">系统运行状态实时监控</span>
      <div class="toolbar-right">
        <span class="countdown-badge">{{ countdown }}s 后刷新</span>
        <button class="refresh-btn" :class="{ spinning: loading }" @click="refresh">
          <i class="fas fa-sync-alt" /> 刷新
        </button>
      </div>
    </div>

    <!-- 服务状态 -->
    <div class="card-row">
      <div class="status-card">
        <div class="card-label">Client 客户端</div>
        <div class="status-badge ok"><i class="fas fa-check-circle" /> 已连接</div>
        <div class="card-sub">Vue 3 · 当前浏览器</div>
      </div>
      <div class="status-card">
        <div class="card-label">前端服务</div>
        <div class="status-badge ok"><i class="fas fa-check-circle" /> 运行中</div>
        <div class="card-sub">Vite Dev · localhost:3000</div>
      </div>
      <div class="status-card">
        <div class="card-label">Java 后端</div>
        <div class="status-badge" :class="javaOk === null ? 'checking' : javaOk === 'timeout' ? 'timeout' : javaOk ? 'ok' : 'err'">
          <i :class="javaOk === null ? 'fas fa-spinner fa-spin' : javaOk === 'timeout' ? 'fas fa-clock' : javaOk ? 'fas fa-check-circle' : 'fas fa-times-circle'" />
          {{ javaOk === null ? '检测中' : javaOk === 'timeout' ? '检测超时' : javaOk ? '运行中' : '未连接' }}
          <button v-if="javaOk === 'timeout'" class="retry-btn" @click="refresh"><i class="fas fa-redo" /> 重试</button>
        </div>
        <div class="card-sub">Spring Boot · localhost:8080</div>
      </div>
      <div class="status-card">
        <div class="card-label">Python Agent</div>
        <div class="status-badge" :class="pythonOk === null ? 'checking' : pythonOk === 'timeout' ? 'timeout' : pythonOk ? 'ok' : 'err'">
          <i :class="pythonOk === null ? 'fas fa-spinner fa-spin' : pythonOk === 'timeout' ? 'fas fa-clock' : pythonOk ? 'fas fa-check-circle' : 'fas fa-times-circle'" />
          {{ pythonOk === null ? '检测中' : pythonOk === 'timeout' ? '检测超时' : pythonOk ? '运行中' : '未连接' }}
          <button v-if="pythonOk === 'timeout'" class="retry-btn" @click="refresh"><i class="fas fa-redo" /> 重试</button>
        </div>
        <div class="card-sub">FastAPI · localhost:8000</div>
      </div>

      <!-- 第四张卡：云端模型 or Ollama -->
      <div class="status-card" :class="{ 'cloud-card': cloudMode }">
        <div class="card-label">
          <i v-if="cloudMode" class="fas fa-cloud" style="color:#667eea;margin-right:4px" />
          {{ cloudMode ? '云端模型' : 'Ollama' }}
        </div>
        <div class="status-badge ok">
          <i class="fas fa-check-circle" />
          {{ cloudMode ? '已连接' : (ollamaOk ? '已连接' : '未连接') }}
        </div>
        <div class="card-sub" v-if="cloudMode" style="word-break:break-all">
          {{ cloudModel }}
        </div>
        <div class="card-sub" v-else>
          {{ currentModel || '无模型' }}
        </div>
        <div class="cloud-url" v-if="cloudMode">{{ cloudBaseUrl }}</div>
      </div>
    </div>

    <!-- 图表行 -->
    <div class="chart-row">
      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-microchip" /> CPU</span>
          <span class="chart-val" :class="colorClass(resources.cpu_percent)">
            {{ resources.cpu_percent ?? '-' }}%
          </span>
        </div>
        <SparkLine :data="history.cpu" :color="lineColor(resources.cpu_percent)" />
        <div class="chart-detail">
          <span>{{ resources.cpu_count ?? '-' }} 核心</span>
          <span>占用约 {{ resources.cpu_used_cores ?? '-' }} 核</span>
        </div>
        <div class="core-grid" v-if="resources.cpu_per_core?.length">
          <div v-for="(v, i) in resources.cpu_per_core" :key="i"
               class="core-bar-wrap" :title="`核心${i+1}: ${v}%`">
            <div class="core-bar" :style="{ height: v + '%' }" :class="colorClass(v)" />
          </div>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-memory" /> 系统内存</span>
          <span class="chart-val" :class="colorClass(resources.memory_percent)">
            {{ resources.memory_percent ?? '-' }}%
          </span>
        </div>
        <SparkLine :data="history.memory" :color="lineColor(resources.memory_percent)" />
        <div class="chart-detail">
          <span>已用 {{ resources.memory_used_gb ?? '-' }} GB</span>
          <span>共 {{ resources.memory_total_gb ?? '-' }} GB</span>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-tv" /> GPU</span>
          <span class="chart-val" :class="colorClass(resources.gpu?.util_percent)">
            {{ resources.gpu?.util_percent ?? '-' }}%
          </span>
        </div>
        <SparkLine :data="history.gpu" :color="lineColor(resources.gpu?.util_percent)" />
        <template v-if="resources.gpu">
          <div class="chart-detail">
            <span>{{ resources.gpu.temperature }}°C</span>
            <span>显存 {{ resources.gpu.mem_used_mb }}/{{ resources.gpu.mem_total_mb }} MB</span>
          </div>
          <div class="mini-progress-wrap">
            <div class="mini-progress-bar gpu-bar"
                 :style="{ width: resources.gpu.mem_percent + '%' }" />
          </div>
          <div class="gpu-name">{{ resources.gpu.name }}</div>
        </template>
        <div v-else-if="resources.gpu === null || resources.gpu === undefined" class="gpu-empty">
          <i class="fas fa-info-circle" style="font-size:1rem;color:#aaa;display:block;margin-bottom:4px" />
          未检测到独立 GPU
          <div class="gpu-empty-sub">集成显卡或无显卡环境，LLM 使用 CPU 推理</div>
        </div>
      </div>

      <div class="chart-card">
        <div class="chart-header">
          <span class="chart-title"><i class="fas fa-bolt" /> 响应时间</span>
          <span class="chart-val" :class="rtColorClass(lastRt)">
            {{ lastRt != null ? lastRt.toFixed(1) + 's' : '-' }}
          </span>
        </div>
        <SparkLine :data="rtHistory" :color="rtColor" />
        <div class="chart-detail">
          <span>最近 {{ responseTimes.length }} 次</span>
          <span v-if="avgRt">均值 {{ avgRt }}s</span>
        </div>
      </div>
    </div>

    <!-- 详情行 -->
    <div class="detail-row">
      <!-- 内存分布（Agent 服务 + 其他进程对齐系统用量） -->
      <div class="detail-card">
        <div class="detail-title"><i class="fas fa-layer-group" /> 内存分布</div>
        <div v-if="Object.keys(processes).length === 0" class="empty-tip">暂无进程数据</div>
        <div v-else class="process-list">
          <div v-for="(info, name) in processesSorted" :key="name"
               class="process-item" :class="{ 'process-other': name === '其他进程' }">
            <div class="process-header">
              <span class="process-name">
                <i :class="processIcon(name)" />
                {{ name }}
                <span v-if="name === '其他进程'" class="other-tip">OS + 其他应用</span>
              </span>
              <div style="display:flex;align-items:center;gap:8px">
                <span class="process-mem">{{ info.mem_mb }} MB</span>
                <button v-if="name === '其他进程' && topOthers.length"
                        class="expand-btn" @click="showOthers = !showOthers">
                  {{ showOthers ? '收起' : '展开详情' }}
                </button>
              </div>
            </div>
            <div class="process-bar-wrap">
              <div class="process-bar"
                   :style="{ width: processBarWidth(info.mem_mb) + '%' }"
                   :class="processBarColor(info.mem_mb, name)" />
            </div>
            <!-- 展开：Top-10 其他进程 -->
            <div v-if="name === '其他进程' && showOthers" class="other-detail">
              <div v-for="p in topOthers" :key="p.name" class="other-row">
                <span class="other-name">{{ p.name }}{{ p.count > 1 ? ` ×${p.count}` : '' }}</span>
                <span class="other-bar-wrap">
                  <span class="other-bar" :style="{ width: processBarWidth(p.mem_mb) + '%' }" />
                </span>
                <span class="other-mem">{{ p.mem_mb }} MB</span>
              </div>
              <div class="other-row other-row-hint">
                <span class="other-name" style="color:#bbb">（其余小进程未列出）</span>
                <span></span><span></span>
              </div>
            </div>
          </div>
          <div class="process-total">
            <span>Agent 服务合计</span>
            <span class="total-val">{{ agentTotalMb }} MB</span>
          </div>
          <div class="process-total mem-breakdown-hint">
            <span>系统已用合计</span>
            <span class="total-val">{{ Math.round((resources.memory_used_gb || 0) * 1024) }} MB</span>
          </div>
        </div>
      </div>

      <!-- Ollama 已加载模型 + 磁盘 -->
      <div class="detail-card">
        <!-- 云端模型信息 or Ollama 模型 -->
        <div v-if="cloudMode" class="cloud-info-box">
          <div class="detail-title"><i class="fas fa-cloud" /> 云端模型信息</div>
          <div class="cloud-info-item">
            <span class="ci-label">模型名称</span>
            <span class="ci-val model-name-highlight">{{ cloudModel }}</span>
          </div>
          <div class="cloud-info-item">
            <span class="ci-label">接入地址</span>
            <span class="ci-val">{{ cloudBaseUrl }}</span>
          </div>
          <div class="cloud-info-item">
            <span class="ci-label">状态</span>
            <span class="status-badge ok" style="font-size:0.78rem;padding:2px 10px">
              <i class="fas fa-check-circle" /> 已连接
            </span>
          </div>
        </div>
        <div v-else>
          <div class="detail-title"><i class="fas fa-brain" /> Ollama 已加载模型</div>
          <div v-if="ollamaModels.length === 0" class="empty-tip">当前无模型加载在显存中</div>
          <div v-else class="ollama-model-list">
            <div v-for="m in ollamaModels" :key="m.name" class="ollama-model-item">
              <div class="ollama-model-row">
                <span class="ollama-model-name">{{ m.name }}</span>
                <span class="ollama-model-size">{{ m.size_gb }} GB</span>
              </div>
              <div class="ollama-model-meta">
                <span class="vram-badge">显存 {{ m.vram_gb }} GB</span>
                <span class="expires-badge" v-if="m.expires_at">
                  {{ formatExpires(m.expires_at) }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="divider" />

        <div class="detail-title" style="margin-top:0">
          <i class="fas fa-hdd" /> 磁盘使用
        </div>
        <div v-if="disks.length === 0" class="empty-tip">暂无磁盘数据</div>
        <div v-else class="disk-list">
          <div v-for="disk in disks" :key="disk.mountpoint" class="disk-item"
               :class="{ 'disk-warn': disk.percent >= 85 }">
            <div class="disk-header">
              <span class="disk-mount">{{ disk.mountpoint }}</span>
              <span class="disk-usage">{{ disk.used_gb }} / {{ disk.total_gb }} GB</span>
              <span class="disk-pct" :class="colorClass(disk.percent)">{{ disk.percent }}%</span>
              <span v-if="disk.percent >= 90" class="disk-alert-badge danger">空间严重不足</span>
              <span v-else-if="disk.percent >= 85" class="disk-alert-badge warn">空间紧张</span>
            </div>
            <div class="disk-bar-wrap">
              <div class="disk-bar" :style="{ width: disk.percent + '%' }"
                   :class="diskBarColor(disk.percent)" />
            </div>
            <div class="disk-free">剩余 {{ disk.free_gb }} GB</div>
          </div>
        </div>

        <div class="sys-info-row">
          <span>Java {{ sysInfo.java_version || '-' }}</span>
          <span>工具 {{ sysInfo.tools_count ?? '-' }} 个</span>
          <span>{{ lastUpdated }}</span>
        </div>
      </div>
    </div>

    <!-- 资源配置面板 -->
    <div class="detail-card resource-config-card">
      <div class="detail-title">
        <i class="fas fa-sliders-h" /> 资源配置
        <span class="rc-tip">修改后立即生效，重启前有效</span>
        <button class="rc-save-btn" :class="{ saving: rcSaving }" @click="saveRuntimeConfig" :disabled="rcSaving">
          <i class="fas fa-save" /> {{ rcSaving ? '保存中…' : '保存配置' }}
        </button>
      </div>

      <div class="rc-body">
        <!-- 左：实时用量 -->
        <div class="rc-usage-col">
          <div class="rc-section-label"><i class="fas fa-chart-bar" /> 实时用量</div>
          <div class="rc-usage-grid">
            <div class="rc-usage-item">
              <span class="ru-label">推理并发</span>
              <div class="ru-bar-wrap">
                <div class="ru-bar" :style="{ width: usagePct(rcUsage.active_inferences, rcCfg.inference_concurrency) + '%' }" />
              </div>
              <span class="ru-val">{{ rcUsage.active_inferences ?? 0 }} / {{ rcCfg.inference_concurrency }}</span>
            </div>
            <div class="rc-usage-item">
              <span class="ru-label">等待队列</span>
              <div class="ru-bar-wrap">
                <div class="ru-bar ru-bar-queue" :style="{ width: usagePct(rcCfg.inference_queue_size - (rcUsage.queue_slots ?? rcCfg.inference_queue_size), rcCfg.inference_queue_size) + '%' }" />
              </div>
              <span class="ru-val">已用 {{ rcCfg.inference_queue_size - (rcUsage.queue_slots ?? rcCfg.inference_queue_size) }} / {{ rcCfg.inference_queue_size }}</span>
            </div>
            <div class="rc-usage-item">
              <span class="ru-label">L1 精确缓存</span>
              <div class="ru-bar-wrap">
                <div class="ru-bar ru-bar-cache" :style="{ width: usagePct(rcUsage.l1_cache_entries, rcCfg.response_cache_max_size) + '%' }" />
              </div>
              <span class="ru-val">{{ rcUsage.l1_cache_entries ?? 0 }} / {{ rcCfg.response_cache_max_size }}</span>
            </div>
            <div class="rc-usage-item">
              <span class="ru-label">L2 语义缓存</span>
              <div class="ru-bar-wrap">
                <div class="ru-bar ru-bar-cache" :style="{ width: usagePct(rcUsage.l2_cache_entries, rcCfg.semantic_cache_max_entries) + '%' }" />
              </div>
              <span class="ru-val">{{ rcUsage.l2_cache_entries ?? 0 }} / {{ rcCfg.semantic_cache_max_entries }}</span>
            </div>
            <div class="rc-usage-item">
              <span class="ru-label">短期记忆</span>
              <div class="ru-bar-wrap">
                <div class="ru-bar ru-bar-mem" :style="{ width: usagePct(rcUsage.short_term_entries, rcCfg.short_term_max_size) + '%' }" />
              </div>
              <span class="ru-val">{{ rcUsage.short_term_entries ?? 0 }} / {{ rcCfg.short_term_max_size }}</span>
            </div>
            <div class="rc-usage-item">
              <span class="ru-label">长期记忆</span>
              <div class="ru-bar-wrap">
                <div class="ru-bar ru-bar-mem" style="background:#a78bfa" :style="{ width: Math.min(100, (rcUsage.long_term_entries ?? 0) / 200 * 100) + '%' }" />
              </div>
              <span class="ru-val">{{ rcUsage.long_term_entries ?? 0 }} 条</span>
            </div>
          </div>
        </div>

        <!-- 右：可配置参数 -->
        <div class="rc-form-col">
          <div class="rc-section-label"><i class="fas fa-cog" /> 可配置参数</div>
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
                <label>
                  上下文窗口 (num_ctx)
                  <span class="rc-range">[512–131072]</span>
                </label>
                <div class="rc-hint-text">
                  影响显存/内存占用。GTX1660 6GB 建议 ≤ 16384；纯CPU可到 32768。修改后下条消息生效。
                </div>
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
    </div>

    <!-- 内存优化建议已移除（功能冗余） -->
    <div v-if="false" class="detail-card mem-tips-card">
      <div class="tips-grid">

        <div class="tip-group">
          <div class="tip-group-title"><i class="fab fa-node-js" style="color:#68a063"/> 前端 (当前 ~{{ frontendMemMb }} MB)</div>
          <div class="tip-item tip-high">
            <span class="tip-tag high">最有效</span>
            <span>改用生产构建 <code>npm run build</code>，Nginx/Java 提供静态文件，Node.js 进程彻底退出。可释放 <b>~1.8 GB</b></span>
          </div>
          <div class="tip-item">
            <span class="tip-tag mid">开发期</span>
            <span>仅 1 个标签调试时关闭其他浏览器标签，Vite HMR 不额外占用</span>
          </div>
        </div>

        <div class="tip-group">
          <div class="tip-group-title"><i class="fab fa-python" style="color:#3572a5"/> Python Agent (当前 ~{{ pythonMemMb }} MB)</div>
          <div class="tip-item tip-high">
            <span class="tip-tag high">最有效</span>
            <span>环境变量 <code>USE_LIGHTWEIGHT_EMBEDDING=true</code>，跳过 PyTorch 加载 sentence-transformers，改用轻量 BM25，可节省 <b>~200 MB</b></span>
          </div>
          <div class="tip-item">
            <span class="tip-tag mid">推荐</span>
            <span>缩小短期记忆上限 <code>SHORT_TERM_MAX_SIZE=50</code>（在「资源配置」面板调整）</span>
          </div>
          <div class="tip-item">
            <span class="tip-tag low">可选</span>
            <span>缩减 L1/L2 缓存条目上限，减少 OrderedDict / ChromaDB 内存占用</span>
          </div>
        </div>

        <div class="tip-group">
          <div class="tip-group-title"><i class="fab fa-java" style="color:#b07219"/> Java 后端 (当前 ~{{ javaMemMb }} MB)</div>
          <div class="tip-item tip-high">
            <span class="tip-tag high">最有效</span>
            <span>限制 JVM 堆大小。在 <code>start.ps1</code> 的 <code>mvn</code> 行加上：<br/>
              <code>-Dspring-boot.run.jvmArguments="-Xms64m -Xmx256m"</code><br/>
              可将 JVM 从 ~830 MB 降至 <b>~300 MB</b></span>
          </div>
          <div class="tip-item">
            <span class="tip-tag mid">推荐</span>
            <span>打包成 JAR 后用 <code>java -Xmx256m -jar app.jar</code> 运行，比 Maven 运行省 ~100 MB</span>
          </div>
        </div>

      </div>
    </div>

    <!-- 已安装/可用模型（可折叠） -->
    <div class="detail-card model-card">
      <div class="detail-title collapsible-title" @click="showModelList = !showModelList" style="cursor:pointer;user-select:none">
        <i class="fas fa-cube" /> 可用模型
        <span class="rc-tip">{{ models.length }} 个</span>
        <i :class="showModelList ? 'fas fa-chevron-up' : 'fas fa-chevron-down'" style="margin-left:auto;font-size:0.75rem;color:#bbb" />
      </div>
      <div v-if="showModelList" class="model-list">
        <!-- 当前是云端模型时，顶部高亮显示 -->
        <div v-if="cloudMode" class="model-item active cloud-model-item">
          <i class="fas fa-cloud" />
          <span class="model-name">{{ cloudModel }}</span>
          <span class="model-badge cloud-badge">云端 · 使用中</span>
        </div>

        <!-- 所有模型列表 -->
        <div v-for="m in models" :key="m"
            class="model-item"
            :class="{ active: !cloudMode && m === currentModel }">
          <i :class="cloudModel && m === cloudModel ? 'fas fa-cloud' : 'fas fa-cube'" />
          <span class="model-name">{{ m }}</span>
          <!-- 当前使用中标注 -->
          <span v-if="!cloudMode && m === currentModel" class="model-badge">当前</span>
          <!-- 云端标注（有云端配置且匹配） -->
          <span v-if="cloudModel && m === cloudModel"
                class="model-local-badge"
                style="background:#f0f2ff;color:#667eea">云端</span>
          <!-- 本地标注（非云端模型） -->
          <span v-else class="model-local-badge">本地</span>
        </div>

        <div v-if="models.length === 0" class="empty-tip">暂无可用模型</div>
      </div>
    </div>

    <!-- 云端服务商配置 -->
    <div class="detail-card cloud-providers-card">
      <div class="detail-title">
        <i class="fas fa-cloud-upload-alt" style="color:#667eea" />
        云端服务商
        <span class="rc-tip">{{ cloudProviders.length }} 个已配置</span>
        <button class="cp-add-btn" @click="openCpAdd">
          <i class="fas fa-plus" /> 添加服务商
        </button>
      </div>

      <div v-if="cloudProviders.length === 0" class="cp-empty">
        <i class="fas fa-cloud" style="font-size:2rem;color:#dde;display:block;margin-bottom:10px" />
        暂无云端服务商配置。点击「添加服务商」接入 OpenAI、DeepSeek、阿里云百炼等兼容接口。
      </div>

      <div v-else class="cp-list">
        <div v-for="p in cloudProviders" :key="p.id"
             class="cp-card"
             :class="{ 'cp-active': p.is_active }">
          <div class="cp-card-header">
            <div class="cp-name">
              <i class="fas fa-cloud" :style="{ color: p.is_active ? '#667eea' : '#bbb' }" />
              {{ p.name }}
            </div>
            <span v-if="p.is_active" class="cp-active-badge">
              <i class="fas fa-check-circle" /> 使用中
            </span>
          </div>
          <div class="cp-meta">
            <span class="cp-provider-tag">{{ p.provider }}</span>
            <span class="cp-model-name">{{ p.model }}</span>
          </div>
          <div class="cp-url" :title="p.base_url">{{ p.base_url || '（默认接口地址）' }}</div>
          <div class="cp-key"><i class="fas fa-key" /> {{ p.api_key_masked }}</div>
          <div class="cp-actions">
            <button v-if="!p.is_active" class="cp-btn cp-btn-activate" @click="activateCp(p.id)">
              <i class="fas fa-power-off" /> 激活
            </button>
            <button v-else class="cp-btn cp-btn-deactivate" @click="deactivateCp">
              <i class="fas fa-toggle-off" /> 停用
            </button>
            <button class="cp-btn cp-btn-edit" @click="openCpEdit(p)">
              <i class="fas fa-pen" /> 编辑
            </button>
            <button class="cp-btn cp-btn-del" @click="deleteCp(p.id, p.name)">
              <i class="fas fa-trash" /> 删除
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 云端服务商 添加/编辑 对话框 -->
    <div v-if="showCpDialog" class="modal-overlay" @click.self="showCpDialog = false">
      <div class="modal-box">
        <div class="modal-header">
          <span class="modal-title">
            {{ cpEditId ? '编辑服务商' : '添加云端服务商' }}
          </span>
          <button class="modal-close" @click="showCpDialog = false">
            <i class="fas fa-times" />
          </button>
        </div>
        <div class="modal-body">
          <!-- 名称 -->
          <div class="form-row">
            <label>名称 <span class="required">*</span></label>
            <input v-model="cpForm.name" placeholder="如：DeepSeek 生产账号" />
          </div>
          <!-- 服务商 -->
          <div class="form-row">
            <label>服务商类型</label>
            <select v-model="cpForm.provider" @change="onCpProviderChange">
              <option value="">-- 自定义 --</option>
              <option v-for="preset in cpPresets" :key="preset.key" :value="preset.key">
                {{ preset.label }}
              </option>
            </select>
          </div>
          <!-- Base URL -->
          <div class="form-row">
            <label>Base URL <span class="rc-tip" style="margin-left:4px">（服务商选择后自动填充，可自定义）</span></label>
            <input v-model="cpForm.base_url" placeholder="https://api.openai.com/v1" />
          </div>
          <!-- API Key -->
          <div class="form-row">
            <label>
              API Key <span class="required">*</span>
              <span v-if="cpEditId" class="rc-tip" style="margin-left:4px">（留空保持原密钥）</span>
            </label>
            <input v-model="cpForm.api_key" type="password" placeholder="sk-..." autocomplete="new-password" />
          </div>
          <!-- Model -->
          <div class="form-row">
            <label>模型名称 <span class="required">*</span></label>
            <input v-model="cpForm.model" placeholder="gpt-4o / deepseek-chat / qwen-plus …" />
          </div>
          <!-- 常用模型快选 -->
          <div v-if="cpForm.provider && cpModelSuggestions.length" class="cp-model-chips">
            <span class="cp-chips-label">快选:</span>
            <span v-for="m in cpModelSuggestions" :key="m"
                  class="cp-chip" @click="cpForm.model = m">{{ m }}</span>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="showCpDialog = false">取消</button>
          <button class="btn-confirm" @click="saveCpForm" :disabled="cpSaving">
            <i class="fas fa-save" /> {{ cpSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, defineComponent, h } from 'vue'
import { useWebSocketStore } from '@/stores/websocket'
import { useConfirmDialogStore } from '@/stores/confirmDialog'
import { ElMessage } from 'element-plus'
import {
  getJavaHealth, getPythonHealth, getSystemInfo, getModels, getSystemResources,
  getRuntimeConfig, updateRuntimeConfig,
  listCloudProviders, getCloudPresets,
  createCloudProvider, updateCloudProvider, deleteCloudProvider,
  activateCloudProvider, deactivateCloudProviders,
} from '@/services/api'

// ── SparkLine ─────────────────────────────────────────────
const SparkLine = defineComponent({
  props: {
    data:  { type: Array,  default: () => [] },
    color: { type: String, default: '#667eea' },
  },
  setup(props) {
    return () => {
      const W = 200, H = 40, pad = 2
      const pts = props.data
      if (pts.length < 2)
        return h('svg', { viewBox: `0 0 ${W} ${H}`, style: 'width:100%;height:40px' })
      const min = Math.min(...pts)
      const max = Math.max(...pts) || 1
      const xs  = pts.map((_, i) => pad + (i / (pts.length - 1)) * (W - pad * 2))
      const ys  = pts.map(v => H - pad - ((v - min) / (max - min || 1)) * (H - pad * 2))
      const d   = xs.map((x, i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${ys[i].toFixed(1)}`).join(' ')
      const area = `${d} L${xs[xs.length-1].toFixed(1)},${H} L${xs[0].toFixed(1)},${H} Z`
      const gid  = `g${props.color.replace('#', '')}`
      return h('svg', { viewBox: `0 0 ${W} ${H}`, style: 'width:100%;height:40px;display:block' }, [
        h('defs', [
          h('linearGradient', { id: gid, x1:'0', y1:'0', x2:'0', y2:'1' }, [
            h('stop', { offset: '0%',   'stop-color': props.color, 'stop-opacity': '0.3' }),
            h('stop', { offset: '100%', 'stop-color': props.color, 'stop-opacity': '0.02' }),
          ])
        ]),
        h('path', { d: area, fill: `url(#${gid})` }),
        h('path', { d, fill: 'none', stroke: props.color,
                    'stroke-width': '1.5', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }),
        h('circle', { cx: xs[xs.length-1].toFixed(1), cy: ys[ys.length-1].toFixed(1),
                      r: '3', fill: props.color }),
      ])
    }
  }
})

// ── Store ─────────────────────────────────────────────────
const wsStore         = useWebSocketStore()
const confirmDialog   = useConfirmDialogStore()
const responseTimes = computed(() => wsStore.responseTimes || [])

// ── 状态 ─────────────────────────────────────────────────
const loading      = ref(false)
const showOthers   = ref(false)
const showMemTips  = ref(false)
const showModelList = ref(true)
const topOthers    = ref([])
const javaOk       = ref(null)   // null=检测中, true=ok, false=失败, 'timeout'=超时
const pythonOk     = ref(null)
const ollamaOk     = ref(false)
const currentModel = ref('')
const cloudModel   = ref('')
const cloudBaseUrl = ref('')
const cloudMode    = ref(false)
const models       = ref([])
const sysInfo      = ref({})
const resources    = ref({})
const processes    = ref({})
const disks        = ref([])
const ollamaModels = ref([])
const lastUpdated  = ref('-')
const countdown    = ref(10)

// ── 资源配置 ──────────────────────────────────────────────
const rcCfg    = ref({})   // 服务端当前值（只读展示）
const rcUsage  = ref({})   // 实时用量
const rcEdit   = ref({})   // 用户正在编辑的值
const rcSaving = ref(false)

const usagePct = (used, total) => total > 0 ? Math.min(100, Math.round((used ?? 0) / total * 100)) : 0

const loadRuntimeConfig = async () => {
  const data = await getRuntimeConfig()
  if (!data) return
  rcCfg.value   = data.config  || {}
  rcUsage.value = data.usage   || {}
  // 只在首次加载时初始化编辑值（防止覆盖用户正在编辑的内容）
  if (Object.keys(rcEdit.value).length === 0) {
    rcEdit.value = { ...data.config }
  }
}

const saveRuntimeConfig = async () => {
  rcSaving.value = true
  try {
    const res = await updateRuntimeConfig(rcEdit.value)
    if (res?.success) {
      ElMessage.success(`已更新 ${Object.keys(res.updated || {}).length} 项配置`)
      await loadRuntimeConfig()
    } else {
      const errKeys = Object.keys(res?.errors || {})
      ElMessage.error(errKeys.length ? `${errKeys[0]}: ${res.errors[errKeys[0]]}` : '保存失败')
    }
  } finally {
    rcSaving.value = false
  }
}

const MAX_POINTS = 30
const history = ref({ cpu: [], memory: [], gpu: [] })

const push = (key, val) => {
  if (val == null) return
  history.value[key].push(val)
  if (history.value[key].length > MAX_POINTS) history.value[key].shift()
}

// ── 响应时间 ──────────────────────────────────────────────
const rtHistory    = computed(() => responseTimes.value.map(r => r.time))
const lastRt       = computed(() => {
  const arr = responseTimes.value
  return arr.length ? arr[arr.length - 1].time : null
})
const avgRt = computed(() => {
  if (!responseTimes.value.length) return null
  return (responseTimes.value.reduce((s, r) => s + r.time, 0) / responseTimes.value.length).toFixed(1)
})
const rtColor      = computed(() => lastRt.value == null ? '#667eea' :
  lastRt.value > 30 ? '#e53935' : lastRt.value > 15 ? '#f57c00' : '#43a047')
const rtColorClass = (v) => v == null ? '' : v > 30 ? 'val-danger' : v > 15 ? 'val-warn' : 'val-ok'

// ── 进程 ─────────────────────────────────────────────────
const processesSorted = computed(() =>
  Object.fromEntries(
    Object.entries(processes.value).sort(([nameA, a], [nameB, b]) => {
      if (nameA === '其他进程') return 1
      if (nameB === '其他进程') return -1
      return b.mem_mb - a.mem_mb
    })
  )
)
const agentTotalMb    = computed(() =>
  Object.values(processes.value).reduce((s, v) => s + v.mem_mb, 0).toFixed(1)
)
const totalMemMb      = computed(() => (resources.value.memory_total_gb || 1) * 1024)

// 各端内存（供优化建议卡片显示）
const frontendMemMb = computed(() => {
  const p = processes.value
  return Math.round((p['前端(Node)']?.mem_mb || 0) + (p['前端(Vite)']?.mem_mb || 0))
})
const pythonMemMb = computed(() => Math.round(processes.value['Python Agent']?.mem_mb || 0))
const javaMemMb   = computed(() => Math.round(processes.value['Java 后端']?.mem_mb || 0))
const processBarWidth = (mb) => Math.min(100, Math.round(mb / totalMemMb.value * 100))
const processBarColor = (mb, name) => {
  if (name === '其他进程') return 'bar-other'
  const pct = mb / totalMemMb.value * 100
  return pct >= 30 ? 'bar-danger' : pct >= 15 ? 'bar-warn' : 'bar-ok'
}
const processIcon = (name) => ({
  'Ollama':       'fas fa-robot',
  'Python Agent': 'fab fa-python',
  'Java 后端':    'fab fa-java',
  '前端(Node)':   'fab fa-node-js',
  '前端(Vite)':   'fas fa-bolt',
  '其他进程':     'fas fa-layer-group',
}[name] || 'fas fa-cog')

// ── 磁盘 ─────────────────────────────────────────────────
const diskBarColor = (pct) => pct >= 90 ? 'bar-danger' : pct >= 70 ? 'bar-warn' : 'bar-ok'

// ── Ollama 模型过期 ───────────────────────────────────────
const formatExpires = (iso) => {
  if (!iso) return ''
  const diff = new Date(iso) - Date.now()
  if (diff <= 0)          return '已卸载'
  if (diff < 60000)       return `${Math.floor(diff / 1000)}s 后卸载`
  if (diff < 3600000)     return `${Math.floor(diff / 60000)}min 后卸载`
  return `${Math.floor(diff / 3600000)}h 后卸载`
}

// ── 颜色 ─────────────────────────────────────────────────
const colorClass = (v) => {
  if (v == null) return ''
  return v >= 80 ? 'val-danger' : v >= 60 ? 'val-warn' : 'val-ok'
}
const lineColor = (v) => {
  if (v == null) return '#667eea'
  return v >= 80 ? '#e53935' : v >= 60 ? '#f57c00' : '#43a047'
}

/** 给 Promise 套一个 8s 超时，超时 reject 带 {timedOut: true} 标记 */
const withTimeout = (promise, ms = 8000) =>
  Promise.race([
    promise,
    new Promise((_, rej) => setTimeout(() => rej({ timedOut: true }), ms))
  ])

// ── 数据加载 ──────────────────────────────────────────────
const refresh = async () => {
  loading.value   = true
  countdown.value = 10
  try {
    const [javaRes, pythonRes, sys, modelData, res] = await Promise.allSettled([
      withTimeout(getJavaHealth()),
      withTimeout(getPythonHealth()),
      getSystemInfo(), getModels(), getSystemResources()
    ])

    const java   = javaRes.status   === 'fulfilled' ? javaRes.value   : null
    const python = pythonRes.status === 'fulfilled' ? pythonRes.value : null

    if (javaRes.status === 'rejected' && javaRes.reason?.timedOut)     javaOk.value = 'timeout'
    else javaOk.value   = java != null ? java.status === 'UP' : false

    if (pythonRes.status === 'rejected' && pythonRes.reason?.timedOut) pythonOk.value = 'timeout'
    else pythonOk.value = python != null ? python.status === 'connected' : false

    const sysd       = sys.status      === 'fulfilled' ? sys.value       : null
    const modelData2 = modelData.status === 'fulfilled' ? modelData.value : null
    const resData    = res.status      === 'fulfilled' ? res.value       : null

    if (sysd) {
      sysInfo.value      = sysd
      ollamaOk.value     = sysd.ollama_available === true
      cloudMode.value    = !!sysd.cloud_mode
      cloudBaseUrl.value = sysd.cloud_base_url || ''
      cloudModel.value   = sysd.cloud_model || modelData2?.cloud_model || ''
    }

    if (modelData2?.available_models) {
      models.value = [...new Set(modelData2.available_models)]
      // 优先用 /api/models 返回的 per-user current_model（与 ChatView 保持一致）
      currentModel.value = modelData2.current_model || sysd?.agent_model || ''
    } else if (sysd?.agent_model) {
      currentModel.value = sysd.agent_model
    }

    if (resData) {
      resources.value    = resData
      processes.value    = resData.processes            || {}
      topOthers.value    = resData.top_other_processes  || []
      disks.value        = resData.disks                || []
      ollamaModels.value = resData.ollama_models        || []
      push('cpu',    resData.cpu_percent)
      push('memory', resData.memory_percent)
      push('gpu',    resData.gpu?.util_percent ?? null)
    }

    lastUpdated.value = new Date().toLocaleTimeString('zh-CN')

    // 同步刷新资源配置用量
    loadRuntimeConfig()
  } finally {
    loading.value = false
  }
}

// ── 云端服务商管理 ────────────────────────────────────────
const cloudProviders = ref([])
const cpPresets      = ref([])
const showCpDialog   = ref(false)
const cpEditId       = ref(null)
const cpSaving       = ref(false)
const cpForm         = ref({ name: '', provider: '', base_url: '', api_key: '', model: '' })

const CP_MODEL_SUGGESTIONS = {
  openai:      ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo'],
  deepseek:    ['deepseek-chat', 'deepseek-reasoner'],
  dashscope:   ['qwen-plus', 'qwen-turbo', 'qwen-max', 'qwen-long'],
  zhipu:       ['glm-4', 'glm-4-flash', 'glm-4-air'],
  moonshot:    ['moonshot-v1-8k', 'moonshot-v1-32k', 'moonshot-v1-128k'],
  baidu:       ['ernie-4.5-turbo-128k', 'ernie-4.0-8k', 'ernie-speed-128k'],
  siliconflow: ['deepseek-ai/DeepSeek-V3', 'Qwen/Qwen2.5-72B-Instruct'],
}
const cpModelSuggestions = computed(() =>
  CP_MODEL_SUGGESTIONS[cpForm.value.provider] || []
)

const loadCloudProvs = async () => {
  const data = await listCloudProviders()
  if (data) cloudProviders.value = data.providers || []
}

const loadCpPresets = async () => {
  const data = await getCloudPresets()
  if (data) cpPresets.value = data.presets || []
}

const openCpAdd = () => {
  cpEditId.value = null
  cpForm.value   = { name: '', provider: '', base_url: '', api_key: '', model: '' }
  showCpDialog.value = true
}

const openCpEdit = (p) => {
  cpEditId.value = p.id
  cpForm.value   = { name: p.name, provider: p.provider, base_url: p.base_url, api_key: '', model: p.model }
  showCpDialog.value = true
}

const onCpProviderChange = () => {
  const preset = cpPresets.value.find(p => p.key === cpForm.value.provider)
  if (preset) cpForm.value.base_url = preset.url
}

const saveCpForm = async () => {
  if (!cpForm.value.name.trim())  { ElMessage.warning('请填写名称'); return }
  if (!cpForm.value.model.trim()) { ElMessage.warning('请填写模型名称'); return }
  if (!cpEditId.value && !cpForm.value.api_key.trim()) { ElMessage.warning('请填写 API Key'); return }
  cpSaving.value = true
  try {
    const payload = { ...cpForm.value }
    let res
    if (cpEditId.value) {
      res = await updateCloudProvider(cpEditId.value, payload)
    } else {
      res = await createCloudProvider(payload)
    }
    if (res?.success) {
      ElMessage.success(cpEditId.value ? '已更新服务商' : '已添加服务商')
      showCpDialog.value = false
      await loadCloudProvs()
    }
  } finally {
    cpSaving.value = false
  }
}

const activateCp = async (pid) => {
  const res = await activateCloudProvider(pid)
  if (res?.success) {
    ElMessage.success('云端服务商已激活，后续对话将使用该服务商')
    await loadCloudProvs()
    await refresh()
  }
}

const deactivateCp = async () => {
  const ok = await confirmDialog.confirm('停用云端服务商，切换回本地 Ollama？', {
    title: '停用云端服务商',
    confirmText: '确认停用',
    type: 'warning',
  })
  if (!ok) return
  const res = await deactivateCloudProviders()
  if (res?.success) {
    ElMessage.success('已切换回本地 Ollama')
    await loadCloudProvs()
    await refresh()
  }
}

const deleteCp = async (pid, name) => {
  const ok = await confirmDialog.confirm(`确定删除服务商「${name}」？`, {
    title: '删除服务商',
    confirmText: '删除',
    type: 'danger',
  })
  if (!ok) return
  const res = await deleteCloudProvider(pid)
  if (res?.success) {
    ElMessage.success('已删除')
    await loadCloudProvs()
  }
}

let timer = null, clockTimer = null
onMounted(() => {
  refresh()
  loadRuntimeConfig()
  loadCloudProvs()
  loadCpPresets()
  timer      = setInterval(refresh, 10000)
  clockTimer = setInterval(() => { countdown.value = Math.max(0, countdown.value - 1) }, 1000)
})
onUnmounted(() => { clearInterval(timer); clearInterval(clockTimer) })
</script>

<style scoped>
.cloud-model-item { border-color: #c5caf5; background: linear-gradient(135deg, #f0f2ff, #fff); }
.cloud-badge { background: #667eea; }
.model-local-badge {
  font-size: 0.72rem; background: #f5f5f5; color: #999;
  padding: 1px 7px; border-radius: 8px;
}
.system-view {
  padding: 20px; height: 100%; overflow-y: auto;
  display: flex; flex-direction: column; gap: 14px; background: #f8f9fa;
}
.toolbar { display: flex; align-items: center; justify-content: space-between; }
.toolbar-right { display: flex; align-items: center; gap: 10px; }
.countdown-badge { font-size: 0.78rem; color: #aaa; background: #f0f0f0; padding: 4px 10px; border-radius: 10px; }
.refresh-btn {
  display: flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 8px;
  border: 1px solid #e0e3e8; background: white; font-size: 0.88rem; color: #555; cursor: pointer;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.card-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.status-card {
  background: white; border-radius: 12px; border: 0.5px solid #e8eaed;
  padding: 16px; display: flex; flex-direction: column; gap: 8px;
}
.cloud-card { border-color: #c5caf5; background: linear-gradient(135deg, #fff 80%, #f0f2ff); }
.card-label { font-size: 0.82rem; color: #888; font-weight: 500; }
.card-sub   { font-size: 0.78rem; color: #aaa; }
.cloud-url  { font-size: 0.72rem; color: #bbb; word-break: break-all; }
.status-badge {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 0.88rem; font-weight: 500; padding: 4px 10px; border-radius: 20px; width: fit-content;
}
.status-badge.ok       { background: #e8f5e9; color: #2e7d32; }
.status-badge.err      { background: #fce4e4; color: #c62828; }
.status-badge.checking { background: #f5f5f5; color: #999; }
.status-badge.timeout  { background: #fff3e0; color: #e65100; }
.retry-btn {
  margin-left: 8px;
  padding: 2px 8px;
  font-size: 0.75rem;
  border: 1px solid currentColor;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.8;
}
.retry-btn:hover { opacity: 1; }

.chart-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.chart-card { background: white; border-radius: 12px; border: 0.5px solid #e8eaed; padding: 14px; }
.chart-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.chart-title  { font-size: 0.82rem; color: #888; display: flex; align-items: center; gap: 5px; }
.chart-title i { color: #667eea; }
.chart-val    { font-size: 1.2rem; font-weight: 500; }
.chart-detail { display: flex; justify-content: space-between; font-size: 0.75rem; color: #aaa; margin-top: 4px; }
.val-ok { color: #43a047; } .val-warn { color: #f57c00; } .val-danger { color: #e53935; }

.core-grid { display: flex; gap: 2px; align-items: flex-end; height: 24px; margin-top: 6px; }
.core-bar-wrap { flex: 1; height: 100%; display: flex; align-items: flex-end; background: #f5f5f5; border-radius: 2px; overflow: hidden; }
.core-bar { width: 100%; border-radius: 2px; transition: height 0.4s ease; min-height: 2px; }
.core-bar.val-ok { background: #43a047; } .core-bar.val-warn { background: #f57c00; } .core-bar.val-danger { background: #e53935; }

.mini-progress-wrap { height: 4px; background: #f0f0f0; border-radius: 2px; overflow: hidden; margin-top: 6px; }
.mini-progress-bar  { height: 100%; border-radius: 2px; transition: width 0.4s; }
.gpu-bar  { background: #667eea; }
.gpu-name  { font-size: 0.72rem; color: #bbb; margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gpu-empty { font-size: 0.78rem; color: #bbb; margin-top: 8px; text-align: center; }
.gpu-empty-sub { font-size: 0.72rem; color: #ccc; margin-top: 3px; }

.detail-row  { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.detail-card { background: white; border-radius: 12px; border: 0.5px solid #e8eaed; padding: 18px; }
.model-card  { grid-column: 1 / -1; }
.detail-title {
  font-size: 0.95rem; font-weight: 500; color: #333;
  margin-bottom: 14px; display: flex; align-items: center; gap: 8px;
}
.detail-title i { color: #667eea; }
.empty-tip  { color: #aaa; font-size: 0.88rem; padding: 10px 0; }
.divider    { border: none; border-top: 0.5px solid #f0f0f0; margin: 14px 0; }

/* ── 云端模型信息 ── */
.cloud-info-box { margin-bottom: 4px; }
.cloud-info-item {
  display: flex; align-items: center; gap: 12px;
  padding: 6px 0; border-bottom: 0.5px solid #f5f5f5; font-size: 0.88rem;
}
.cloud-info-item:last-child { border-bottom: none; }
.ci-label { color: #aaa; min-width: 64px; flex-shrink: 0; font-size: 0.82rem; }
.ci-val   { color: #333; font-weight: 500; word-break: break-all; }
.model-name-highlight { color: #667eea; font-size: 0.95rem; }

/* ── 进程内存 ── */
.process-list { display: flex; flex-direction: column; gap: 10px; }
.process-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.process-name { font-size: 0.85rem; color: #555; display: flex; align-items: center; gap: 6px; }
.process-name i { color: #aaa; font-size: 0.8rem; width: 14px; text-align: center; }
.process-mem  { font-size: 0.82rem; font-weight: 500; color: #333; }
.process-bar-wrap { height: 5px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.process-bar { height: 100%; border-radius: 3px; transition: width 0.5s ease; }
.bar-ok { background: #43a047; } .bar-warn { background: #f57c00; } .bar-danger { background: #e53935; }
.bar-other { background: #9ca3af; }
.process-other { opacity: 0.85; }
.process-other .process-name { color: #888; }
.other-tip { font-size: 0.72rem; color: #bbb; margin-left: 4px; font-weight: 400; }

.expand-btn {
  font-size: 0.72rem; padding: 2px 8px; border-radius: 6px;
  border: 1px solid #d1d5db; background: white; color: #667eea;
  cursor: pointer; white-space: nowrap;
}
.expand-btn:hover { background: #f0f2ff; }

.other-detail {
  margin-top: 6px; padding: 8px 10px;
  background: #fafafa; border-radius: 8px;
  display: flex; flex-direction: column; gap: 5px;
}
.other-row {
  display: grid; grid-template-columns: 140px 1fr 70px;
  align-items: center; gap: 8px; font-size: 0.78rem;
}
.other-name { color: #666; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.other-bar-wrap { height: 4px; background: #e5e7eb; border-radius: 2px; overflow: hidden; }
.other-bar { display: block; height: 100%; background: #9ca3af; border-radius: 2px; min-width: 2px; }
.other-mem { color: #888; text-align: right; font-variant-numeric: tabular-nums; }
.other-row-hint { opacity: 0.5; }
.process-total { display: flex; justify-content: space-between; padding-top: 8px; border-top: 0.5px solid #f0f0f0; font-size: 0.85rem; color: #666; }
.process-total.mem-breakdown-hint { border-top: none; padding-top: 2px; color: #aaa; font-size: 0.8rem; }
.total-val { font-weight: 500; color: #333; }

/* ── Ollama 模型 ── */
.ollama-model-list { display: flex; flex-direction: column; gap: 10px; margin-bottom: 4px; }
.ollama-model-item { padding: 10px 12px; background: #f8f9fa; border-radius: 8px; }
.ollama-model-row  { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.ollama-model-name { font-size: 0.88rem; font-weight: 500; color: #333; }
.ollama-model-size { font-size: 0.82rem; color: #888; }
.ollama-model-meta { display: flex; gap: 8px; }
.vram-badge    { font-size: 0.75rem; padding: 2px 8px; background: #e3f2fd; color: #1976d2; border-radius: 8px; }
.expires-badge { font-size: 0.75rem; padding: 2px 8px; background: #fff3e0; color: #f57c00; border-radius: 8px; }

/* ── 磁盘 ── */
.disk-list { display: flex; flex-direction: column; gap: 12px; }
.disk-header { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.disk-mount  { font-size: 0.88rem; font-weight: 500; color: #333; min-width: 36px; }
.disk-usage  { font-size: 0.82rem; color: #888; flex: 1; }
.disk-pct    { font-size: 0.82rem; font-weight: 500; }
.disk-bar-wrap { height: 5px; background: #f0f0f0; border-radius: 3px; overflow: hidden; margin-bottom: 3px; }
.disk-bar    { height: 100%; border-radius: 3px; transition: width 0.5s; }
.disk-free   { font-size: 0.75rem; color: #bbb; }
.disk-warn   { background: #fffde7; border-radius: 8px; padding: 6px; margin: -6px; }
.disk-alert-badge {
  font-size: 0.72rem; padding: 2px 8px; border-radius: 8px; font-weight: 500;
}
.disk-alert-badge.warn   { background: #fff3e0; color: #f57c00; }
.disk-alert-badge.danger { background: #fce4e4; color: #c62828; }

/* ── 模型列表 ── */
.model-list { display: flex; flex-wrap: wrap; gap: 8px; }
.model-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-radius: 8px; border: 1px solid #f0f0f0; font-size: 0.88rem; color: #555;
}
.model-item.active { border-color: #c5caf5; background: #f0f2ff; color: #667eea; }
.model-item i      { color: #aaa; font-size: 0.8rem; }
.model-item.active i { color: #667eea; }
.model-badge { font-size: 0.75rem; background: #667eea; color: white; padding: 2px 8px; border-radius: 10px; }

.sys-info-row {
  display: flex; justify-content: space-between; font-size: 0.75rem; color: #bbb;
  margin-top: 14px; padding-top: 10px; border-top: 0.5px solid #f0f0f0;
}

/* ── 内存优化建议 ── */
.mem-tips-card { grid-column: 1 / -1; }
.tips-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.tip-group { background: #fafafa; border-radius: 10px; padding: 14px 16px; border: 0.5px solid #f0f0f0; }
.tip-group-title { font-size: 0.82rem; font-weight: 600; color: #444; margin-bottom: 10px; display: flex; align-items: center; gap: 6px; }
.tip-item { display: flex; gap: 8px; align-items: flex-start; font-size: 0.82rem; color: #555; margin-bottom: 8px; line-height: 1.5; }
.tip-item:last-child { margin-bottom: 0; }
.tip-item code { background: #f0f0f0; padding: 1px 5px; border-radius: 4px; font-size: 0.78rem; color: #333; word-break: break-all; }
.tip-tag { flex-shrink: 0; font-size: 0.7rem; padding: 2px 7px; border-radius: 8px; font-weight: 600; margin-top: 1px; }
.tip-high .tip-tag, .tip-tag.high { background: #fce4e4; color: #c62828; }
.tip-tag.mid  { background: #e8f5e9; color: #2e7d32; }
.tip-tag.low  { background: #f5f5f5; color: #888; }
@media (max-width: 1100px) { .tips-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 700px)  { .tips-grid { grid-template-columns: 1fr; } }

/* ── 资源配置面板 ── */
.resource-config-card { grid-column: 1 / -1; }

.detail-title .rc-tip {
  font-size: 0.75rem; color: #aaa; font-weight: 400; margin-left: 8px;
}
.rc-save-btn {
  margin-left: auto; padding: 6px 16px; background: #667eea; color: white;
  border: none; border-radius: 8px; font-size: 0.85rem; cursor: pointer;
  display: flex; align-items: center; gap: 6px; transition: opacity 0.2s;
}
.rc-save-btn:hover:not(:disabled) { opacity: 0.85; }
.rc-save-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.rc-save-btn.saving { background: #9ca3af; }

.rc-body { display: grid; grid-template-columns: 280px 1fr; gap: 24px; align-items: start; }

/* 用量列 */
.rc-usage-col { display: flex; flex-direction: column; gap: 10px; }
.rc-section-label {
  font-size: 0.8rem; font-weight: 600; color: #667eea; text-transform: uppercase;
  letter-spacing: 0.04em; margin-bottom: 4px; display: flex; align-items: center; gap: 6px;
}
.rc-usage-grid { display: flex; flex-direction: column; gap: 10px; }
.rc-usage-item { display: flex; flex-direction: column; gap: 3px; }
.ru-label { font-size: 0.78rem; color: #888; }
.ru-bar-wrap { height: 5px; background: #f0f0f0; border-radius: 3px; overflow: hidden; }
.ru-bar { height: 100%; background: #667eea; border-radius: 3px; transition: width 0.4s; min-width: 2px; }
.ru-bar-queue { background: #f59e0b; }
.ru-bar-cache { background: #10b981; }
.ru-bar-mem   { background: #8b5cf6; }
.ru-val { font-size: 0.75rem; color: #aaa; text-align: right; }

/* 配置列 */
.rc-form-col { display: flex; flex-direction: column; gap: 12px; }
.rc-form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }

.rc-group {
  background: #fafafa; border-radius: 10px; padding: 12px 14px;
  border: 0.5px solid #f0f0f0;
}
.rc-group-title { font-size: 0.78rem; font-weight: 600; color: #555; margin-bottom: 10px; }
.rc-field { margin-bottom: 10px; }
.rc-field:last-child { margin-bottom: 0; }
.rc-field label { font-size: 0.78rem; color: #777; display: block; margin-bottom: 5px; }
.rc-range { color: #bbb; font-weight: 400; }
.rc-hint-text { font-size: 0.72rem; color: #999; margin-bottom: 4px; line-height: 1.4; }
.rc-slider-row { display: flex; align-items: center; gap: 8px; }
.rc-slider-row input[type="range"] {
  flex: 1; height: 4px; accent-color: #667eea;
  cursor: pointer;
}
.rc-num {
  width: 72px; padding: 3px 7px; border: 1px solid #e0e3e8; border-radius: 6px;
  font-size: 0.82rem; text-align: right; color: #333; outline: none;
}
.rc-num:focus { border-color: #667eea; }

@media (max-width: 1200px) {
  .rc-form-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 900px) {
  .rc-body { grid-template-columns: 1fr; }
  .rc-form-grid { grid-template-columns: 1fr; }
}

@media (max-width: 768px) {
  .card-row  { grid-template-columns: repeat(2, 1fr); }
  .chart-row { grid-template-columns: repeat(2, 1fr); }
  .detail-row { grid-template-columns: 1fr; }
}

/* ── 云端服务商配置 ─────────────────────────────────────── */
.cloud-providers-card { grid-column: 1 / -1; }

.cp-add-btn {
  margin-left: auto; padding: 6px 14px;
  background: #667eea; color: white; border: none;
  border-radius: 8px; font-size: 0.82rem; cursor: pointer;
  display: flex; align-items: center; gap: 5px; transition: opacity 0.2s;
}
.cp-add-btn:hover { opacity: 0.85; }

.cp-empty {
  text-align: center; padding: 28px 0; color: #bbb; font-size: 0.88rem;
}

.cp-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.cp-card {
  background: #fafafa; border: 1px solid #e8eaed; border-radius: 12px;
  padding: 14px 16px; display: flex; flex-direction: column; gap: 8px;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.cp-card:hover { border-color: #c5caf5; box-shadow: 0 2px 12px rgba(102,126,234,0.08); }
.cp-card.cp-active {
  border-color: #667eea;
  background: linear-gradient(135deg, #f8f9ff 80%, #f0f2ff);
}

.cp-card-header {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
}
.cp-name {
  display: flex; align-items: center; gap: 7px;
  font-weight: 500; font-size: 0.92rem; color: #333;
}
.cp-active-badge {
  font-size: 0.72rem; background: #e8f5e9; color: #2e7d32;
  padding: 2px 9px; border-radius: 10px; display: flex; align-items: center; gap: 4px;
  flex-shrink: 0;
}

.cp-meta { display: flex; align-items: center; gap: 8px; }
.cp-provider-tag {
  font-size: 0.72rem; background: #f0f2ff; color: #667eea;
  padding: 2px 8px; border-radius: 8px; text-transform: lowercase;
}
.cp-model-name { font-size: 0.82rem; color: #555; font-weight: 500; }

.cp-url {
  font-size: 0.72rem; color: #bbb; word-break: break-all;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.cp-key { font-size: 0.75rem; color: #aaa; display: flex; align-items: center; gap: 5px; }
.cp-key i { font-size: 0.65rem; }

.cp-actions { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 2px; }
.cp-btn {
  padding: 5px 11px; border-radius: 7px; font-size: 0.78rem;
  cursor: pointer; display: flex; align-items: center; gap: 4px;
  transition: all 0.15s; border: 1px solid transparent;
}
.cp-btn-activate  { background: #667eea; color: white; border-color: #667eea; }
.cp-btn-activate:hover { background: #5a6fd6; }
.cp-btn-deactivate { background: white; color: #888; border-color: #e0e3e8; }
.cp-btn-deactivate:hover { border-color: #f57c00; color: #f57c00; }
.cp-btn-edit { background: white; color: #555; border-color: #e0e3e8; }
.cp-btn-edit:hover { border-color: #667eea; color: #667eea; }
.cp-btn-del  { background: white; color: #999; border-color: #e0e3e8; }
.cp-btn-del:hover { border-color: #e53935; color: #e53935; }

/* ── 服务商对话框 ──────────────────────────────────────── */
.modal-overlay {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex; align-items: center; justify-content: center;
  z-index: 200;
}
.modal-box {
  background: white; border-radius: 14px;
  width: 460px; max-height: 85vh; overflow-y: auto;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.modal-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 20px 14px; border-bottom: 0.5px solid #e8eaed;
}
.modal-title { font-size: 1rem; font-weight: 500; }
.modal-close { background: none; border: none; color: #aaa; cursor: pointer; font-size: 1rem; padding: 4px; }
.modal-close:hover { color: #333; }
.modal-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 14px; }
.form-row { display: flex; flex-direction: column; gap: 5px; }
.form-row label { font-size: 0.85rem; color: #555; font-weight: 500; display: flex; align-items: center; flex-wrap: wrap; }
.form-row input, .form-row select {
  padding: 8px 12px; border: 1px solid #e0e3e8;
  border-radius: 8px; font-size: 0.88rem; outline: none; transition: border-color 0.2s;
}
.form-row input:focus, .form-row select:focus { border-color: #667eea; }
.required { color: #f44336; margin-left: 2px; }
.modal-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 14px 20px 18px; border-top: 0.5px solid #e8eaed;
}
.btn-cancel {
  padding: 8px 18px; border-radius: 8px; border: 1px solid #e0e3e8;
  background: white; color: #555; cursor: pointer; font-size: 0.88rem;
}
.btn-confirm {
  padding: 8px 18px; border-radius: 8px; border: none;
  background: #667eea; color: white; cursor: pointer; font-size: 0.88rem;
  display: flex; align-items: center; gap: 6px; transition: background 0.2s;
}
.btn-confirm:hover:not(:disabled) { background: #5a6fd6; }
.btn-confirm:disabled { opacity: 0.5; cursor: not-allowed; }

.cp-model-chips { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.cp-chips-label { font-size: 0.75rem; color: #aaa; flex-shrink: 0; }
.cp-chip {
  font-size: 0.75rem; padding: 3px 9px; border-radius: 10px;
  background: #f0f2ff; color: #667eea; cursor: pointer;
  border: 1px solid #e0e3ef; transition: all 0.15s;
}
.cp-chip:hover { background: #667eea; color: white; border-color: #667eea; }
</style>