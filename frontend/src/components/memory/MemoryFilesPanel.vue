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
    <SkeletonList v-if="filesLoading" :rows="3" />
    <EmptyState
        v-else-if="!knowledgeFiles.length"
        icon="fas fa-folder-open"
        title="暂无入库文件"
        sub="上传文档后可在对话中语义检索"
      />
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
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

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
  border: 2px dashed var(--color-border-strong); border-radius: var(--radius-md);
  padding: var(--space-7) var(--space-4-5); text-align: center;
  background: var(--color-bg); cursor: pointer;
  transition: border-color var(--duration-base), background var(--duration-base);
}
.upload-zone:hover, .upload-zone.drag-over {
  border-color: var(--color-primary); background: var(--color-primary-soft);
}
.upload-icon { font-size: 2rem; color: var(--color-text-muted); margin-bottom: var(--space-2); }
.upload-title { font-size: var(--text-md); font-weight: var(--weight-medium); color: var(--color-text-secondary); margin: 0 0 var(--space-1); }
.upload-sub   { font-size: var(--text-sm-2); color: var(--color-text-muted); margin: 0; }
.pending-file {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: var(--space-3-5) var(--space-4);
  display: flex; flex-direction: column; gap: var(--space-2-5);
}
.pending-info { display: flex; align-items: center; gap: var(--space-2); }
.pending-name { font-weight: var(--weight-medium); color: var(--color-text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-size { font-size: var(--text-xs); color: var(--color-text-muted); white-space: nowrap; }
.desc-input {
  border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  padding: var(--space-1-5) var(--space-2-5); font-size: var(--text-sm); outline: none;
  transition: border-color var(--duration-base);
}
.desc-input:focus { border-color: var(--color-primary); }
.pending-actions { display: flex; gap: var(--space-2); }
.upload-confirm-btn {
  padding: var(--space-1-5) var(--space-4); border-radius: var(--radius-sm); border: none;
  background: var(--color-primary); color: var(--color-text-on-primary); font-size: var(--text-sm);
  cursor: pointer; transition: background var(--duration-base);
}
.upload-confirm-btn:hover:not(:disabled) { background: var(--color-primary-hover); }
.upload-confirm-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.upload-cancel-btn {
  padding: var(--space-1-5) var(--space-3-5); border-radius: var(--radius-sm); border: 1px solid var(--color-border);
  background: var(--color-surface); color: var(--color-text-secondary); font-size: var(--text-sm); cursor: pointer;
}
.upload-cancel-btn:hover { background: var(--color-bg); }
.file-list-header {
  display: flex; align-items: center; justify-content: space-between;
}
.file-list-title { font-size: var(--text-sm); font-weight: var(--weight-semibold); color: var(--color-text); }
.file-list { display: flex; flex-direction: column; gap: var(--space-2-5); }
.file-card {
  background: var(--color-surface); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); padding: var(--space-3) var(--space-3-5);

  box-shadow: var(--shadow-card);}
.file-card-top {
  display: flex; align-items: center; gap: var(--space-2); margin-bottom: var(--space-1);
}
.file-icon { color: var(--color-text-muted); }
.file-name { flex: 1; font-weight: var(--weight-medium); color: var(--color-text); font-size: var(--text-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-desc { font-size: var(--text-sm-2); color: var(--color-text-secondary); margin-bottom: var(--space-1-5); }
.file-meta { display: flex; gap: var(--space-3); font-size: var(--text-xs); color: var(--color-text-muted); }

/* 复用主视图的空态/加载/刷新样式 */
.refresh-btn {
  padding: var(--space-2) var(--space-2-5); border-radius: var(--radius-sm);
  border: 1px solid var(--color-border); background: var(--color-surface);
  color: var(--color-text-secondary); cursor: pointer; transition: var(--transition-interactive);
  display: flex; align-items: center; gap: var(--space-1);
}
.refresh-btn:hover { border-color: var(--color-primary); color: var(--color-primary); }
.refresh-btn.spinning i { animation: spin 0.8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
