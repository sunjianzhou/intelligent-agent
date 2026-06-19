<template>
  <div class="memory-view">
    <!-- 统计卡片 -->
    <div class="stats-row">
      <div class="stat-card accent-primary">
        <div class="stat-num">{{ stats.long_term?.count ?? '-' }}</div>
        <div class="stat-label">长期记忆</div>
      </div>
      <div class="stat-card accent-success">
        <div class="stat-num">{{ stats.short_term?.count ?? '-' }}</div>
        <div class="stat-label">短期记忆</div>
      </div>
      <div class="stat-card accent-warn">
        <div class="stat-num">{{ avgImportance }}</div>
        <div class="stat-label">平均重要性</div>
      </div>
    </div>

    <!-- 搜索栏 + 类型切换 -->
    <div class="toolbar toolbar-row1">
      <div class="search-wrap">
        <i class="fas fa-search search-icon" />
        <input
          v-model="searchQuery"
          class="search-input"
          placeholder="搜索记忆内容..."
          @keydown.enter="doSearch"
          @input="debouncedSearch"
        />
        <button v-if="searchQuery" class="clear-btn" @click="clearSearch">
          <i class="fas fa-times" />
        </button>
      </div>
      <div class="type-tabs">
        <button
          v-for="t in types"
          :key="t.value"
          class="type-btn"
          :class="{ active: activeType === t.value && !isSearchMode }"
          @click="switchType(t.value)"
        >{{ t.label }}</button>
      </div>
    </div>

    <!-- 操作按钮行 -->
    <div class="toolbar toolbar-row2">
      <div class="action-btns">
        <button class="refresh-btn" :class="{ spinning: loading }" @click="load" title="刷新">
          <i class="fas fa-sync-alt" /><span class="btn-label">刷新</span>
        </button>
        <!-- 批量导入 -->
        <label class="import-btn" title="批量导入记忆（TXT/JSON）">
          <i class="fas fa-file-import" /><span class="btn-label">导入</span>
          <input type="file" accept=".txt,.json" style="display:none" @change="importFile" />
        </label>
        <!-- 知识提炼 -->
        <button class="distill-btn" :class="{ spinning: distilling }" :disabled="distilling"
                title="从短期对话中提炼知识写入长期记忆" @click="runDistill">
          <i class="fas fa-flask" />
          <span>{{ distilling ? '提炼中…' : '提炼知识' }}</span>
        </button>
        <!-- 导出 -->
        <div class="export-wrap" ref="exportRef">
          <button class="export-btn" @click="exportDropOpen = !exportDropOpen" title="导出">
            <i class="fas fa-download" />
            <span>导出</span>
          </button>
          <div v-if="exportDropOpen" class="export-dropdown">
            <a class="export-item" :href="exportUrl('markdown')" download @click="exportDropOpen=false">
              <i class="fas fa-file-alt" /> 记忆 Markdown
            </a>
            <a class="export-item" :href="exportUrl('json')" download @click="exportDropOpen=false">
              <i class="fas fa-code" /> 记忆 JSON
            </a>
            <div class="export-divider" />
            <button class="export-item" @click="exportMigration">
              <i class="fas fa-box-open" /> 迁移包（全量）
            </button>
          </div>
        </div>
        <!-- 导入迁移包 -->
        <label class="import-btn" title="导入迁移包恢复数据">
          <i class="fas fa-upload" /><span class="btn-label">恢复</span>
          <input type="file" accept=".json" style="display:none" @change="importMigration" />
        </label>
      </div>
      <!-- 危险操作：放在第二行右端，与其他按钮有视觉分隔 -->
      <button class="clear-all-btn" @click="confirmClearAll" title="清空全部记忆（不可恢复）">
        <i class="fas fa-trash-alt" /> 清空全部
      </button>
    </div>

    <!-- 搜索结果标签 -->
    <div v-if="isSearchMode" class="search-hint">
      <i class="fas fa-search" />
      「{{ lastSearchQuery }}」的{{ activeType === 'short_term' ? '关键词' : '语义' }}搜索结果 · {{ displayMemories.length }} 条
      <span v-if="activeType === 'short_term'" class="search-mode-tip">（短期记忆使用关键词匹配）</span>
      <button class="link-btn" @click="clearSearch">返回列表</button>
    </div>

    <!-- 摘要时间线 -->
    <div v-if="activeType === 'summaries' && !loading" class="summaries-wrap">
      <div v-if="summaries.length === 0" class="empty-state">
        <i class="fas fa-scroll empty-icon" />
        <p class="empty-title">暂无阶段摘要</p>
        <p class="empty-sub">每 {{ summaryInterval }} 轮对话后自动生成摘要</p>
      </div>
      <div v-else class="timeline">
        <div v-for="(s, idx) in summaries" :key="s.id" class="tl-item">
          <div class="tl-dot" />
          <div class="tl-card">
            <div class="tl-time">{{ s.timestamp }}</div>
            <div class="tl-content">{{ s.content }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 记忆列表 -->
    <div v-if="activeType !== 'summaries' && displayMemories.length > 0" class="memory-list">
      <div
        v-for="mem in displayMemories"
        :key="mem.id"
        class="memory-card"
        :class="{ deleting: deletingId === mem.id }"
      >
        <div class="memory-header">
          <div class="memory-meta">
            <span class="badge cat-badge">{{ mem.category }}</span>
            <span v-if="mem.role" class="badge role-badge">{{ mem.role }}</span>
            <span v-if="mem.similarity !== undefined" class="badge sim-badge">
              相关性 {{ (mem.similarity * 100).toFixed(0) }}%
            </span>
          </div>
          <div class="memory-actions">
            <span class="importance-badge"
                  :class="mem.importance >= 0.8 ? 'imp-high' : mem.importance >= 0.5 ? 'imp-mid' : 'imp-low'"
                  :title="`重要性: ${mem.importance}（点击修改）`"
                  style="cursor:pointer"
                  @click.stop="editImportance(mem)">
              ★ {{ mem.importance }} <i class="fas fa-pen" style="font-size:0.65rem;margin-left:2px" />
            </span>
            <button
              v-if="activeType === 'long_term' || isSearchMode"
              class="del-btn"
              :disabled="deletingId === mem.id"
              @click="deleteOne(mem.id)"
            >
              <i class="fas fa-trash" />
            </button>
          </div>
        </div>
        <div class="memory-content">{{ mem.content }}</div>
        <div class="memory-footer">
          <span class="mem-time">{{ formatTime(mem.created_at) }}</span>
          <span v-if="mem.access_count" class="mem-access">
            访问 {{ mem.access_count }} 次
          </span>
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="activeType !== 'summaries' && activeType !== 'files' && !loading" class="empty-state">
      <i class="fas fa-brain empty-icon" />
      <p class="empty-title">{{ isSearchMode ? '未找到相关记忆' : '暂无记忆数据' }}</p>
      <p class="empty-sub">{{ isSearchMode ? '换个关键词试试' : '开始对话后记忆将自动积累' }}</p>
    </div>

    <!-- 加载 -->
    <div v-if="loading" class="loading-state">
      <i class="fas fa-circle-notch fa-spin" />
      <span>加载中...</span>
    </div>

    <!-- 文件入库面板 -->
    <div v-if="activeType === 'files'" class="files-panel">
      <!-- 上传区 -->
      <div class="upload-zone"
           :class="{ 'drag-over': dragOver }"
           @dragover.prevent="dragOver = true"
           @dragleave="dragOver = false"
           @drop.prevent="onDrop"
           @click="$refs.fileInput.click()">
        <i class="fas fa-cloud-upload-alt upload-icon" />
        <p class="upload-title">点击或拖拽文件到此处</p>
        <p class="upload-sub">支持 .txt .md .pdf .json，最大 10 MB</p>
        <input ref="fileInput" type="file" accept=".txt,.md,.pdf,.json" style="display:none" @change="onFileSelect" />
      </div>

      <!-- 描述输入 + 上传进度 -->
      <div v-if="pendingFile" class="pending-file">
        <div class="pending-info">
          <i class="fas fa-file-alt" />
          <span class="pending-name">{{ pendingFile.name }}</span>
          <span class="pending-size">{{ (pendingFile.size / 1024).toFixed(1) }} KB</span>
        </div>
        <input v-model="pendingDesc" class="desc-input" placeholder="描述（可选）" />
        <div class="pending-actions">
          <button class="upload-confirm-btn" :disabled="uploading" @click="doUpload">
            <i class="fas fa-check" /> {{ uploading ? '入库中…' : '确认入库' }}
          </button>
          <button class="upload-cancel-btn" @click="pendingFile = null; pendingDesc = ''">取消</button>
        </div>
      </div>

      <!-- 文件列表 -->
      <div class="file-list-header">
        <span class="file-list-title">已入库文件 ({{ knowledgeFiles.length }})</span>
        <button class="refresh-btn" :class="{ spinning: filesLoading }" @click="loadKnowledgeFiles">
          <i class="fas fa-sync-alt" />
        </button>
      </div>
      <div v-if="filesLoading" class="loading-state">
        <i class="fas fa-circle-notch fa-spin" /><span>加载中…</span>
      </div>
      <div v-else-if="!knowledgeFiles.length" class="empty-state">
        <i class="fas fa-folder-open empty-icon" />
        <p class="empty-title">暂无入库文件</p>
        <p class="empty-sub">上传文档后可在对话中语义检索</p>
      </div>
      <div v-else class="file-list">
        <div v-for="f in knowledgeFiles" :key="f.file_id" class="file-card">
          <div class="file-card-top">
            <i class="fas fa-file-alt file-icon" />
            <span class="file-name">{{ f.filename }}</span>
            <button class="del-btn" :disabled="deletingFileId === f.file_id" @click="deleteFile(f)">
              <i class="fas fa-trash" />
            </button>
          </div>
          <div v-if="f.description" class="file-desc">{{ f.description }}</div>
          <div class="file-meta">
            <span>{{ f.chunk_count }} 块</span>
            <span>{{ (f.size_bytes / 1024).toFixed(1) }} KB</span>
            <span>{{ formatTime(f.uploaded_at) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { getMemoryList, getMemoryStats, deleteMemory, clearAllMemory, searchMemory, updateMemoryImportance, batchImportMemory, distillMemory, getMemorySummaries, exportMemory, listKnowledgeFiles, deleteKnowledgeFile, uploadKnowledgeFile } from '@/services/api'
import { exportAllSessions, importSessions } from '@/services/localDB'
import { formatDateTime as formatTime } from '@/utils/date'

const loading       = ref(false)
const distilling    = ref(false)
const memories      = ref([])
const searchResults = ref([])
const summaries     = ref([])
const stats         = ref({})
const activeType    = ref('long_term')
const searchQuery   = ref('')
const lastSearchQuery = ref('')
const isSearchMode  = ref(false)
const deletingId    = ref(null)
const exportDropOpen = ref(false)
const exportRef      = ref(null)
const summaryInterval = 10  // matches backend default

const types = [
  { value: 'long_term',  label: '长期记忆' },
  { value: 'short_term', label: '短期记忆' },
  { value: 'summaries',  label: '摘要历史' },
  { value: 'files',      label: '文件入库' },
]

// ── 文件入库状态 ──────────────────────────────────────────────────────────────
const knowledgeFiles  = ref([])
const filesLoading    = ref(false)
const pendingFile     = ref(null)
const pendingDesc     = ref('')
const uploading       = ref(false)
const deletingFileId  = ref(null)
const dragOver        = ref(false)
const fileInput       = ref(null)

const displayMemories = computed(() =>
  isSearchMode.value ? searchResults.value : memories.value
)

const avgImportance = computed(() => {
  const items = memories.value
  if (!items.length) return '-'
  const avg = items.reduce((s, m) => s + (m.importance ?? 0), 0) / items.length
  return isNaN(avg) ? '-' : avg.toFixed(2)
})


const load = async () => {
  loading.value = true
  try {
    if (activeType.value === 'summaries') {
      const [sumData, statsData] = await Promise.all([
        getMemorySummaries(50),
        getMemoryStats()
      ])
      summaries.value = sumData?.summaries || []
      stats.value     = statsData?.stats   || {}
    } else {
      const [listData, statsData] = await Promise.all([
        getMemoryList(activeType.value, 100),
        getMemoryStats()
      ])
      memories.value = listData?.memories || []
      stats.value    = statsData?.stats   || {}
    }
  } finally {
    loading.value = false
  }
}

const exportUrl = (format) => exportMemory(format)

const switchType = (type) => {
  activeType.value = type
  isSearchMode.value = false
  searchQuery.value  = ''
  if (type === 'files') {
    loadKnowledgeFiles()
  } else {
    load()
  }
}

// ── 文件入库逻辑 ──────────────────────────────────────────────────────────────

const loadKnowledgeFiles = async () => {
  filesLoading.value = true
  try {
    const res = await listKnowledgeFiles()
    knowledgeFiles.value = res?.files || []
  } finally {
    filesLoading.value = false
  }
}

const onFileSelect = (e) => {
  const f = e.target.files?.[0]
  if (f) { pendingFile.value = f; dragOver.value = false }
  e.target.value = ''
}

const onDrop = (e) => {
  dragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) pendingFile.value = f
}

const doUpload = async () => {
  if (!pendingFile.value || uploading.value) return
  uploading.value = true
  try {
    const res = await uploadKnowledgeFile(pendingFile.value, pendingDesc.value)
    if (res?.success) {
      ElMessage({ message: `「${res.filename}」已入库，共 ${res.chunk_count} 个知识块`, type: 'success', duration: 3000 })
      pendingFile.value = null
      pendingDesc.value = ''
      await loadKnowledgeFiles()
    } else {
      ElMessage({ message: res?.message || '上传失败', type: 'error', duration: 3000 })
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 2000 })
  } finally {
    uploading.value = false
  }
}

const deleteFile = async (f) => {
  const { useConfirmDialogStore } = await import('@/stores/confirmDialog')
  const confirmDialog = useConfirmDialogStore()
  const ok = await confirmDialog.confirm(
    `确认删除「${f.filename}」及其 ${f.chunk_count} 个向量块？`,
    { title: '删除知识文件', confirmText: '删除', danger: true }
  )
  if (!ok) return
  deletingFileId.value = f.file_id
  try {
    await deleteKnowledgeFile(f.file_id)
    knowledgeFiles.value = knowledgeFiles.value.filter(x => x.file_id !== f.file_id)
    ElMessage({ message: '文件已删除', type: 'success', duration: 1500 })
  } catch {
    ElMessage({ message: '删除失败', type: 'error', duration: 2000 })
  } finally {
    deletingFileId.value = null
  }
}

const doSearch = async () => {
  const q = searchQuery.value.trim()
  if (!q) return

  // 短期记忆：前端关键词过滤（不支持向量搜索）
  if (activeType.value === 'short_term') {
    const ql = q.toLowerCase()
    searchResults.value = memories.value.filter(m =>
      m.content?.toLowerCase().includes(ql) ||
      m.category?.toLowerCase().includes(ql)
    )
    lastSearchQuery.value = q
    isSearchMode.value   = true
    return
  }

  // 长期记忆：向量语义搜索
  loading.value = true
  try {
    const data = await searchMemory(q, 20)
    searchResults.value  = data?.results || []
    lastSearchQuery.value = q
    isSearchMode.value   = true
  } finally {
    loading.value = false
  }
}

const clearSearch = () => {
  searchQuery.value    = ''
  lastSearchQuery.value = ''
  isSearchMode.value   = false
  searchResults.value  = []
}

let _searchTimer = null
const debouncedSearch = () => {
  clearTimeout(_searchTimer)
  if (!searchQuery.value.trim()) { clearSearch(); return }
  _searchTimer = setTimeout(doSearch, 500)
}

const deleteOne = async (id) => {
  try {
    await ElMessageBox.confirm('确定删除这条记忆？', '删除确认', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
  } catch { return }
  deletingId.value = id
  try {
    await deleteMemory(id)
    memories.value     = memories.value.filter(m => m.id !== id)
    searchResults.value = searchResults.value.filter(m => m.id !== id)
    if (stats.value?.long_term) stats.value.long_term.count--
    ElMessage({ message: '记忆已删除', type: 'success', duration: 2000 })
  } finally {
    deletingId.value = null
  }
}

const confirmClearAll = async () => {
  try {
    await ElMessageBox.confirm('确定清空全部记忆？此操作不可撤销。', '清空确认', {
      confirmButtonText: '清空', cancelButtonText: '取消', type: 'error'
    })
  } catch { return }
  loading.value = true
  try {
    await clearAllMemory()
    memories.value = []
    searchQuery.value     = ''
    lastSearchQuery.value = ''
    isSearchMode.value    = false
    searchResults.value   = []
    ElMessage({ message: '全部记忆已清空', type: 'success', duration: 2000 })
    await load()
  } finally {
    loading.value = false
  }
}

// ── 批量导入（WANT-002）──────────────────────────────────
const importFile = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  e.target.value = ''  // 重置 input 以便重复选择同一文件

  const text = await file.text()
  let items = []

  if (file.name.endsWith('.json')) {
    try {
      const parsed = JSON.parse(text)
      items = Array.isArray(parsed)
        ? parsed.map(p => typeof p === 'string' ? { content: p } : p)
        : [{ content: text }]
    } catch {
      ElMessage({ message: 'JSON 格式错误', type: 'error', duration: 3000 })
      return
    }
  } else {
    // TXT：每行一条记忆
    items = text.split('\n')
      .map(l => l.trim())
      .filter(Boolean)
      .map(content => ({ content, category: 'knowledge', importance: 0.6 }))
  }

  if (!items.length) {
    ElMessage({ message: '文件内容为空', type: 'warning', duration: 2000 })
    return
  }

  try {
    await ElMessageBox.confirm(
      `将导入 <strong>${items.length}</strong> 条记忆，合并到现有数据。确认继续？`,
      '批量导入记忆',
      { confirmButtonText: '确认导入', cancelButtonText: '取消',
        type: 'info', dangerouslyUseHTMLString: true }
    )
  } catch { return }

  loading.value = true
  try {
    const res = await batchImportMemory(items)
    if (res?.success) {
      ElMessage({ message: `成功导入 ${res.imported} 条记忆${res.errors?.length ? `，${res.errors.length} 条失败` : ''}`, type: 'success', duration: 3000 })
      await load()
    } else {
      ElMessage({ message: res?.message || '导入失败', type: 'error', duration: 3000 })
    }
  } finally {
    loading.value = false
  }
}

// ── 知识提炼 ──────────────────────────────────────────────
const runDistill = async () => {
  distilling.value = true
  try {
    const res = await distillMemory()
    if (res?.success !== false) {
      ElMessage({ message: `知识提炼完成，已写入长期记忆`, type: 'success', duration: 3000 })
      await load()
    } else {
      ElMessage({ message: res?.message || '提炼失败', type: 'error', duration: 3000 })
    }
  } catch {
    ElMessage({ message: '提炼请求失败', type: 'error', duration: 2000 })
  } finally {
    distilling.value = false
  }
}

const editImportance = async (mem) => {
  let newVal = mem.importance
  try {
    await ElMessageBox({
      title: '修改记忆重要性',
      message: `当前: ${mem.importance}，输入新值（0.0 ~ 1.0）`,
      inputValue: String(mem.importance),
      showInput: true,
      inputPlaceholder: '0.0 ~ 1.0',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      beforeClose: (action, instance, done) => {
        if (action === 'confirm') {
          const v = parseFloat(instance.inputValue)
          if (isNaN(v) || v < 0 || v > 1) {
            instance.inputErrorMessage = '请输入 0.0 ~ 1.0 的数值'
            return
          }
          newVal = Math.round(v * 100) / 100
        }
        done()
      }
    })
  } catch { return }

  const result = await updateMemoryImportance(mem.id, newVal)
  if (result?.success) {
    mem.importance = newVal
    const target = memories.value.find(m => m.id === mem.id)
    if (target) target.importance = newVal
    ElMessage({ message: `重要性已更新为 ${newVal}`, type: 'success', duration: 2000 })
  } else {
    ElMessage({ message: result?.message || '更新失败', type: 'error', duration: 3000 })
  }
}

// ── 迁移包导出/导入 ───────────────────────────────────────
const exportMigration = async () => {
  exportDropOpen.value = false
  try {
    // 合并本地会话 + 服务端记忆
    const [sessions, memRes] = await Promise.all([
      exportAllSessions(),
      fetch('/api/memory/export?format=json').then(r => r.json()).catch(() => [])
    ])
    const bundle = {
      version: 1,
      exported_at: new Date().toISOString(),
      sessions,
      memories: Array.isArray(memRes) ? memRes : [],
    }
    const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `migration_${new Date().toISOString().slice(0,10)}.json`
    a.click()
    ElMessage({ message: `迁移包已导出（${sessions.length} 条会话，${bundle.memories.length} 条记忆）`, type: 'success', duration: 3000 })
  } catch (e) {
    ElMessage({ message: '导出失败: ' + e.message, type: 'error', duration: 3000 })
  }
}

const importMigration = async (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  e.target.value = ''
  try {
    const text   = await file.text()
    const bundle = JSON.parse(text)
    if (bundle.version !== 1 || !Array.isArray(bundle.sessions)) {
      ElMessage({ message: '文件格式不正确，请使用本应用导出的迁移包', type: 'error', duration: 3000 })
      return
    }
    const sessionsToImport = bundle.sessions.length
    const memoriesCount    = bundle.memories?.length || 0
    try {
      await ElMessageBox.confirm(
        `将合并导入 <strong>${sessionsToImport}</strong> 条会话` +
        (memoriesCount ? `和 <strong>${memoriesCount}</strong> 条记忆` : '') +
        `，不会删除已有数据。确认继续？`,
        '导入迁移数据',
        { confirmButtonText: '确认导入', cancelButtonText: '取消',
          type: 'info', dangerouslyUseHTMLString: true }
      )
    } catch { return }
    const sessionCount = await importSessions(bundle.sessions)
    let memCount = 0
    if (bundle.memories?.length) {
      const res = await fetch('/api/memory/batch-import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ items: bundle.memories.map(m => ({
          content: m.content, category: m.type || 'knowledge', importance: m.importance || 0.7
        })) })
      }).then(r => r.json()).catch(() => null)
      memCount = res?.imported || 0
    }
    ElMessage({ message: `导入成功：${sessionCount} 条会话，${memCount} 条记忆`, type: 'success', duration: 3000 })
    await load()
  } catch (ex) {
    ElMessage({ message: '导入失败: ' + ex.message, type: 'error', duration: 3000 })
  }
}

