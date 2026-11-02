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

    <!-- 免费模型下载引导（HuggingFace / ModelScope 直链） -->
    <details class="model-guide" v-if="providerName === 'comfyui'">
      <summary><i class="fas fa-download" /> 免费模型下载引导</summary>
      <div class="model-guide-body">
        <p>把模型文件放入 ComfyUI 对应目录后重启 ComfyUI，即可在模型列表中选择。</p>
        <table class="guide-table">
          <thead>
            <tr><th>模型</th><th>类型</th><th>放入目录</th><th>HuggingFace</th><th>ModelScope</th></tr>
          </thead>
          <tbody>
            <tr v-for="m in MODEL_GUIDE" :key="m.name">
              <td>{{ m.name }}</td>
              <td>{{ m.kind }}</td>
              <td><code>{{ m.dir }}</code></td>
              <td><a v-if="m.hf" :href="m.hf" target="_blank" rel="noopener">下载</a></td>
              <td><a v-if="m.ms" :href="m.ms" target="_blank" rel="noopener">下载</a></td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>

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

        <!-- 高级选项（ComfyUI：LoRA + 自定义工作流） -->
        <div class="param-section" v-if="providerName === 'comfyui'">
          <label class="param-label">
            ControlNet <span class="en-tip">（SD1.5/SDXL，需下载对应 control 模型）</span>
          </label>
          <select v-model="form.controlnet" class="sampler-select">
            <option value="">不使用</option>
            <option v-for="c in controlNets" :key="c" :value="c">{{ c }}</option>
          </select>
          <div v-if="form.controlnet" class="controlnet-row">
            <label class="param-label">强度 <span class="param-val">{{ form.controlnetStrength }}</span></label>
            <input type="range" v-model.number="form.controlnetStrength" min="0.1" max="2" step="0.05" class="steps-slider" />
            <div v-if="form.controlImagePreview" class="img2img-preview-wrap">
              <img :src="form.controlImagePreview" class="img2img-preview" />
              <button class="img2img-clear" @click="clearControlImage" title="移除参考图">
                <i class="fas fa-times" />
              </button>
            </div>
            <label v-else class="img2img-upload-btn">
              <i class="fas fa-upload" /> 上传参考图
              <input type="file" accept="image/*" style="display:none" @change="onControlImageFile" />
            </label>
          </div>
        </div>

        <!-- 局部重绘（inpaint） -->
        <div class="param-section" v-if="providerName === 'comfyui' && form.initImagePreview">
          <label class="param-label">
            局部重绘蒙版 <span class="en-tip">（可选，白色区域重绘）</span>
          </label>
          <div v-if="form.maskImagePreview" class="img2img-preview-wrap">
            <img :src="form.maskImagePreview" class="img2img-preview" />
            <button class="img2img-clear" @click="clearMaskImage" title="移除蒙版">
              <i class="fas fa-times" />
            </button>
          </div>
          <label v-else class="img2img-upload-btn">
            <i class="fas fa-upload" /> 上传蒙版
            <input type="file" accept="image/*" style="display:none" @change="onMaskImageFile" />
          </label>
        </div>

        <div class="param-section" v-if="providerName === 'comfyui'">
          <label class="param-label">
            LoRA <span class="en-tip">（逗号分隔：文件名:强度，如 detail.safetensors:0.8）</span>
          </label>
          <input
            v-model="form.loras"
            class="prompt-input lora-input"
            placeholder="lora1.safetensors:0.8, lora2.safetensors"
          />
        </div>

        <div class="param-section" v-if="providerName === 'comfyui'">
          <label class="param-label">
            自定义工作流
            <span v-if="usingCustomWorkflow" class="workflow-badge">使用中</span>
            <span class="en-tip">（节点图 JSON，支持占位符）</span>
          </label>
          <textarea
            v-model="workflowJson"
            class="prompt-input workflow-input"
            rows="5"
            placeholder='{"1":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"checkpoint.safetensors"}}}'
          />
          <div class="workflow-actions">
            <button class="style-btn" :disabled="workflowSaving" @click="doSaveWorkflow">
              <i class="fas fa-save" /> 保存
            </button>
            <button class="style-btn" :disabled="workflowSaving" @click="doResetWorkflow">
              <i class="fas fa-undo" /> 恢复默认
            </button>
          </div>
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
          <!-- ComfyUI 实时预览（/ws 预览帧回传） -->
          <img
            v-if="previewBase64"
            :src="'data:image/png;base64,' + previewBase64"
            class="live-preview"
            alt="生成中预览"
          />
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
              <button class="refresh-btn" :class="{ spinning: galleryLoading }" aria-label="刷新图库" title="刷新图库" @click="loadGallery">
                <i class="fas fa-sync-alt" />
              </button>
          </div>
            <SkeletonList v-if="galleryLoading" :rows="4" variant="cards" />
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
  getComfyuiWorkflow, saveComfyuiWorkflow, resetComfyuiWorkflow,
  listImageControlNets,
} from '@/services/api'
import SkeletonList from '@/components/common/SkeletonList.vue'

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

