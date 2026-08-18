<template>
  <div class="files-panel">
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
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listKnowledgeFiles, deleteKnowledgeFile, uploadKnowledgeFile } from '@/services/api'
import { formatDateTime as formatTime } from '@/utils/date'

const knowledgeFiles  = ref([])
const filesLoading    = ref(false)
const pendingFile     = ref(null)
const pendingDesc     = ref('')
const uploading       = ref(false)
const deletingFileId  = ref(null)
const dragOver        = ref(false)
const fileInput       = ref(null)

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

onMounted(loadKnowledgeFiles)
</script>

<style scoped>
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
.upload-title { font-size: 1rem; font-weight: 500; color: var(--color-text-secondary); margin: 0 0 var(--space-1); }
.upload-sub   { font-size: 0.82rem; color: var(--color-text-muted); margin: 0; }
.pending-file {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: 14px var(--space-4);
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
  border-radius: var(--radius-md); padding: var(--space-3) 14px;
}
.file-card-top {
  display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-1);
}
.file-icon { color: #90a4ae; }
.file-name { flex: 1; font-weight: 500; color: var(--color-text); font-size: 0.9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-desc { font-size: 0.8rem; color: #777; margin-bottom: 6px; }
.file-meta { display: flex; gap: var(--space-3); font-size: 0.75rem; color: var(--color-text-muted); }

/* 复用主视图的空态/加载/刷新样式 */
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
.loading-state {
  display: flex; align-items: center;
  justify-content: center; gap: 10px;
  padding: 40px; color: var(--color-text-muted); font-size: 0.9rem;
}
.refresh-btn {
  padding: 8px 10px; border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; transition: all 0.2s;
  display: flex; align-items: center; gap: 5px;
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

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
