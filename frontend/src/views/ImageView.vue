<template>
  <div class="image-view">

    <!-- 顶部状态栏 -->
    <div class="status-bar">
      <div class="provider-badge" :class="providerClass">
        <i :class="providerIcon" />
        <span>{{ statusLabel }}</span>
      </div>
      <div class="model-selector" v-if="models.length > 1">
        <select v-model="selectedModel" class="model-select" @change="doSwitchModel" :disabled="switching">
          <option v-for="m in models" :key="m.name" :value="m.name">{{ m.name }}</option>
        </select>
        <span v-if="switching" class="switch-tip"><i class="fas fa-circle-notch fa-spin" /> 切换中…</span>
      </div>
      <span v-else-if="statusLoaded" class="current-model">{{ currentModel || '服务当前模型' }}</span>
    </div>

    <!-- ComfyUI 离线时的启动引导横幅 -->
    <div v-if="providerName === 'comfyui' && !providerOk && statusLoaded" class="comfyui-hint">
      <i class="fas fa-info-circle" />
      <span>
        ComfyUI 未就绪，请在本地启动：
        <code>python main.py --listen 0.0.0.0 --port 8188</code>
      </span>
    </div>

    <!-- 主体：左侧参数面板 + 右侧结果区 -->
    <div class="main-layout">

      <!-- 参数面板 -->
      <div class="param-panel">
        <div class="param-section">
          <label class="param-label">正向提示词 <span class="en-tip">（英文描述效果更好）</span></label>
          <textarea
            v-model="form.prompt"
            class="prompt-input"
            rows="4"
            placeholder="a beautiful landscape, sunset, photorealistic, 8k..."
            @keydown.ctrl.enter="doGenerate"
          />
        </div>

        <div class="param-section">
          <label class="param-label">负向提示词 <span class="en-tip">（不想出现的内容）</span></label>
          <textarea
            v-model="form.negativePrompt"
            class="prompt-input neg"
            rows="2"
            placeholder="ugly, blurry, low quality, watermark..."
          />
        </div>

        <!-- 风格预设 -->
        <div class="param-section">
          <label class="param-label">风格预设</label>
          <div class="style-presets">
            <button
              v-for="s in STYLE_PRESETS"
              :key="s.value"
              class="style-btn"
              :class="{ active: form.style === s.value }"
              @click="form.style = form.style === s.value ? '' : s.value"
            >{{ s.label }}</button>
          </div>
        </div>

        <!-- 尺寸 -->
        <div class="param-section param-row">
          <div class="param-item">
            <label class="param-label">尺寸</label>
            <div class="size-btns">
              <button
                v-for="sz in SIZE_OPTIONS"
                :key="sz"
                class="size-btn"
                :class="{ active: form.size === sz }"
                @click="form.size = sz"
              >{{ sz }}</button>
            </div>
          </div>
          <div class="param-item">
            <label class="param-label">步数 <span class="param-val">{{ form.steps }}</span></label>
            <input type="range" v-model.number="form.steps" min="10" max="50" step="1" class="steps-slider" />
            <div class="steps-hint">
              <span>快 (10)</span><span>精 (50)</span>
            </div>
          </div>
        </div>

        <!-- CFG Scale -->
        <div class="param-section">
          <label class="param-label">CFG Scale <span class="param-val">{{ form.cfg }}</span>
            <span class="en-tip">（提示词引导强度，7~10 推荐）</span>
          </label>
          <input type="range" v-model.number="form.cfg" min="1" max="20" step="0.5" class="steps-slider" />
        </div>

        <!-- 采样器（SD WebUI / ComfyUI 均支持，选项因 provider 不同） -->
        <div class="param-section" v-if="providerName === 'sd_webui' || providerName === 'comfyui'">
          <label class="param-label">
            采样器
            <span class="en-tip">{{ providerName === 'comfyui' ? '（ComfyUI 原生名称）' : '（SD WebUI）' }}</span>
          </label>
          <select v-model="form.sampler" class="sampler-select">
            <option v-for="s in SAMPLER_OPTIONS" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>

        <!-- img2img -->
        <div class="param-section">
          <label class="param-label">
            <span>图生图（img2img）</span>
            <span class="en-tip">可选，上传底图后以此为基础生成</span>
          </label>
          <div v-if="form.initImagePreview" class="img2img-preview-wrap">
            <img :src="form.initImagePreview" class="img2img-preview" />
            <button class="img2img-clear" @click="clearImg2img" title="移除底图">
              <i class="fas fa-times" />
            </button>
            <div class="param-section denoising-row">
              <label class="param-label">去噪强度 <span class="param-val">{{ form.denoisingStrength }}</span></label>
              <input type="range" v-model.number="form.denoisingStrength" min="0.1" max="1" step="0.05" class="steps-slider" />
            </div>
          </div>
          <label v-else class="img2img-upload-btn">
            <i class="fas fa-upload" /> 上传底图
            <input type="file" accept="image/*" style="display:none" @change="onImg2imgFile" />
          </label>
        </div>

        <!-- 生成按钮 -->
        <button
          class="gen-btn"
          :class="{ loading: generating }"
          :disabled="generating || !providerOk"
          @click="doGenerate"
        >
          <i v-if="generating" class="fas fa-circle-notch fa-spin" />
          <i v-else class="fas fa-magic" />
          {{ generating ? '生成中…' : '生 成（Ctrl+Enter）' }}
        </button>

        <div v-if="!providerOk && statusLoaded" class="provider-tip">
          <i class="fas fa-exclamation-triangle" />
          {{ unavailableMsg }}
        </div>
      </div>

      <!-- 结果区 -->
      <div class="result-area">
        <!-- 当前生成结果 -->
        <div v-if="lastResult" class="result-card">
          <img :src="lastResult.url" class="result-img" :alt="lastResult.prompt" />
          <div class="result-meta">
            <span class="result-provider">{{ lastResult.provider }}/{{ lastResult.model }}</span>
            <span class="result-size">{{ lastResult.size }}</span>
            <a :href="lastResult.url" :download="lastResult.filename" class="dl-btn" title="下载">
              <i class="fas fa-download" />
            </a>
          </div>
          <div class="result-prompt">{{ lastResult.prompt }}</div>
        </div>

        <!-- 进度占位 -->
        <div v-else-if="generating" class="gen-placeholder">
          <div class="gen-spinner">
            <i class="fas fa-palette fa-3x" />
          </div>
          <p>正在生成图片，请稍候…</p>
          <p class="gen-hint">本地模型首次生成可能需要 30 秒以上</p>
          <!-- 进度条（SD WebUI 实时进度） -->
          <div v-if="progressPct > 0" class="progress-wrap">
            <div class="progress-bar" :style="{ width: progressPct + '%' }" />
            <span class="progress-text">{{ progressPct }}%
              <span v-if="progressEta > 0"> · {{ Math.ceil(progressEta) }}s</span>
            </span>
          </div>
        </div>

        <div v-else-if="!lastResult" class="gen-placeholder empty">
          <i class="fas fa-image fa-3x" />
          <p>填写提示词后点击「生成」</p>
        </div>

        <!-- 历史 Gallery -->
        <div class="gallery-section">
          <div class="gallery-header">
            <span class="gallery-title">历史生成 ({{ gallery.length }})</span>
            <button class="refresh-btn" :class="{ spinning: galleryLoading }" @click="loadGallery">
              <i class="fas fa-sync-alt" />
            </button>
          </div>
          <div v-if="galleryLoading" class="gallery-loading">
            <i class="fas fa-circle-notch fa-spin" /> 加载中…
          </div>
          <div v-else-if="!gallery.length" class="gallery-empty">暂无生成记录</div>
          <div v-else class="gallery-grid">
            <div
              v-for="img in gallery"
              :key="img.filename"
              class="gallery-item"
              @click="previewImg = img"
            >
              <img :src="img.url" :alt="img.filename" class="gallery-thumb" />
              <div class="gallery-overlay">
                <a :href="img.url" :download="img.filename" @click.stop class="gal-dl">
                  <i class="fas fa-download" />
                </a>
                <button class="gal-del" @click.stop="deleteGalleryImg(img)" title="删除">
                  <i class="fas fa-trash" />
                </button>
              </div>
              <div class="gallery-time">{{ formatDate(img.created_at) }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 图片预览弹窗 -->
    <div v-if="previewImg" class="preview-mask" @click="previewImg = null">
      <div class="preview-box" @click.stop>
        <img :src="previewImg.url" class="preview-full" />
        <div class="preview-actions">
          <a :href="previewImg.url" :download="previewImg.filename" class="preview-dl">
            <i class="fas fa-download" /> 下载
          </a>
          <button class="preview-close" @click="previewImg = null">
            <i class="fas fa-times" /> 关闭
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getImageProviderStatus, listImageModels, switchImageModel,
  generateImage, listGeneratedImages, deleteGeneratedImage,
  getImageProgress,
} from '@/services/api'