const onClickOutside = (e) => {
  if (exportRef.value && !exportRef.value.contains(e.target)) {
    exportDropOpen.value = false
  }
}

onMounted(() => {
  load()
  document.addEventListener('click', onClickOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', onClickOutside)
  clearTimeout(_searchTimer)
})
</script>

<style scoped>
.memory-view {
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: var(--color-bg);
}

/* ── 统计卡片 ─────────────────────────────────────────────── */
.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.clear-all-btn {
  height: 34px;
  padding: 0 14px;
  border: 1px solid #fca5a5;
  border-radius: var(--radius-sm);
  background: #fff5f5;
  color: #dc2626;
  font-size: 0.82rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  transition: all 0.2s;
  flex-shrink: 0;
}
.clear-all-btn:hover { background: #fee2e2; border-color: #f87171; }
.stat-card {
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 14px;
  text-align: center;
  cursor: default;
}
.stat-card.accent-primary { border-top: 3px solid var(--color-primary); }
.stat-card.accent-success { border-top: 3px solid var(--color-accent); }
.stat-card.accent-warn    { border-top: 3px solid var(--color-warn); }
.stat-num  { font-size: 22px; font-weight: 500; color: var(--color-text); }
.stat-label { font-size: 12px; color: var(--color-text-muted); margin-top: var(--space-1); }

/* ── 工具栏 ─────────────────────────────────────────────── */
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.toolbar-row1 { flex-wrap: nowrap; }
.toolbar-row2 {
  justify-content: space-between;
}
.action-btns {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.search-wrap {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
}
.search-icon {
  position: absolute;
  left: 10px;
  color: var(--color-text-muted);
  font-size: 0.85rem;
}
.search-input {
  width: 100%;
  padding: 8px 32px 8px 32px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 0.88rem;
  outline: none;
  background: var(--color-surface);
  transition: border-color 0.2s;
}
.search-input:focus { border-color: var(--color-primary); }
.clear-btn {
  position: absolute; right: 8px;
  background: none; border: none;
  color: var(--color-text-muted); cursor: pointer; font-size: 0.8rem;
}
.type-tabs { display: flex; gap: 6px; }
.type-btn {
  padding: 7px 14px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  font-size: 0.85rem; color: var(--color-text-secondary); cursor: pointer;
  transition: all 0.2s; white-space: nowrap;
}
.type-btn:hover  { border-color: var(--color-primary); color: var(--color-primary); }
.type-btn.active { background: var(--color-primary); border-color: var(--color-primary); color: white; }
.refresh-btn {
  padding: 8px 10px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; transition: all 0.2s;
  display: flex; align-items: center; gap: 5px;
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.import-btn {
  padding: 8px 10px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; transition: all 0.2s;
  display: flex; align-items: center; gap: 5px;
}
.import-btn:hover { border-color: var(--color-accent); color: var(--color-accent); }
.btn-label { font-size: 0.82rem; }
.distill-btn {
  padding: 8px 12px; border-radius: var(--radius-sm); display: flex; align-items: center; gap: 6px;
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; transition: all 0.2s; font-size: 0.85rem;
}
.distill-btn:hover:not(:disabled) { border-color: #7c4dff; color: #7c4dff; }
.distill-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.distill-btn.spinning i { animation: spin 0.8s linear infinite; }

/* ── 搜索提示 ────────────────────────────────────────────── */
.search-hint {
  font-size: 0.85rem; color: var(--color-primary);
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
}
.search-mode-tip { font-size: 0.78rem; color: var(--color-text-muted); }
.link-btn {
  background: none; border: none;
  color: var(--color-primary); cursor: pointer;
  font-size: 0.85rem; text-decoration: underline;
}

/* ── 记忆列表 ────────────────────────────────────────────── */
.memory-list { display: flex; flex-direction: column; gap: 10px; }
.memory-card {
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 14px 16px;
  transition: all 0.2s;
}
.memory-card:hover   { border-color: #c5caf5; }
.memory-card.deleting { opacity: 0.4; pointer-events: none; }

.memory-header {
  display: flex; align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-2);
}
.memory-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.badge {
  font-size: 11px; padding: 2px 8px;
  border-radius: var(--radius-sm); font-weight: 500;
}
.cat-badge  { background: var(--color-surface-raised); color: var(--color-primary); }
.role-badge { background: #e8f5e9; color: #2e7d32; }
.sim-badge  { background: #fff8e1; color: #f57f17; }

.memory-actions {
  display: flex; align-items: center; gap: var(--space-2);
}
.importance-badge {
  font-size: 0.75rem; padding: 2px 8px; border-radius: 10px;
  font-weight: 500; cursor: default;
}
.imp-high { background: #fff8e1; color: var(--color-warn); }
.imp-mid  { background: #e8f5e9; color: #2e7d32; }
.imp-low  { background: var(--color-bg); color: var(--color-text-muted); }
.del-btn {
  background: none; border: none;
  color: var(--color-text-muted); cursor: pointer;
  font-size: 0.82rem; padding: var(--space-1);
  border-radius: 4px; transition: color 0.2s;
}
.del-btn:hover:not(:disabled) { color: var(--color-danger); }
.del-btn:disabled { cursor: not-allowed; }

.memory-content {
  font-size: 0.88rem; color: var(--color-text);
  line-height: 1.6; margin-bottom: var(--space-2);
  word-break: break-word;
}
.memory-footer {
  display: flex; align-items: center; gap: var(--space-3);
}
.mem-time   { font-size: 0.75rem; color: var(--color-text-muted); }
.mem-access { font-size: 0.75rem; color: var(--color-text-muted); }

/* ── 空状态 ───────────────────────────────────────────────── */
.empty-state {
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: var(--space-2); padding: 60px 20px;
  background: var(--color-surface); border-radius: var(--radius-md);
  border: 0.5px solid var(--color-border);
}
.empty-icon  { font-size: 2.5rem; color: #ddd; }
.empty-title { font-size: 1rem; font-weight: 500; color: var(--color-text-secondary); margin: 0; }
.empty-sub   { font-size: 0.85rem; color: var(--color-text-muted); margin: 0; }

/* ── 加载 ─────────────────────────────────────────────────── */
.loading-state {
  display: flex; align-items: center;
  justify-content: center; gap: 10px;
  padding: 40px; color: var(--color-text-muted); font-size: 0.9rem;
}

/* ── 导出下拉 ─────────────────────────────────────────────── */
.export-wrap { position: relative; }
.export-btn {
  padding: 8px 12px; border-radius: var(--radius-sm); display: flex; align-items: center; gap: 6px;
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; transition: all 0.2s; font-size: 0.85rem;
}
.export-btn:hover { border-color: #1976d2; color: #1976d2; }
.export-dropdown {
  position: absolute; right: 0; top: calc(100% + 6px);
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-sm); box-shadow: 0 4px 16px rgba(0,0,0,0.1);
  min-width: 130px; z-index: 100; overflow: hidden;
}
.export-item {
  display: flex; align-items: center; gap: var(--space-2);
  padding: 10px 14px; font-size: 0.85rem; color: #444;
  text-decoration: none; transition: background 0.15s;
}
.export-item:hover { background: #f5f7ff; color: #1976d2; }
.export-divider { height: 1px; background: var(--color-border); margin: var(--space-1) 0; }
button.export-item { width: 100%; text-align: left; background: none; border: none; cursor: pointer; font-size: 0.85rem; }

/* ── 摘要时间线 ───────────────────────────────────────────── */
.summaries-wrap { flex: 1; }
.timeline {
  display: flex; flex-direction: column; gap: 0;
  padding-left: var(--space-4);
  border-left: 2px solid var(--color-border);
  margin-left: var(--space-2);
}
.tl-item {
  position: relative; padding: 0 0 20px 24px;
}
.tl-dot {
  position: absolute; left: -7px; top: 4px;
  width: 12px; height: 12px; border-radius: 50%;
  background: var(--color-primary); border: 2px solid white;
  box-shadow: 0 0 0 2px var(--color-primary);
}
.tl-card {
  background: var(--color-surface); border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md); padding: 12px 16px;
}
.tl-time {
  font-size: 0.75rem; color: var(--color-text-muted); margin-bottom: 6px;
}
.tl-content {
  font-size: 0.88rem; color: var(--color-text); line-height: 1.6;
}

/* ── 文件入库面板 ─────────────────────────────────────────── */
.files-panel { display: flex; flex-direction: column; gap: var(--space-4); }
.upload-zone {
  border: 2px dashed #c9d1d9; border-radius: var(--radius-md);
  padding: 40px 20px; text-align: center;
  background: var(--color-bg); cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.upload-zone:hover, .upload-zone.drag-over {
  border-color: #1976d2; background: #e8f0fd;
}
.upload-icon { font-size: 2rem; color: #90a4ae; margin-bottom: var(--space-2); }
.upload-title { font-size: 1rem; font-weight: 500; color: var(--color-text-secondary); margin: 0 0 4px; }
.upload-sub   { font-size: 0.82rem; color: var(--color-text-muted); margin: 0; }
.pending-file {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: 14px 16px;
  display: flex; flex-direction: column; gap: 10px;
}
.pending-info { display: flex; align-items: center; gap: var(--space-2); }
.pending-name { font-weight: 500; color: var(--color-text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-size { font-size: 0.78rem; color: var(--color-text-muted); white-space: nowrap; }
.desc-input {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  padding: 7px 10px; font-size: 0.85rem; outline: none;
  transition: border-color 0.2s;
}
.desc-input:focus { border-color: #1976d2; }
.pending-actions { display: flex; gap: var(--space-2); }
.upload-confirm-btn {
  padding: 7px 18px; border-radius: var(--radius-sm); border: none;
  background: #1976d2; color: white; font-size: 0.85rem;
  cursor: pointer; transition: background 0.2s;
}
.upload-confirm-btn:hover:not(:disabled) { background: #1565c0; }
.upload-confirm-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.upload-cancel-btn {
  padding: 7px 14px; border-radius: var(--radius-sm); border: 1px solid var(--color-border);
  background: var(--color-surface); color: var(--color-text-secondary); font-size: 0.85rem; cursor: pointer;
}
.upload-cancel-btn:hover { background: var(--color-bg); }
.file-list-header {
  display: flex; align-items: center; justify-content: space-between;
}
.file-list-title { font-size: 0.9rem; font-weight: 600; color: #444; }
.file-list { display: flex; flex-direction: column; gap: 10px; }
.file-card {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: 12px 14px;
}
.file-card-top {
  display: flex; align-items: center; gap: var(--space-2); margin-bottom: 4px;
}
.file-icon { color: #90a4ae; }
.file-name { flex: 1; font-weight: 500; color: var(--color-text); font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-desc { font-size: 0.8rem; color: #777; margin-bottom: 6px; }
.file-meta { display: flex; gap: 12px; font-size: 0.75rem; color: var(--color-text-muted); }

/* 暗色主题适配 */
[data-theme="dark"] .upload-zone { background: #1a1f2e; border-color: #3a4060; }
[data-theme="dark"] .upload-zone:hover, [data-theme="dark"] .upload-zone.drag-over { background: #1e2a45; border-color: #4f6ef7; }
[data-theme="dark"] .upload-icon { color: #6b7280; }
[data-theme="dark"] .pending-file, [data-theme="dark"] .file-card { background: #1a1f2e; border-color: #2d3451; }
[data-theme="dark"] .pending-name, [data-theme="dark"] .file-name { color: #c9d1d9; }
[data-theme="dark"] .desc-input { background: #0d1117; border-color: #2d3451; color: #c9d1d9; }
[data-theme="dark"] .upload-cancel-btn { background: #1a1f2e; color: #c9d1d9; border-color: #2d3451; }
[data-theme="dark"] .file-list-title { color: #c9d1d9; }
</style>