// 免费模型一键下载引导（HuggingFace / ModelScope 直链）
const MODEL_GUIDE = [
  {
    name: 'FLUX.1 schnell', kind: '基础模型', dir: 'models/unet/',
    hf: 'https://huggingface.co/black-forest-labs/FLUX.1-schnell',
    ms: 'https://modelscope.cn/models/AI-ModelScope/FLUX.1-schnell',
  },
  {
    name: 'Qwen-Image', kind: '基础模型', dir: 'models/unet/',
    hf: 'https://huggingface.co/Qwen/Qwen-Image',
    ms: 'https://modelscope.cn/models/Qwen/Qwen-Image',
  },
  {
    name: 'HiDream-I1', kind: '基础模型', dir: 'models/diffusion_models/',
    hf: 'https://huggingface.co/black-forest-labs?q=HiDream',
    ms: 'https://modelscope.cn/models/HiDream/HiDream-I1',
  },
  {
    name: 'SDXL base 1.0', kind: '基础模型', dir: 'models/checkpoints/',
    hf: 'https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0',
    ms: 'https://modelscope.cn/models/AI-ModelScope/stable-diffusion-xl-base-1.0',
  },
  {
    name: 'SD 1.5', kind: '基础模型', dir: 'models/checkpoints/',
    hf: 'https://huggingface.co/runwayml/stable-diffusion-v1-5',
    ms: 'https://modelscope.cn/models/AI-ModelScope/stable-diffusion-v1-5',
  },
  {
    name: 'ControlNet Canny/SD1.5', kind: 'ControlNet', dir: 'models/controlnet/',
    hf: 'https://huggingface.co/lllyasviel/ControlNet-v1-1',
    ms: 'https://modelscope.cn/models/licyks/ControlNet-v1-1',
  },
  {
    name: 'ControlNet Canny/SDXL', kind: 'ControlNet', dir: 'models/controlnet/',
    hf: 'https://huggingface.co/diffusers/controlnet-canny-sdxl-1.0',
    ms: '',
  },
  {
    name: 't5xxl / ae (FLUX 配套)', kind: 'CLIP/VAE', dir: 'models/clip/ · models/vae/',
    hf: 'https://huggingface.co/comfyanonymous/flux_text_encoders',
    ms: '',
  },
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
  loras:             '',               // ComfyUI LoRA："name:强度, name2" 逗号分隔
  controlnet:        '',
  controlnetStrength: 1.0,
  controlImagePreview: null,
  controlImageB64:    null,
  maskImagePreview:   null,
  maskImageB64:       null,
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
const previewBase64  = ref(null)
const controlNets    = ref([])
let   _progressTimer = null
const workflowJson      = ref('')
const usingCustomWorkflow = ref(false)
const workflowSaving    = ref(false)

// ── 计算 ─────────────────────────────────────────────────────────────────────

// 动态采样器列表：根据 provider 切换
const SAMPLER_OPTIONS = computed(() =>
  providerName.value === 'sd_webui' ? SAMPLER_OPTIONS_SD : SAMPLER_OPTIONS_COMFY
)

// 切换 provider 时重置采样器默认值
watch(providerName, async (name) => {
  if (name === 'sd_webui') {
    form.value.sampler = 'DPM++ 2M Karras'
  } else {
    form.value.sampler = 'euler'
  }
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
  await Promise.all([loadStatus(), loadGallery(), loadWorkflowState()])
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
    if (providerOk.value && providerName.value === 'comfyui') await loadControlNets()
  } catch {
    statusLoaded.value = true
    unavailableMsg.value = '无法连接到后端服务'
  }
}

const loadControlNets = async () => {
  try {
    const res = await listImageControlNets()
    controlNets.value = res?.controlnets || []
  } catch { /* 非 ComfyUI 或后端未就绪时静默 */ }
}

const loadModels = async () => {
  try {
    const res = await listImageModels()
    models.value       = res?.models || []
    selectedModel.value = res?.current || ''
  } catch { /* 静默失败 */ }
}

const loadWorkflowState = async () => {
  try {
    const res = await getComfyuiWorkflow()
    usingCustomWorkflow.value = !!res?.using_custom
    workflowJson.value = res?.workflow
      ? JSON.stringify(res.workflow, null, 2)
      : ''
  } catch { /* 非 ComfyUI 或后端未就绪时静默 */ }
}

const doSaveWorkflow = async () => {
  if (workflowSaving.value) return
  let graph
  try {
    graph = JSON.parse(workflowJson.value)
  } catch {
    ElMessage({ message: '工作流 JSON 格式错误', type: 'error', duration: 2500 })
    return
  }
  if (!graph || typeof graph !== 'object' || Array.isArray(graph)) {
    ElMessage({ message: '工作流必须是 JSON 对象（节点图）', type: 'error', duration: 2500 })
    return
  }
  workflowSaving.value = true
  try {
    const res = await saveComfyuiWorkflow(graph)
    if (res?.success) {
      usingCustomWorkflow.value = true
      ElMessage({ message: res.message || '工作流已保存', type: 'success', duration: 2000 })
    } else {
      ElMessage({ message: res?.message || '保存失败', type: 'error', duration: 2500 })
    }
  } finally {
    workflowSaving.value = false
  }
}

const doResetWorkflow = async () => {
  if (workflowSaving.value) return
  workflowSaving.value = true
  try {
    const res = await resetComfyuiWorkflow()
    if (res?.success) {
      usingCustomWorkflow.value = false
      workflowJson.value = ''
      ElMessage({ message: res.message || '已恢复内置模板', type: 'success', duration: 2000 })
    } else {
      ElMessage({ message: res?.message || '恢复失败', type: 'error', duration: 2500 })
    }
  } finally {
    workflowSaving.value = false
  }
}

const parseLoras = (raw) => {
  if (!raw || !raw.trim()) return undefined
  return raw.split(',').map((part) => {
    const [name, modelStrength, clipStrength] = part.trim().split(':')
    if (!name) return null
    const lora = { name: name.trim() }
    if (modelStrength) lora.strength_model = Number(modelStrength) || 1.0
    if (clipStrength) lora.strength_clip = Number(clipStrength) || lora.strength_model || 1.0
    return lora
  }).filter(Boolean)
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
  // SD WebUI、ComfyUI、diffusers 均支持进度查询
  if (!['sd_webui', 'comfyui', 'diffusers'].includes(providerName.value)) return
  progressPct.value = 0
  progressEta.value = 0
  previewBase64.value = null
  const interval = providerName.value === 'comfyui' ? 1500 : 1000
  _progressTimer = setInterval(async () => {
    const data = await getImageProgress().catch(() => null)
    if (!data) return
    progressPct.value = Math.round((data.progress || 0) * 100)
    progressEta.value = data.eta || 0
    if (data.preview_base64) previewBase64.value = data.preview_base64
  }, interval)
}

const stopProgressPoll = () => {
  if (_progressTimer) { clearInterval(_progressTimer); _progressTimer = null }
  progressPct.value = 0
  previewBase64.value = null
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

// ── ControlNet / 蒙版 ─────────────────────────────────────────────────────────

const readImageFile = (file, apply) => {
  if (!file) return
  const reader = new FileReader()
  reader.onload = (ev) => apply(ev.target.result)
  reader.readAsDataURL(file)
}

const onControlImageFile = (e) => readImageFile(e.target.files?.[0], (dataUrl) => {
  form.value.controlImagePreview = dataUrl
  form.value.controlImageB64     = dataUrl.split(',')[1] || null
})

const clearControlImage = () => {
  form.value.controlImagePreview = null
  form.value.controlImageB64     = null
}

const onMaskImageFile = (e) => readImageFile(e.target.files?.[0], (dataUrl) => {
  form.value.maskImagePreview = dataUrl
  form.value.maskImageB64     = dataUrl.split(',')[1] || null
})

const clearMaskImage = () => {
  form.value.maskImagePreview = null
  form.value.maskImageB64     = null
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
      model:              selectedModel.value || undefined,
      loras:              parseLoras(form.value.loras),
      sampler_name:       form.value.sampler,
      init_image_base64:  form.value.initImageB64 || undefined,
      denoising_strength: form.value.denoisingStrength,
      controlnet_name:    form.value.controlnet || undefined,
      controlnet_strength: form.value.controlnetStrength,
      control_image_base64: form.value.controlImageB64 || undefined,
      mask_image_base64:  form.value.maskImageB64 || undefined,
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
  background: var(--bg-main, var(--color-surface-sunken));
}

/* ── 状态栏 ────────────────────────────────────────────────── */
.status-bar {
  display: flex; align-items: center; gap: var(--space-3);
  padding: var(--space-2-5) var(--space-4-5);
  background: var(--color-surface); border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.provider-badge {
  display: inline-flex; align-items: center; gap: var(--space-1-5);
  padding: var(--space-1) var(--space-2-5); border-radius: var(--radius-lg); font-size: var(--text-sm-2); font-weight: var(--weight-medium);
}
.badge-ok      { background: var(--color-success-soft); color: var(--color-success); }
.badge-offline { background: var(--color-danger-soft); color: var(--color-danger); }
.badge-loading { background: var(--color-surface-raised); color: var(--color-primary); }
.model-select {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: var(--space-1) var(--space-2);
  font-size: var(--text-sm-2); background: var(--color-surface); color: var(--color-text); cursor: pointer;
}
.switch-tip { font-size: var(--text-xs); color: var(--color-text-muted); }
.current-model { font-size: var(--text-sm-2); color: var(--color-text-muted); }

/* ── ComfyUI 提示横幅 ─────────────────────────────────────── */
.comfyui-hint {
  display: flex; align-items: center; gap: var(--space-2-5);
  padding: var(--space-2) var(--space-4-5); background: var(--color-warn-soft); border-bottom: 1px solid var(--color-warn);
  font-size: var(--text-sm-2); color: var(--color-warn); flex-shrink: 0;
}
.comfyui-hint code {
  background: var(--color-surface-sunken); padding: var(--space-px) var(--space-1-5); border-radius: var(--radius-xs);
  color: var(--color-text);
  font-family: monospace; font-size: var(--text-sm-2);
}

/* ── 主体布局 ──────────────────────────────────────────────── */
.main-layout {
  display: grid; grid-template-columns: 320px 1fr; gap: 0;
  flex: 1; overflow: hidden;
}

/* ── 参数面板 ──────────────────────────────────────────────── */
.param-panel {
  background: var(--color-surface); border-right: 1px solid var(--color-border);
  padding: var(--space-4); display: flex; flex-direction: column; gap: var(--space-3-5);
  overflow-y: auto;
}
.param-section { display: flex; flex-direction: column; gap: var(--space-1); }
.param-label { font-size: var(--text-sm-2); font-weight: var(--weight-semibold); color: var(--color-text-secondary); }
.en-tip { font-size: var(--text-xs); color: var(--color-text-muted); font-weight: var(--weight-normal); }
.param-val { color: var(--color-primary); font-weight: var(--weight-bold); margin-left: var(--space-1); }
.prompt-input {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-2-5);
  font-size: var(--text-sm); color: var(--color-text); resize: vertical; outline: none;
  font-family: inherit; transition: border-color var(--duration-base); line-height: var(--leading-normal);
}
.prompt-input:focus { border-color: var(--color-primary); }
.prompt-input.neg { font-size: var(--text-sm-2); color: var(--color-danger); }
.lora-input { font-size: var(--text-xs); }
.workflow-input {
  font-family: monospace; font-size: var(--text-xs);
  background: var(--color-surface);
}
.workflow-actions { display: flex; gap: var(--space-2); margin-top: var(--space-2); }
.workflow-badge {
  font-size: var(--text-2xs); color: var(--color-accent); background: var(--color-success-soft);
  padding: var(--space-px) var(--space-2); border-radius: var(--radius-sm); margin-left: var(--space-1-5);
}

.style-presets { display: flex; flex-wrap: wrap; gap: var(--space-1-5); }
.style-btn {
  padding: var(--space-1) var(--space-2-5); border-radius: var(--radius-md); border: 1px solid var(--color-border);
  font-size: var(--text-xs); background: var(--color-surface); color: var(--color-text); cursor: pointer; transition: var(--transition-interactive);
}
.style-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.style-btn.active { background: var(--color-primary); color: var(--color-text-on-primary); border-color: var(--color-primary); }

.param-row { flex-direction: row; gap: var(--space-4); }
.param-item { flex: 1; display: flex; flex-direction: column; gap: var(--space-1); }

.size-btns { display: flex; flex-wrap: wrap; gap: var(--space-1); }
.size-btn {
  padding: var(--space-1) var(--space-2); border-radius: var(--radius-xs); border: 1px solid var(--color-border);
  font-size: var(--text-xs); background: var(--color-surface); color: var(--color-text); cursor: pointer;
  transition: var(--transition-interactive); white-space: nowrap;
}
.size-btn:hover { border-color: var(--color-primary); }
.size-btn.active { background: var(--color-primary-soft); border-color: var(--color-primary); color: var(--color-primary); font-weight: var(--weight-semibold); }

.steps-slider { width: 100%; accent-color: var(--color-primary); cursor: pointer; }
.steps-hint { display: flex; justify-content: space-between; font-size: var(--text-2xs); color: var(--color-text-muted); }

.gen-btn {
  padding: var(--space-3); border-radius: var(--radius-md); border: none;
  background: var(--color-primary);
  color: var(--color-text-on-primary); font-size: var(--text-base); font-weight: var(--weight-semibold);
  cursor: pointer; transition: opacity var(--duration-base), transform var(--duration-fast);
  display: flex; align-items: center; justify-content: center; gap: var(--space-2);
  position: sticky; bottom: 0; z-index: 1;
  box-shadow: var(--shadow-raised);
}
.gen-btn:hover:not(:disabled) { opacity: 0.9; transform: translateY(-1px); }
.gen-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.gen-btn.loading { background: var(--color-text-muted); }

.provider-tip {
  background: var(--color-warn-soft); border: 1px solid var(--color-warn-soft); border-radius: var(--radius-sm);
  padding: var(--space-2) var(--space-3); font-size: var(--text-sm-2); color: var(--color-warn);
  display: flex; align-items: flex-start; gap: var(--space-1-5); line-height: var(--leading-normal);
}

/* ── 结果区 ────────────────────────────────────────────────── */
.result-area {
  display: flex; flex-direction: column; gap: var(--space-4);
  padding: var(--space-4); overflow-y: auto;
}

.result-card {
  background: var(--color-surface); border-radius: var(--radius-md); border: 1px solid var(--color-border);
  overflow: hidden;

  box-shadow: var(--shadow-card);}
.result-img { width: 100%; display: block; max-height: 480px; object-fit: contain; background: var(--surface-media); }
.result-meta {
  display: flex; align-items: center; gap: var(--space-2); padding: var(--space-2) var(--space-3);
  font-size: var(--text-xs); color: var(--color-text-muted); border-top: 1px solid var(--color-border);
}
.result-provider { font-weight: var(--weight-medium); color: var(--color-primary); }
.result-size { margin-right: auto; }
.dl-btn {
  padding: var(--space-1) var(--space-2-5); border-radius: var(--radius-sm); background: var(--color-primary-soft);
  color: var(--color-primary); text-decoration: none; font-size: var(--text-sm-2);
}
.result-prompt {
  padding: var(--space-1-5) var(--space-3) var(--space-2-5); font-size: var(--text-xs); color: var(--color-text-secondary); font-style: italic;
}

.gen-placeholder {
  background: var(--color-surface); border-radius: var(--radius-md); border: 1px solid var(--color-border);
  padding: 60px var(--space-4-5); text-align: center; color: var(--color-text-muted);
}
.gen-spinner { animation: pulse 1.5s ease-in-out infinite; color: var(--color-primary); margin-bottom: var(--space-4); }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.4} }
.gen-hint { font-size: var(--text-xs); color: var(--color-text-muted); margin-top: var(--space-1-5); }
.gen-placeholder.empty i { color: var(--color-text-secondary); }