// ── 常量 ─────────────────────────────────────────────────────────────────────

const SIZE_OPTIONS = ['512x512', '768x512', '512x768', '768x768', '1024x1024']

// SD WebUI 采样器（显示友好名称）
const SAMPLER_OPTIONS_SD = [
  'DPM++ 2M Karras', 'DPM++ SDE Karras', 'DPM++ 2S a Karras',
  'Euler a', 'Euler', 'Heun', 'DDIM', 'UniPC',
]

// ComfyUI 采样器（原生名称，后端自动映射 SD 名称兼容）
const SAMPLER_OPTIONS_COMFY = [
  'euler', 'euler_ancestral', 'dpmpp_2m', 'dpmpp_sde',
  'dpmpp_2s_ancestral', 'dpmpp_3m_sde', 'heun', 'ddim', 'uni_pc', 'lcm',
]

const STYLE_PRESETS = [
  { label: '写实',       value: 'photorealistic, hyperdetailed' },
  { label: '油画',       value: 'oil painting, artistic, impasto' },
  { label: '动漫',       value: 'anime style, cel shading, vibrant' },
  { label: '水彩',       value: 'watercolor painting, soft colors' },
  { label: '像素',       value: 'pixel art, 8bit, retro game style' },
  { label: '素描',       value: 'pencil sketch, black and white, detailed lines' },
]

