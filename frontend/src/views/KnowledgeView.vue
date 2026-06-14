<template>
  <div class="knowledge-view">

    <!-- 上传区域 -->
    <div class="section upload-section">
      <div
        class="upload-zone"
        :class="{ 'drag-over': dragOver }"
        @dragover.prevent="dragOver = true"
        @dragleave="dragOver = false"
        @drop.prevent="onDrop"
        @click="$refs.fileInput.click()"
      >
        <i class="fas fa-cloud-upload-alt upload-icon" />
        <p class="upload-title">点击或拖拽文件到此处上传</p>
        <p class="upload-sub">支持 .txt .md .pdf .json，最大 10 MB</p>
        <input
          ref="fileInput"
          type="file"
          accept=".txt,.md,.pdf,.json"
          style="display:none"
          @change="onFileSelect"
        />
      </div>

      <!-- 已选文件预览 + 描述 + 上传按钮 -->
      <div v-if="pendingFile" class="pending-card">
        <div class="pending-info">
          <i class="fas fa-file-alt pending-icon" />
          <span class="pending-name">{{ pendingFile.name }}</span>
          <span class="pending-size">{{ formatSize(pendingFile.size) }}</span>
          <button class="pending-remove" @click.stop="clearPending" title="取消">
            <i class="fas fa-times" />
          </button>
        </div>
        <input
          v-model="pendingDesc"
          class="desc-input"
          placeholder="描述（可选）——帮助日后识别文件用途"
        />
        <div class="pending-actions">
          <button class="upload-btn" :disabled="uploading" @click="doUpload">
            <i v-if="uploading" class="fas fa-circle-notch fa-spin" />
            <i v-else class="fas fa-check" />
            {{ uploading ? '入库中…' : '确认入库' }}
          </button>
          <button class="cancel-btn" @click="clearPending">取消</button>
        </div>
      </div>
    </div>

    <!-- 统计横幅 -->
    <div class="stats-banner">
      <span class="stats-icon"><i class="fas fa-book" /></span>
      <span v-if="!loading" class="stats-text">
        共 <strong>{{ files.length }}</strong> 个文件
        /
        <strong>{{ totalChunks }}</strong> 个知识块
      </span>
      <span v-else class="stats-text">加载中…</span>
      <div class="stats-spacer" />
      <button class="refresh-btn" :class="{ spinning: loading }" @click="loadFiles" title="刷新列表">
        <i class="fas fa-sync-alt" />
        <span class="refresh-label">刷新</span>
      </button>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="loading-state">
      <i class="fas fa-circle-notch fa-spin" />
      <span>加载中…</span>
    </div>

    <!-- 空状态 -->
    <div v-else-if="!files.length" class="empty-state">
      <i class="fas fa-book-open empty-icon" />
      <p class="empty-title">知识库暂无文件</p>
      <p class="empty-sub">上传文档后，智能体可在对话中进行语义检索</p>
    </div>

    <!-- 文件列表表格 -->
    <div v-else class="table-wrapper">
      <table class="file-table">
        <thead>
          <tr>
            <th class="col-name">文件名</th>
            <th class="col-chunks">知识块</th>
            <th class="col-size">大小</th>
            <th class="col-desc">描述</th>
            <th class="col-time">上传时间</th>
            <th class="col-action"></th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="f in files"
            :key="f.file_id"
            class="file-row"
            :class="{ deleting: deletingId === f.file_id }"
          >
            <td class="col-name">
              <div class="file-name-cell">
                <i class="fas fa-file-alt file-type-icon" />
                <span class="file-name-text">{{ f.filename }}</span>
              </div>
            </td>
            <td class="col-chunks">
              <span class="chunk-badge">{{ f.chunk_count }}</span>
            </td>
            <td class="col-size text-muted">{{ formatSize(f.size_bytes) }}</td>
            <td class="col-desc text-muted">
              <span class="desc-text" :title="f.description">{{ f.description || '—' }}</span>
            </td>
            <td class="col-time text-muted">{{ relativeTime(f.uploaded_at) }}</td>
            <td class="col-action">
              <button
                class="del-btn"
                :disabled="deletingId === f.file_id"
                @click="deleteFile(f)"
                title="删除该文件及所有向量块"
              >
                <i class="fas fa-trash" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listKnowledgeFiles, deleteKnowledgeFile, uploadKnowledgeFile } from '@/services/api'