/* 进度条 */
.progress-wrap {
  width: 100%; max-width: 320px; margin: var(--space-4) auto 0;
  background: var(--color-border); border-radius: var(--radius-sm); height: 10px; position: relative;
}
.progress-bar {
  height: 100%; border-radius: var(--radius-sm); transition: width var(--duration-slow) var(--ease-standard);
  background: var(--color-primary);
}
.progress-text {
  position: absolute; top: 14px; left: 50%; transform: translateX(-50%);
  font-size: var(--text-xs); color: var(--color-text-muted); white-space: nowrap;
}

/* 采样器 */
.sampler-select {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: var(--space-1-5) var(--space-2);
  font-size: var(--text-sm-2); background: var(--color-surface); color: var(--color-text); cursor: pointer; width: 100%;
}

/* img2img */
.img2img-upload-btn {
  display: inline-flex; align-items: center; gap: var(--space-1-5);
  padding: var(--space-1-5) var(--space-3-5); border-radius: var(--radius-sm); border: 1px dashed var(--color-primary-border);
  color: var(--color-primary); font-size: var(--text-sm-2); cursor: pointer;
  transition: var(--transition-interactive);
}
.img2img-upload-btn:hover { border-color: var(--color-primary); color: var(--color-primary); background: var(--color-primary-soft); }
.img2img-preview-wrap { position: relative; margin-top: var(--space-1); }
.img2img-preview { width: 100%; border-radius: var(--radius-sm); border: 1px solid var(--color-border); display: block; }
.img2img-clear {
  position: absolute; top: 6px; right: 6px;
  background: var(--color-scrim); border: none; color: white;
  border-radius: 50%; width: 24px; height: 24px;
  cursor: pointer; font-size: var(--text-xs);
  display: flex; align-items: center; justify-content: center;
}
.denoising-row { margin-top: var(--space-2); }

