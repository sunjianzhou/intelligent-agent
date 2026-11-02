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
    <SkeletonList v-if="loading" :rows="4" />

    <!-- 空状态 -->
    <EmptyState
        v-else-if="!files.length"
        icon="fas fa-book-open"
        title="知识库暂无文件"
        sub="上传文档后，智能体可在对话中进行语义检索"
      />

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
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

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
  padding: var(--space-4-5);
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  background: var(--color-bg);
}

/* ── 上传区 ──────────────────────────────────────────────────── */
.upload-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.section {
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-card);
}

.upload-zone {
  border: 2px dashed var(--color-border-strong);
  border-radius: var(--radius-md);
  padding: 36px var(--space-4-5);
  text-align: center;
  cursor: pointer;
  background: var(--color-bg);
  transition: border-color var(--duration-base), background var(--duration-base);
  user-select: none;
}
.upload-zone:hover,
.upload-zone.drag-over {
  border-color: var(--color-primary);
  background: var(--color-surface-raised);
}
.upload-icon  { font-size: 2rem; color: var(--color-text-muted); display: block; margin-bottom: var(--space-2); }
.upload-title { font-size: var(--text-base); font-weight: var(--weight-medium); color: var(--color-text-secondary); margin: 0 0 var(--space-1); }
.upload-sub   { font-size: var(--text-sm-2); color: var(--color-text-muted); margin: 0; }

/* ── 待上传文件卡片 ─────────────────────────────────────────── */
.pending-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-3-5) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-2-5);
}
.pending-info {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.pending-icon  { color: var(--color-primary); font-size: var(--text-md); flex-shrink: 0; }
.pending-name  { flex: 1; font-weight: var(--weight-medium); color: var(--color-text); font-size: var(--text-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-size  { font-size: var(--text-xs); color: var(--color-text-muted); white-space: nowrap; }
.pending-remove {
  background: none; border: none; color: var(--color-text-muted); cursor: pointer;
  font-size: var(--text-sm-2); padding: var(--space-0-5) var(--space-1); border-radius: var(--radius-xs);
  transition: color var(--duration-base); flex-shrink: 0;
}
.pending-remove:hover { color: var(--color-danger); }

.desc-input {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: var(--space-1-5) var(--space-2-5);
  font-size: var(--text-sm);
  outline: none;
  transition: border-color var(--duration-base);
  font-family: inherit;
}
.desc-input:focus { border-color: var(--color-primary); }

.pending-actions { display: flex; gap: var(--space-2); }

.upload-btn {
  padding: var(--space-1-5) var(--space-4-5);
  border-radius: var(--radius-sm);
  border: none;
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--duration-base);
  display: flex;
  align-items: center;
  gap: var(--space-1-5);
}
.upload-btn:hover:not(:disabled) { background: var(--color-primary-hover); }
.upload-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.cancel-btn {
  padding: var(--space-1-5) var(--space-4);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--duration-base);
}
.cancel-btn:hover { background: var(--color-bg); }

/* ── 统计横幅 ────────────────────────────────────────────────── */
.stats-banner {
  display: flex;
  align-items: center;
  gap: var(--space-2-5);
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-2-5) var(--space-4);
  box-shadow: var(--shadow-card);
}
.stats-icon { color: var(--color-primary); font-size: var(--text-md); }
.stats-text { font-size: var(--text-sm); color: var(--color-text-secondary); }
.stats-text strong { color: var(--color-text); }
.stats-spacer { flex: 1; }

.refresh-btn {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1-5) var(--space-3);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--text-sm-2);
  cursor: pointer;
  transition: var(--transition-interactive);
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
.refresh-label { font-size: var(--text-sm-2); }
@keyframes spin { to { transform: rotate(360deg); } }

/* ── 加载/空状态 ─────────────────────────────────────────────── */







/* ── 文件表格 ─────────────────────────────────────────────────── */
.table-wrapper {
  background: var(--color-surface);
  border: 0.5px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  box-shadow: var(--shadow-card);
}

.file-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.file-table thead {
  background: var(--color-bg);
  border-bottom: 1px solid var(--color-border);
}

.file-table th {
  padding: var(--space-2-5) var(--space-3-5);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  color: var(--color-text-muted);
  text-align: left;
  white-space: nowrap;
}

.file-table td {
  padding: var(--space-2-5) var(--space-3-5);
  border-bottom: 1px solid var(--color-border);
  vertical-align: middle;
}

.file-row:last-child td { border-bottom: none; }

.file-row:hover td { background: var(--color-primary-soft); }

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
  gap: var(--space-1-5);
}
.file-type-icon { color: var(--color-text-muted); flex-shrink: 0; }
.file-name-text {
  font-weight: var(--weight-medium);
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 260px;
}

/* 知识块标签 */
.chunk-badge {
  display: inline-block;
  padding: var(--space-0-5) var(--space-2);
  border-radius: var(--radius-md);
  background: var(--color-surface-raised);
  color: var(--color-primary);
  font-size: var(--text-xs);
  font-weight: var(--weight-semibold);
  text-align: center;
}

.text-muted { color: var(--color-text-muted); font-size: var(--text-sm-2); }

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
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: var(--text-sm);
  padding: var(--space-1) var(--space-1-5);
  border-radius: var(--radius-xs);
  transition: color var(--duration-base), background var(--duration-base);
  line-height: var(--leading-none);
}
.del-btn:hover:not(:disabled) { color: var(--color-danger); background: var(--color-danger-soft); }
.del-btn:disabled { cursor: not-allowed; opacity: 0.4; }
</style>