// ── 状态 ─────────────────────────────────────────────────────────────────────
const files       = ref([])
const loading     = ref(false)
const dragOver    = ref(false)
const pendingFile = ref(null)
const pendingDesc = ref('')
const uploading   = ref(false)
const deletingId  = ref(null)
const fileInput   = ref(null)

// ── 计算 ─────────────────────────────────────────────────────────────────────
const totalChunks = computed(() =>
  files.value.reduce((sum, f) => sum + (f.chunk_count || 0), 0)
)

// ── 工具函数 ─────────────────────────────────────────────────────────────────

const formatSize = (bytes) => {
  if (!bytes && bytes !== 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

const relativeTime = (iso) => {
  if (!iso) return '-'
  try {
    const d    = new Date(iso)
    const diff = Date.now() - d.getTime()
    if (diff < 60_000)         return '刚刚'
    if (diff < 3_600_000)      return `${Math.floor(diff / 60_000)} 分钟前`
    if (diff < 86_400_000)     return `${Math.floor(diff / 3_600_000)} 小时前`
    if (diff < 86_400_000 * 7) return `${Math.floor(diff / 86_400_000)} 天前`
    return d.toLocaleString('zh-CN', {
      month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    })
  } catch { return iso }
}

// ── 数据加载 ─────────────────────────────────────────────────────────────────

const loadFiles = async () => {
  loading.value = true
  try {
    const res = await listKnowledgeFiles()
    files.value = res?.files || []
  } catch {
    ElMessage({ message: '加载文件列表失败', type: 'error', duration: 2000 })
  } finally {
    loading.value = false
  }
}

// ── 文件选择 / 拖拽 ───────────────────────────────────────────────────────────

const onFileSelect = (e) => {
  const f = e.target.files?.[0]
  if (f) { pendingFile.value = f; pendingDesc.value = '' }
  e.target.value = ''
}

const onDrop = (e) => {
  dragOver.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) { pendingFile.value = f; pendingDesc.value = '' }
}

const clearPending = () => {
  pendingFile.value = null
  pendingDesc.value = ''
}

// ── 上传 ─────────────────────────────────────────────────────────────────────

const doUpload = async () => {
  if (!pendingFile.value || uploading.value) return
  const file = pendingFile.value
  const desc = pendingDesc.value.trim()

  // 大小校验（10 MB）
  if (file.size > 10 * 1024 * 1024) {
    ElMessage({ message: '文件超过 10 MB，请压缩后重试', type: 'warning', duration: 3000 })
    return
  }

  uploading.value = true
  try {
    const res = await uploadKnowledgeFile(file, desc)
    if (res?.success) {
      ElMessage({
        message: `「${res.filename}」已入库，共 ${res.chunk_count} 个知识块`,
        type: 'success',
        duration: 3000,
      })
      clearPending()
      await loadFiles()
    } else {
      ElMessage({ message: res?.message || '上传失败', type: 'error', duration: 3000 })
    }
  } catch {
    ElMessage({ message: '网络错误，请重试', type: 'error', duration: 2000 })
  } finally {
    uploading.value = false
  }
}

// ── 删除 ─────────────────────────────────────────────────────────────────────

const deleteFile = async (f) => {
  const { useConfirmDialogStore } = await import('@/stores/confirmDialog')
  const confirmDialog = useConfirmDialogStore()
  const ok = await confirmDialog.confirm(
    `确认删除「${f.filename}」及其 ${f.chunk_count} 个向量块？此操作不可恢复。`,
    { title: '删除知识文件', confirmText: '删除', danger: true }
  )
  if (!ok) return

  deletingId.value = f.file_id
  try {
    await deleteKnowledgeFile(f.file_id)
    files.value = files.value.filter(x => x.file_id !== f.file_id)
    ElMessage({ message: `「${f.filename}」已删除`, type: 'success', duration: 2000 })
  } catch {
    ElMessage({ message: '删除失败，请重试', type: 'error', duration: 2000 })
  } finally {
    deletingId.value = null
  }
}

// ── 生命周期 ─────────────────────────────────────────────────────────────────
onMounted(() => loadFiles())
</script>

<style scoped>
.knowledge-view {
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #f8f9fa;
}

/* ── 上传区 ──────────────────────────────────────────────────── */
.upload-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section {
  background: white;
  border: 0.5px solid #e8eaed;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.upload-zone {
  border: 2px dashed #c9d1d9;
  border-radius: 10px;
  padding: 36px 20px;
  text-align: center;
  cursor: pointer;
  background: #fafbfc;
  transition: border-color 0.2s, background 0.2s;
  user-select: none;
}
.upload-zone:hover,
.upload-zone.drag-over {
  border-color: #667eea;
  background: #f0f2ff;
}
.upload-icon  { font-size: 2rem; color: #aab0d0; display: block; margin-bottom: 8px; }
.upload-title { font-size: 0.95rem; font-weight: 500; color: #555; margin: 0 0 4px; }
.upload-sub   { font-size: 0.8rem; color: #aaa; margin: 0; }

/* ── 待上传文件卡片 ─────────────────────────────────────────── */
.pending-card {
  background: white;
  border: 1px solid #e0e3e8;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.pending-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pending-icon  { color: #667eea; font-size: 1rem; flex-shrink: 0; }
.pending-name  { flex: 1; font-weight: 500; color: #333; font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-size  { font-size: 0.78rem; color: #999; white-space: nowrap; }
.pending-remove {
  background: none; border: none; color: #bbb; cursor: pointer;
  font-size: 0.8rem; padding: 2px 4px; border-radius: 4px;
  transition: color 0.2s; flex-shrink: 0;
}
.pending-remove:hover { color: #f44336; }

.desc-input {
  border: 1px solid #e0e3e8;
  border-radius: 7px;
  padding: 7px 10px;
  font-size: 0.85rem;
  outline: none;
  transition: border-color 0.2s;
  font-family: inherit;
}
.desc-input:focus { border-color: #667eea; }

.pending-actions { display: flex; gap: 8px; }

.upload-btn {
  padding: 7px 20px;
  border-radius: 7px;
  border: none;
  background: #667eea;
  color: white;
  font-size: 0.85rem;
  cursor: pointer;
  transition: background 0.2s;
  display: flex;
  align-items: center;
  gap: 6px;
}
.upload-btn:hover:not(:disabled) { background: #5568d8; }
.upload-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.cancel-btn {
  padding: 7px 16px;
  border-radius: 7px;
  border: 1px solid #e0e3e8;
  background: white;
  color: #666;
  font-size: 0.85rem;
  cursor: pointer;
  transition: background 0.2s;
}
.cancel-btn:hover { background: #f5f5f5; }

/* ── 统计横幅 ────────────────────────────────────────────────── */
.stats-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: white;
  border: 0.5px solid #e8eaed;
  border-radius: 10px;
  padding: 10px 16px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}
.stats-icon { color: #667eea; font-size: 1rem; }
.stats-text { font-size: 0.88rem; color: #555; }
.stats-text strong { color: #333; }
.stats-spacer { flex: 1; }

.refresh-btn {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border-radius: 7px;
  border: 1px solid #e0e3e8;
  background: white;
  color: #555;
  font-size: 0.82rem;
  cursor: pointer;
  transition: all 0.2s;
}
.refresh-btn:hover { border-color: #667eea; color: #667eea; }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
.refresh-label { font-size: 0.82rem; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 加载/空状态 ─────────────────────────────────────────────── */
.loading-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 20px;
  color: #aaa;
  font-size: 0.9rem;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 64px 20px;
  background: white;
  border-radius: 12px;
  border: 0.5px solid #e8eaed;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}
.empty-icon  { font-size: 2.5rem; color: #ddd; }
.empty-title { font-size: 1rem; font-weight: 500; color: #666; margin: 0; }
.empty-sub   { font-size: 0.85rem; color: #aaa; margin: 0; }

/* ── 文件表格 ─────────────────────────────────────────────────── */
.table-wrapper {
  background: white;
  border: 0.5px solid #e8eaed;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.file-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.file-table thead {
  background: #f8f9fa;
  border-bottom: 1px solid #e8eaed;
}

.file-table th {
  padding: 10px 14px;
  font-size: 0.78rem;
  font-weight: 600;
  color: #888;
  text-align: left;
  white-space: nowrap;
}

.file-table td {
  padding: 11px 14px;
  border-bottom: 1px solid #f2f3f5;
  vertical-align: middle;
}

.file-row:last-child td { border-bottom: none; }

.file-row:hover td { background: #fafbff; }

.file-row.deleting { opacity: 0.4; pointer-events: none; }

/* 列宽 */
.col-name   { min-width: 160px; }
.col-chunks { width: 80px;  text-align: center; }
.col-size   { width: 90px;  white-space: nowrap; }
.col-desc   { max-width: 220px; }
.col-time   { width: 120px; white-space: nowrap; }
.col-action { width: 48px;  text-align: center; }

/* 文件名单元格 */
.file-name-cell {
  display: flex;
  align-items: center;
  gap: 7px;
}
.file-type-icon { color: #90a4ae; flex-shrink: 0; }
.file-name-text {
  font-weight: 500;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

/* 知识块标签 */
.chunk-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  background: #f0f2ff;
  color: #667eea;
  font-size: 0.78rem;
  font-weight: 600;
  text-align: center;
}

.text-muted { color: #999; font-size: 0.82rem; }

/* 描述截断 */
.desc-text {
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
  cursor: default;
}

/* 删除按钮 */
.del-btn {
  background: none;
  border: none;
  color: #ccc;
  cursor: pointer;
  font-size: 0.85rem;
  padding: 5px 6px;
  border-radius: 5px;
  transition: color 0.2s, background 0.2s;
  line-height: 1;
}
.del-btn:hover:not(:disabled) { color: #f44336; background: #fff0f0; }
.del-btn:disabled { cursor: not-allowed; opacity: 0.4; }

/* ── 暗色主题 ─────────────────────────────────────────────────── */
[data-theme="dark"] .knowledge-view {
  background: #0d1117;
}

[data-theme="dark"] .section,
[data-theme="dark"] .stats-banner,
[data-theme="dark"] .empty-state,
[data-theme="dark"] .table-wrapper {
  background: #161b22;
  border-color: #2d3451;
}

[data-theme="dark"] .upload-zone {
  background: #1a1f2e;
  border-color: #3a4060;
}
[data-theme="dark"] .upload-zone:hover,
[data-theme="dark"] .upload-zone.drag-over {
  background: #1e2a45;
  border-color: #667eea;
}
[data-theme="dark"] .upload-title { color: #c9d1d9; }
[data-theme="dark"] .upload-sub   { color: #6b7280; }

[data-theme="dark"] .pending-card {
  background: #1a1f2e;
  border-color: #2d3451;
}
[data-theme="dark"] .pending-name { color: #c9d1d9; }
[data-theme="dark"] .desc-input {
  background: #0d1117;
  border-color: #2d3451;
  color: #c9d1d9;
}
[data-theme="dark"] .cancel-btn {
  background: #1a1f2e;
  border-color: #2d3451;
  color: #c9d1d9;
}

[data-theme="dark"] .stats-text { color: #9ca3af; }
[data-theme="dark"] .stats-text strong { color: #c9d1d9; }
[data-theme="dark"] .refresh-btn {
  background: #161b22;
  border-color: #2d3451;
  color: #9ca3af;
}
[data-theme="dark"] .refresh-btn:hover { border-color: #667eea; color: #667eea; }

[data-theme="dark"] .file-table thead { background: #1a1f2e; }
[data-theme="dark"] .file-table th    { color: #6b7280; }
[data-theme="dark"] .file-table td    { border-color: #21262d; }
[data-theme="dark"] .file-row:hover td { background: #1e2430; }
[data-theme="dark"] .file-name-text   { color: #c9d1d9; }
[data-theme="dark"] .text-muted       { color: #6b7280; }
[data-theme="dark"] .chunk-badge      { background: #1e2a45; color: #7c9ef5; }
[data-theme="dark"] .empty-title      { color: #9ca3af; }

@media (max-width: 768px) {
  .col-desc   { display: none; }
  .col-size   { display: none; }
  .file-name-text { max-width: 140px; }
}
</style>