/* ── Gallery ───────────────────────────────────────────────── */
.gallery-section { flex: 1; }
.gallery-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-2-5); }
.gallery-title { font-size: var(--text-sm); font-weight: var(--weight-semibold); color: var(--color-text-secondary); }
.refresh-btn {
  background: none; border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  color: var(--color-text-muted); cursor: pointer; padding: var(--space-1) var(--space-2); font-size: var(--text-sm-2);
  transition: var(--transition-interactive);
}
.refresh-btn:hover { color: var(--color-primary); border-color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.gallery-loading, .gallery-empty { text-align: center; padding: var(--space-4-5); color: var(--color-text-muted); font-size: var(--text-sm); }
.gallery-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-2-5);
}
.gallery-item {
  position: relative; border-radius: var(--radius-sm); overflow: hidden;
  cursor: pointer; aspect-ratio: 1;
  border: 1px solid var(--color-border); background: var(--color-border);
}
.gallery-thumb { width: 100%; height: 100%; object-fit: cover; display: block; }
.gallery-overlay {
  position: absolute; inset: 0; background: var(--color-scrim);
  display: flex; align-items: center; justify-content: center; gap: var(--space-2-5);
  opacity: 0; transition: opacity var(--duration-base);
}
.gallery-item:hover .gallery-overlay { opacity: 1; }
.gal-dl, .gal-del {
  width: 32px; height: 32px; border-radius: 50%;
  background: var(--color-surface-on-dark-strong); display: flex; align-items: center;
  justify-content: center; text-decoration: none; color: white;
  border: none; cursor: pointer; font-size: var(--text-sm-2);
  transition: background var(--duration-base);
}
.gal-dl:hover  { background: var(--color-primary); }
.gal-del:hover { background: var(--color-danger); }
.gallery-time {
  position: absolute; bottom: 0; left: 0; right: 0;
  background: linear-gradient(transparent, var(--color-scrim));
  color: white; font-size: var(--text-2xs); padding: var(--space-1) var(--space-1-5); text-align: center;
}

/* ── 预览弹窗 ──────────────────────────────────────────────── */
.preview-mask {
  position: fixed; inset: 0; background: var(--color-scrim-strong);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.preview-box {
  max-width: 90vw; max-height: 90vh; background: var(--surface-media); border-radius: var(--radius-md);
  overflow: hidden; display: flex; flex-direction: column;
}
.preview-full { max-width: 100%; max-height: 80vh; object-fit: contain; display: block; }
.preview-actions {
  display: flex; justify-content: center; gap: var(--space-3); padding: var(--space-3);
}
.preview-dl {
  padding: var(--space-1-5) var(--space-4); border-radius: var(--radius-sm); background: var(--color-primary); color: var(--color-text-on-primary);
  text-decoration: none; font-size: var(--text-sm);
}
.preview-close {
  padding: var(--space-1-5) var(--space-4); border-radius: var(--radius-sm); background: var(--color-text); color: white;
  border: none; cursor: pointer; font-size: var(--text-sm);
}
</style>