// ── 状态 ─────────────────────────────────────────────────────────────────────

const form = ref({
  prompt:            '',
  negativePrompt:    'ugly, blurry, low quality, watermark, text',
  style:             '',
  size:              '512x512',
  steps:             20,
  cfg:               7,
  sampler:           'euler',          // ComfyUI 默认；切换到 sd_webui 时会重置
  initImagePreview:  null,             // Data URL for display
  initImageB64:      null,             // pure base64 for API
  denoisingStrength: 0.75,
})

const providerOk    = ref(false)
const providerName  = ref('')
const currentModel  = ref('')
const statusLoaded  = ref(false)
const unavailableMsg = ref('')
const models        = ref([])
const selectedModel = ref('')
const switching     = ref(false)
const generating     = ref(false)
const lastResult     = ref(null)
const gallery        = ref([])
const galleryLoading = ref(false)
const previewImg     = ref(null)
const progressPct    = ref(0)
const progressEta    = ref(0)
let   _progressTimer = null

// ── 计算 ─────────────────────────────────────────────────────────────────────

// 动态采样器列表：根据 provider 切换
const SAMPLER_OPTIONS = computed(() =>
  providerName.value === 'sd_webui' ? SAMPLER_OPTIONS_SD : SAMPLER_OPTIONS_COMFY
)

// 切换 provider 时重置采样器默认值
watch(providerName, (name) => {
  if (name === 'sd_webui') form.value.sampler = 'DPM++ 2M Karras'
  else form.value.sampler = 'euler'
})

const providerClass = computed(() => ({
  'badge-ok':      providerOk.value,
  'badge-offline': !providerOk.value && statusLoaded.value,
  'badge-loading': !statusLoaded.value,
}))

const providerIcon = computed(() => {
  if (!statusLoaded.value)    return 'fas fa-circle-notch fa-spin'
  if (!providerOk.value)      return 'fas fa-exclamation-circle'
  const icons = { sd_webui: 'fas fa-paint-brush', comfyui: 'fas fa-project-diagram', diffusers: 'fab fa-python' }
  return icons[providerName.value] || 'fas fa-image'
})

const statusLabel = computed(() => {
  if (!statusLoaded.value) return '检测中…'
  if (!providerOk.value)   return `${providerName.value || '图片服务'} 离线`
  return `${providerName.value} 就绪`
})

// ── 初始化 ────────────────────────────────────────────────────────────────────

onMounted(async () => {
  await Promise.all([loadStatus(), loadGallery()])
})

onUnmounted(() => stopProgressPoll())

const loadStatus = async () => {
  try {
    const res = await getImageProviderStatus()
    providerOk.value   = !!res?.available
    providerName.value = res?.provider   || ''
    currentModel.value = res?.model      || ''
    unavailableMsg.value = res?.message  || ''
    statusLoaded.value = true

    if (providerOk.value) await loadModels()
  } catch {
    statusLoaded.value = true
    unavailableMsg.value = '无法连接到后端服务'
  }
}

const loadModels = async () => {
  try {
    const res = await listImageModels()
    models.value       = res?.models || []
    selectedModel.value = res?.current || ''
  } catch { /* 静默失败 */ }
}

// ── 操作 ─────────────────────────────────────────────────────────────────────

const doSwitchModel = async () => {
  if (!selectedModel.value || switching.value) return
  switching.value = true
  try {
    const res = await switchImageModel(selectedModel.value)
    if (res?.success) {
      currentModel.value = selectedModel.value
      ElMessage({ message: res.message || '模型切换成功', type: 'success', duration: 2000 })
    } else {
      ElMessage({ message: res?.message || '切换失败', type: 'error', duration: 2500 })
    }
  } finally {
    switching.value = false
  }
}

// ── 进度轮询 ──────────────────────────────────────────────────────────────────

const startProgressPoll = () => {
  // SD WebUI 和 ComfyUI 均支持进度查询
  if (!['sd_webui', 'comfyui'].includes(providerName.value)) return
  progressPct.value = 0
  progressEta.value = 0
  const interval = providerName.value === 'comfyui' ? 1500 : 1000
  _progressTimer = setInterval(async () => {
    const data = await getImageProgress().catch(() => null)
    if (!data) return
    progressPct.value = Math.round((data.progress || 0) * 100)
    progressEta.value = data.eta || 0
  }, interval)
}

const stopProgressPoll = () => {
  if (_progressTimer) { clearInterval(_progressTimer); _progressTimer = null }
  progressPct.value = 0
}

// ── img2img ───────────────────────────────────────────────────────────────────

const onImg2imgFile = (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (ev) => {
    const dataUrl = ev.target.result
    form.value.initImagePreview = dataUrl
    // strip "data:image/xxx;base64," prefix
    form.value.initImageB64 = dataUrl.split(',')[1] || null
  }
  reader.readAsDataURL(file)
}

const clearImg2img = () => {
  form.value.initImagePreview = null
  form.value.initImageB64     = null
}

// ── 生成 ─────────────────────────────────────────────────────────────────────

const doGenerate = async () => {
  if (generating.value) return
  const prompt = form.value.prompt.trim()
  if (!prompt) {
    ElMessage({ message: '请输入提示词', type: 'warning', duration: 1500 })
    return
  }
  if (!providerOk.value) {
    ElMessage({ message: '图片生成服务未就绪', type: 'error', duration: 2000 })
    return
  }

  generating.value = true
  lastResult.value  = null
  startProgressPoll()
  try {
    const res = await generateImage({
      prompt:             prompt,
      negative_prompt:    form.value.negativePrompt,
      style:              form.value.style || undefined,
      size:               form.value.size,
      steps:              form.value.steps,
      cfg:                form.value.cfg,
      sampler_name:       form.value.sampler,
      init_image_base64:  form.value.initImageB64 || undefined,
      denoising_strength: form.value.denoisingStrength,
    })
    if (res?.success) {
      lastResult.value = res
      ElMessage({ message: '图片生成成功', type: 'success', duration: 1500 })
      await loadGallery()
    } else {
      ElMessage({ message: res?.message || '生成失败', type: 'error', duration: 3000 })
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 2000 })
  } finally {
    stopProgressPoll()
    generating.value = false
  }
}

const loadGallery = async () => {
  galleryLoading.value = true
  try {
    const res = await listGeneratedImages()
    gallery.value = res?.images || []
  } finally {
    galleryLoading.value = false
  }
}

const deleteGalleryImg = async (img) => {
  const { useConfirmDialogStore } = await import('@/stores/confirmDialog')
  const confirmDialog = useConfirmDialogStore()
  const ok = await confirmDialog.confirm(`确认删除图片 ${img.filename}？`, {
    title: '删除图片', confirmText: '删除', danger: true,
  })
  if (!ok) return
  try {
    await deleteGeneratedImage(img.filename)
    gallery.value = gallery.value.filter(x => x.filename !== img.filename)
    if (lastResult.value?.filename === img.filename) lastResult.value = null
  } catch {
    ElMessage({ message: '删除失败', type: 'error', duration: 1500 })
  }
}

const formatDate = (iso) => {
  try {
    const d = new Date(iso)
    const diff = Date.now() - d.getTime()
    if (diff < 60000)   return '刚刚'
    if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
    return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
  } catch { return iso }
}
</script>

<style scoped>
.image-view {
  display: flex; flex-direction: column; height: 100%; gap: 0;
  background: var(--bg-main, #f5f7fa);
}

/* ── 状态栏 ────────────────────────────────────────────────── */
.status-bar {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 20px;
  background: white; border-bottom: 1px solid #e0e3e8;
  flex-shrink: 0;
}
.provider-badge {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 10px; border-radius: 20px; font-size: 0.82rem; font-weight: 500;
}
.badge-ok      { background: #e6f9f0; color: #2e7d52; }
.badge-offline { background: #fde8e8; color: #c0392b; }
.badge-loading { background: #f0f4ff; color: #5c6bc0; }
.model-select {
  border: 1px solid #e0e3e8; border-radius: 6px; padding: 4px 8px;
  font-size: 0.82rem; background: white; cursor: pointer;
}
.switch-tip { font-size: 0.78rem; color: #90a4ae; }
.current-model { font-size: 0.82rem; color: #78909c; }

/* ── ComfyUI 提示横幅 ─────────────────────────────────────── */
.comfyui-hint {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 20px; background: #fffbeb; border-bottom: 1px solid #fde68a;
  font-size: 0.82rem; color: #92400e; flex-shrink: 0;
}
.comfyui-hint code {
  background: #fef3c7; padding: 1px 6px; border-radius: 4px;
  font-family: monospace; font-size: 0.8rem;
}

/* ── 主体布局 ──────────────────────────────────────────────── */
.main-layout {
  display: grid; grid-template-columns: 320px 1fr; gap: 0;
  flex: 1; overflow: hidden;
}

/* ── 参数面板 ──────────────────────────────────────────────── */
.param-panel {
  background: white; border-right: 1px solid #e0e3e8;
  padding: 16px; display: flex; flex-direction: column; gap: 14px;
  overflow-y: auto;
}
.param-section { display: flex; flex-direction: column; gap: 5px; }
.param-label { font-size: 0.82rem; font-weight: 600; color: #555; }
.en-tip { font-size: 0.73rem; color: #aaa; font-weight: 400; }
.param-val { color: #1976d2; font-weight: 700; margin-left: 4px; }
.prompt-input {
  border: 1px solid #e0e3e8; border-radius: 8px; padding: 8px 10px;
  font-size: 0.85rem; resize: vertical; outline: none;
  font-family: inherit; transition: border-color 0.2s; line-height: 1.5;
}
.prompt-input:focus { border-color: #1976d2; }
.prompt-input.neg { font-size: 0.8rem; color: #e53935; }

.style-presets { display: flex; flex-wrap: wrap; gap: 6px; }
.style-btn {
  padding: 4px 10px; border-radius: 14px; border: 1px solid #e0e3e8;
  font-size: 0.78rem; background: white; cursor: pointer; transition: all 0.15s;
}
.style-btn:hover { border-color: #1976d2; color: #1976d2; }
.style-btn.active { background: #1976d2; color: white; border-color: #1976d2; }

.param-row { flex-direction: row; gap: 16px; }
.param-item { flex: 1; display: flex; flex-direction: column; gap: 5px; }

.size-btns { display: flex; flex-wrap: wrap; gap: 4px; }
.size-btn {
  padding: 3px 8px; border-radius: 5px; border: 1px solid #e0e3e8;
  font-size: 0.74rem; background: white; cursor: pointer;
  transition: all 0.15s; white-space: nowrap;
}
.size-btn:hover { border-color: #1976d2; }
.size-btn.active { background: #e8f0fd; border-color: #1976d2; color: #1976d2; font-weight: 600; }

.steps-slider { width: 100%; accent-color: #1976d2; cursor: pointer; }
.steps-hint { display: flex; justify-content: space-between; font-size: 0.7rem; color: #bbb; }

.gen-btn {
  padding: 12px; border-radius: 10px; border: none;
  background: linear-gradient(135deg, #1976d2, #6c3af7);
  color: white; font-size: 0.95rem; font-weight: 600;
  cursor: pointer; transition: opacity 0.2s, transform 0.15s;
  display: flex; align-items: center; justify-content: center; gap: 8px;
}
.gen-btn:hover:not(:disabled) { opacity: 0.9; transform: translateY(-1px); }
.gen-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.gen-btn.loading { background: #90a4ae; }

.provider-tip {
  background: #fff3e0; border: 1px solid #ffe0b2; border-radius: 8px;
  padding: 8px 12px; font-size: 0.8rem; color: #e65100;
  display: flex; align-items: flex-start; gap: 6px; line-height: 1.5;
}

/* ── 结果区 ────────────────────────────────────────────────── */
.result-area {
  display: flex; flex-direction: column; gap: 16px;
  padding: 16px; overflow-y: auto;
}

.result-card {
  background: white; border-radius: 12px; border: 1px solid #e0e3e8;
  overflow: hidden;
}
.result-img { width: 100%; display: block; max-height: 480px; object-fit: contain; background: #000; }
.result-meta {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  font-size: 0.78rem; color: #90a4ae; border-top: 1px solid #f0f0f0;
}
.result-provider { font-weight: 500; color: #1976d2; }
.result-size { margin-right: auto; }
.dl-btn {
  padding: 4px 10px; border-radius: 6px; background: #e8f0fd;
  color: #1976d2; text-decoration: none; font-size: 0.82rem;
}
.result-prompt {
  padding: 6px 12px 10px; font-size: 0.78rem; color: #777; font-style: italic;
}

.gen-placeholder {
  background: white; border-radius: 12px; border: 1px solid #e0e3e8;
  padding: 60px 20px; text-align: center; color: #aaa;
}
.gen-spinner { animation: pulse 1.5s ease-in-out infinite; color: #1976d2; margin-bottom: 16px; }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.4} }
.gen-hint { font-size: 0.78rem; color: #ccc; margin-top: 6px; }
.gen-placeholder.empty i { color: #d0d7de; }

/* 进度条 */
.progress-wrap {
  width: 100%; max-width: 320px; margin: 16px auto 0;
  background: #e8eaf0; border-radius: 6px; height: 10px; position: relative;
}
.progress-bar {
  height: 100%; border-radius: 6px; transition: width 0.4s ease;
  background: linear-gradient(90deg, #1976d2, #6c3af7);
}
.progress-text {
  position: absolute; top: 14px; left: 50%; transform: translateX(-50%);
  font-size: 0.75rem; color: #78909c; white-space: nowrap;
}

/* 采样器 */
.sampler-select {
  border: 1px solid #e0e3e8; border-radius: 8px; padding: 6px 8px;
  font-size: 0.82rem; background: white; cursor: pointer; width: 100%;
}

/* img2img */
.img2img-upload-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 14px; border-radius: 7px; border: 1px dashed #c0cfe8;
  color: #5c6bc0; font-size: 0.82rem; cursor: pointer;
  transition: all 0.2s;
}
.img2img-upload-btn:hover { border-color: #1976d2; color: #1976d2; background: #f0f4ff; }
.img2img-preview-wrap { position: relative; margin-top: 4px; }
.img2img-preview { width: 100%; border-radius: 8px; border: 1px solid #e0e3e8; display: block; }
.img2img-clear {
  position: absolute; top: 6px; right: 6px;
  background: rgba(0,0,0,0.5); border: none; color: white;
  border-radius: 50%; width: 24px; height: 24px;
  cursor: pointer; font-size: 0.75rem;
  display: flex; align-items: center; justify-content: center;
}
.denoising-row { margin-top: 8px; }

/* 暗色补充 */
[data-theme="dark"] .sampler-select { background: #0d1117; border-color: #2d3451; color: #c9d1d9; }

/* ── Gallery ───────────────────────────────────────────────── */
.gallery-section { flex: 1; }
.gallery-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.gallery-title { font-size: 0.9rem; font-weight: 600; color: #555; }
.refresh-btn {
  background: none; border: 1px solid #e0e3e8; border-radius: 6px;
  color: #90a4ae; cursor: pointer; padding: 4px 8px; font-size: 0.8rem;
  transition: all 0.2s;
}
.refresh-btn:hover { color: #1976d2; border-color: #1976d2; }
.refresh-btn.spinning i { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.gallery-loading, .gallery-empty { text-align: center; padding: 20px; color: #bbb; font-size: 0.85rem; }
.gallery-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}
.gallery-item {
  position: relative; border-radius: 8px; overflow: hidden;
  cursor: pointer; aspect-ratio: 1;
  border: 1px solid #e0e3e8; background: #f0f0f0;
}
.gallery-thumb { width: 100%; height: 100%; object-fit: cover; display: block; }
.gallery-overlay {
  position: absolute; inset: 0; background: rgba(0,0,0,0.45);
  display: flex; align-items: center; justify-content: center; gap: 10px;
  opacity: 0; transition: opacity 0.2s;
}
.gallery-item:hover .gallery-overlay { opacity: 1; }
.gal-dl, .gal-del {
  width: 32px; height: 32px; border-radius: 50%;
  background: rgba(255,255,255,0.2); display: flex; align-items: center;
  justify-content: center; text-decoration: none; color: white;
  border: none; cursor: pointer; font-size: 0.8rem;
  transition: background 0.2s;
}
.gal-dl:hover  { background: #1976d2; }
.gal-del:hover { background: #e53935; }
.gallery-time {
  position: absolute; bottom: 0; left: 0; right: 0;
  background: linear-gradient(transparent, rgba(0,0,0,0.6));
  color: white; font-size: 0.68rem; padding: 4px 6px; text-align: center;
}

/* ── 预览弹窗 ──────────────────────────────────────────────── */
.preview-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,0.8);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.preview-box {
  max-width: 90vw; max-height: 90vh; background: #1a1a2e; border-radius: 12px;
  overflow: hidden; display: flex; flex-direction: column;
}
.preview-full { max-width: 100%; max-height: 80vh; object-fit: contain; display: block; }
.preview-actions {
  display: flex; justify-content: center; gap: 12px; padding: 12px;
}
.preview-dl {
  padding: 7px 18px; border-radius: 7px; background: #1976d2; color: white;
  text-decoration: none; font-size: 0.85rem;
}
.preview-close {
  padding: 7px 18px; border-radius: 7px; background: #555; color: white;
  border: none; cursor: pointer; font-size: 0.85rem;
}

/* ── 暗色主题 ──────────────────────────────────────────────── */
[data-theme="dark"] .image-view { background: #0d1117; }
[data-theme="dark"] .status-bar,
[data-theme="dark"] .param-panel,
[data-theme="dark"] .result-card,
[data-theme="dark"] .gen-placeholder { background: #161b22; border-color: #2d3451; }
[data-theme="dark"] .prompt-input,
[data-theme="dark"] .model-select { background: #0d1117; border-color: #2d3451; color: #c9d1d9; }
[data-theme="dark"] .style-btn,
[data-theme="dark"] .size-btn { background: #161b22; border-color: #2d3451; color: #c9d1d9; }
[data-theme="dark"] .param-label { color: #c9d1d9; }
[data-theme="dark"] .result-meta { border-color: #2d3451; }
[data-theme="dark"] .gallery-item { background: #1a1f2e; border-color: #2d3451; }
[data-theme="dark"] .gallery-title { color: #c9d1d9; }

@media (max-width: 768px) {
  .main-layout { grid-template-columns: 1fr; }
  .param-panel { border-right: none; border-bottom: 1px solid #e0e3e8; }
}
</style>
