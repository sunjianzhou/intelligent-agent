<template>
  <div class="role-editor">
    <!-- 顶部工具栏 -->
    <div class="editor-header">
      <el-select v-model="currentRoleId" placeholder="选择角色" style="width:220px" @change="onRoleSelect">
        <el-option
          v-for="r in roleList"
          :key="r.roleId"
          :label="r.roleCard?.name || r.roleId"
          :value="r.roleId"
        />
        <el-option value="__new__" label="＋ 新建角色" />
      </el-select>

      <div class="header-actions">
        <!-- 激活状态标识 -->
        <el-tag
          v-if="isActiveRole"
          type="success"
          effect="dark"
          size="small"
        >
          <i class="fas fa-circle" style="font-size:8px;margin-right:4px" />
          已激活
        </el-tag>

        <el-tooltip content="开启后保存时同步到后端 API">
          <el-switch v-model="syncEnabled" active-text="同步后端" inactive-text="仅本地" />
        </el-tooltip>
        <el-button type="primary" @click="handleSave" :loading="saving">保存</el-button>
        <el-button
          v-if="!isActiveRole"
          type="success"
          :disabled="!currentRoleId || currentRoleId === '__new__'"
          :loading="activating"
          @click="handleActivate"
        >激活角色</el-button>
        <el-button
          v-else
          type="warning" plain
          @click="handleDeactivate"
        >停用</el-button>
        <el-button
          type="danger" plain
          :disabled="!currentRoleId || currentRoleId === '__new__'"
          @click="handleDelete"
        >删除</el-button>
      </div>
    </div>

    <!-- 五 Tab 编辑器 -->
    <el-tabs v-model="activeTab" class="editor-tabs">

      <!-- ── Tab 1：角色名片 ── -->
      <el-tab-pane label="角色名片" name="card">
        <el-form :model="form.roleCard" label-width="90px">
          <el-form-item label="角色名称" required>
            <el-input v-model="form.roleCard.name" placeholder="如：Luna" />
          </el-form-item>
          <el-form-item label="头像">
            <div class="avatar-row">
              <el-avatar :size="72" :src="form.roleCard.avatarUrl || undefined" shape="circle">
                {{ form.roleCard.name?.[0] || '?' }}
              </el-avatar>
              <el-button size="small" style="margin-left:12px" @click="triggerAvatarUpload">
                上传头像
              </el-button>
              <input
                ref="avatarInputEl"
                type="file"
                accept="image/*"
                style="display:none"
                @change="onAvatarChange"
              />
            </div>
          </el-form-item>
          <el-form-item label="签名">
            <el-input v-model="form.roleCard.signature" placeholder="一句话签名" />
          </el-form-item>
          <el-form-item label="标签">
            <TagInput v-model="form.roleCard.tags" placeholder="输入标签后回车" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 2：核心身份 ── -->
      <el-tab-pane label="核心身份" name="identity">
        <el-form :model="form.coreIdentity" label-width="90px">
          <el-form-item label="性格标签">
            <TagInput v-model="form.coreIdentity.personality" placeholder="如：温柔、理性…" />
          </el-form-item>

          <el-form-item label="核心原则">
            <div class="list-editor">
              <div
                v-for="(_, i) in form.coreIdentity.principles"
                :key="i"
                class="list-row"
              >
                <span class="badge-blue">P{{ i + 1 }}</span>
                <el-input v-model="form.coreIdentity.principles[i]" size="small" />
                <el-button size="small" type="danger" text @click="removePrinciple(i)">✕</el-button>
              </div>
              <div class="list-footer">
                <el-button size="small" @click="addPrinciple">＋ 添加原则</el-button>
                <el-text size="small" type="info">序号越小优先级越高</el-text>
              </div>
            </div>
          </el-form-item>

          <el-form-item label="绝对底线">
            <div class="list-editor">
              <el-alert
                v-if="form.coreIdentity.redlines.length"
                type="error"
                :closable="false"
                style="margin-bottom:8px;padding:6px 12px"
              >
                底线优先级凌驾于所有其他规则之上，任何情况下不得违反
              </el-alert>
              <div
                v-for="(_, i) in form.coreIdentity.redlines"
                :key="i"
                class="list-row"
              >
                <el-input v-model="form.coreIdentity.redlines[i]" size="small" />
                <el-button size="small" type="danger" text @click="removeRedline(i)">✕</el-button>
              </div>
              <el-button size="small" type="danger" plain @click="addRedline">＋ 添加底线</el-button>
            </div>
          </el-form-item>

          <el-form-item label="语言风格">
            <el-input
              v-model="form.coreIdentity.languageStyle"
              type="textarea"
              :rows="2"
              placeholder="如：亲切自然，句子简短，偶尔用表情符号"
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 3：用户画像 ── -->
      <el-tab-pane label="用户画像" name="profile">
        <el-form :model="form.userProfile" label-width="90px">
          <el-form-item label="昵称">
            <el-input v-model="form.userProfile.nickname" />
          </el-form-item>
          <el-form-item label="关系设定">
            <el-input v-model="form.userProfile.relationship" placeholder="如：知心朋友、导师" />
          </el-form-item>
          <el-form-item label="背景">
            <el-input
              v-model="form.userProfile.background"
              type="textarea"
              :rows="2"
              placeholder="用户职业、兴趣爱好等"
            />
          </el-form-item>
          <el-form-item label="沟通风格">
            <div style="display:flex;gap:8px">
              <el-select v-model="form.userProfile.preferences.tone" style="width:140px">
                <el-option label="随意 casual" value="casual" />
                <el-option label="正式 formal" value="formal" />
                <el-option label="专业 professional" value="professional" />
              </el-select>
              <el-select v-model="form.userProfile.preferences.detail" style="width:140px">
                <el-option label="简短 brief" value="brief" />
                <el-option label="适中 moderate" value="moderate" />
                <el-option label="详细 detailed" value="detailed" />
              </el-select>
            </div>
          </el-form-item>
          <el-form-item label="已透露信息">
            <TagInput v-model="form.userProfile.disclosedInfo" placeholder="如：住在上海…" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 4：角色记忆（只读展示 + 检索规则） ── -->
      <el-tab-pane label="角色记忆" name="memory">
        <el-alert type="warning" :closable="false" style="margin-bottom:16px">
          短期记忆存于 Python 运行时（⚠️ 服务重启后清空）；长期记忆持久化在 ChromaDB。
        </el-alert>

        <el-divider>承诺列表</el-divider>
        <el-empty
          v-if="!form.roleMemory.commitments?.length"
          description="暂无承诺"
          :image-size="60"
        />
        <el-timeline v-else>
          <el-timeline-item
            v-for="(c, i) in form.roleMemory.commitments"
            :key="i"
            :timestamp="c.timestamp?.slice(0, 10)"
            :type="c.status === 'active' ? 'primary' : 'info'"
          >
            {{ c.content }}
            <el-tag
              size="small"
              :type="c.status === 'active' ? 'success' : 'info'"
              style="margin-left:8px"
            >{{ c.status }}</el-tag>
          </el-timeline-item>
        </el-timeline>

        <el-divider>检索规则</el-divider>
        <el-form :model="form.roleMemory.retrievalRule" label-width="120px">
          <el-form-item label="相似度阈值">
            <el-slider
              v-model="form.roleMemory.retrievalRule.similarityThreshold"
              :min="0" :max="1" :step="0.05"
              show-input
            />
          </el-form-item>
          <el-form-item label="最大召回条数">
            <el-input-number
              v-model="form.roleMemory.retrievalRule.topK"
              :min="1" :max="20"
            />
          </el-form-item>
          <el-form-item label="时间近似权重">
            <el-slider
              v-model="form.roleMemory.retrievalRule.recencyWeight"
              :min="0" :max="1" :step="0.05"
              show-input
            />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <!-- ── Tab 5：知识与日记 ── -->
      <el-tab-pane label="知识与日记" name="knowledge">
        <el-divider>近期日记</el-divider>
        <el-empty
          v-if="!form.knowledgeJournal.recentJournalEntries?.length"
          description="暂无日记"
          :image-size="60"
        />
        <el-card
          v-for="(entry, i) in form.knowledgeJournal.recentJournalEntries"
          :key="i"
          class="journal-card"
        >
          <template #header>
            <el-text type="info" size="small">📔 {{ entry.date }}</el-text>
          </template>
          <p style="margin:0;white-space:pre-wrap">{{ entry.content }}</p>
        </el-card>

        <el-divider>知识库来源</el-divider>
        <div v-if="form.knowledgeJournal.knowledgeSources?.length" style="margin-bottom:12px">
          <el-tag
            v-for="src in form.knowledgeJournal.knowledgeSources"
            :key="src"
            style="margin:4px"
            closable
            @close="removeKnowledgeSource(src)"
          >{{ src }}</el-tag>
        </div>
        <el-text v-else type="info" size="small">暂无知识库来源</el-text>

        <div style="margin-top:16px">
          <el-upload
            :auto-upload="false"
            :on-change="onKnowledgeFileChange"
            :show-file-list="false"
            accept=".txt,.md,.pdf"
          >
            <el-button size="small" type="primary" plain>上传知识文档</el-button>
          </el-upload>
          <el-text size="small" type="info" style="display:block;margin-top:4px">
            支持 .txt / .md / .pdf，实际向量化由后端处理
          </el-text>
        </div>
      </el-tab-pane>

    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, defineComponent, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { saveRole, loadRole, listRoles, deleteRole, newRoleConfig } from '@/services/roleStorage'

// ── TagInput 内联局部组件 ─────────────────────────────────────────────────
const TagInput = defineComponent({
  name: 'TagInput',
  props: { modelValue: Array, placeholder: String },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const inputVal = ref('')
    function onEnter() {
      const v = inputVal.value.trim()
      if (!v) return
      emit('update:modelValue', [...(props.modelValue || []), v])
      inputVal.value = ''
    }
    function remove(i) {
      const tags = [...(props.modelValue || [])]
      tags.splice(i, 1)
      emit('update:modelValue', tags)
    }
    return { inputVal, onEnter, remove }
  },
  template: `
    <div class="tag-input-wrap">
      <el-tag
        v-for="(t, i) in (modelValue || [])"
        :key="i" closable size="small"
        style="margin:2px"
        @close="remove(i)"
      >{{ t }}</el-tag>
      <el-input
        v-model="inputVal"
        size="small"
        :placeholder="placeholder"
        style="width:150px;margin:2px"
        @keydown.enter.prevent="onEnter"
      />
    </div>
  `,
})

// ── 后端 API（可选） ──────────────────────────────────────────────────────
async function apiRequest(method, path, body) {
  const res = await fetch(`/api/roles${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`API ${method} ${path} 返回 ${res.status}`)
  return res.json()
}

// ── 响应式状态 ────────────────────────────────────────────────────────────
const syncEnabled = ref(false)
const saving = ref(false)
const activating = ref(false)
const activeTab = ref('card')
const currentRoleId = ref('')
const activeRoleId = ref('')   // 后端当前激活的 role_id
const roleList = ref([])
const avatarInputEl = ref(null)
const form = reactive(newRoleConfig())

// 当前表单对应的角色是否为激活状态
const isActiveRole = computed(() => !!currentRoleId.value && currentRoleId.value === activeRoleId.value)

// ── 生命周期 ─────────────────────────────────────────────────────────────
onMounted(async () => {
  roleList.value = await listRoles()
  // 拉取后端当前激活角色
  try {
    const data = await apiRequest('GET', '/activate')
    if (data.role_id) activeRoleId.value = data.role_id
  } catch { /* 后端未接入时静默失败 */ }
})

// ── 角色切换 ─────────────────────────────────────────────────────────────
async function onRoleSelect(roleId) {
  if (roleId === '__new__') {
    const blank = newRoleConfig()
    Object.assign(form, blank)
    currentRoleId.value = blank.roleId
    return
  }
  const role = await loadRole(roleId)
  if (role) Object.assign(form, role)
}

// ── 保存 ─────────────────────────────────────────────────────────────────
async function handleSave() {
  if (!form.roleCard.name?.trim()) {
    ElMessage.warning('请填写角色名称')
    return
  }
  saving.value = true
  try {
    await saveRole({ ...form })
    if (syncEnabled.value) {
      const exists = roleList.value.some(r => r.roleId === form.roleId)
      if (exists) {
        await apiRequest('PUT', `/${form.roleId}`, form)
      } else {
        await apiRequest('POST', '', form)
      }
    }
    roleList.value = await listRoles()
    currentRoleId.value = form.roleId
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(`保存失败: ${e.message}`)
  } finally {
    saving.value = false
  }
}

// ── 删除 ─────────────────────────────────────────────────────────────────
async function handleDelete() {
  await ElMessageBox.confirm(
    `确认删除角色「${form.roleCard.name}」？此操作不可撤销。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  await deleteRole(form.roleId)
  if (syncEnabled.value) {
    await apiRequest('DELETE', `/${form.roleId}`).catch(() => {})
  }
  const blank = newRoleConfig()
  Object.assign(form, blank)
  currentRoleId.value = ''
  roleList.value = await listRoles()
  ElMessage.success('已删除')
}

// ── 激活 / 停用 ───────────────────────────────────────────────────────────
async function handleActivate() {
  if (!currentRoleId.value) return
  activating.value = true
  try {
    // 确保角色已同步到后端
    await apiRequest('PUT', `/${form.roleId}`, { ...form })
    await apiRequest('POST', '/activate', { role_id: form.roleId })
    activeRoleId.value = form.roleId
    ElMessage.success(`已激活角色「${form.roleCard.name}」，AI 将使用此角色配置`)
  } catch (e) {
    ElMessage.error(`激活失败: ${e.message}`)
  } finally {
    activating.value = false
  }
}

async function handleDeactivate() {
  try {
    await apiRequest('DELETE', '/activate')
    activeRoleId.value = ''
    ElMessage.success('已停用角色，AI 恢复默认模板')
  } catch (e) {
    ElMessage.error(`停用失败: ${e.message}`)
  }
}

// ── 原则 & 底线增删 ──────────────────────────────────────────────────────
function addPrinciple() { form.coreIdentity.principles.push('') }
function removePrinciple(i) { form.coreIdentity.principles.splice(i, 1) }
function addRedline() { form.coreIdentity.redlines.push('') }
function removeRedline(i) { form.coreIdentity.redlines.splice(i, 1) }

// ── 头像上传 ─────────────────────────────────────────────────────────────
function triggerAvatarUpload() { avatarInputEl.value?.click() }

function onAvatarChange(e) {
  const file = e.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (ev) => { form.roleCard.avatarUrl = ev.target.result }
  reader.readAsDataURL(file)
}

// ── 知识库管理 ───────────────────────────────────────────────────────────
function removeKnowledgeSource(src) {
  const idx = form.knowledgeJournal.knowledgeSources.indexOf(src)
  if (idx !== -1) form.knowledgeJournal.knowledgeSources.splice(idx, 1)
}

function onKnowledgeFileChange(file) {
  ElMessage.info(`"${file.name}" 已加入知识库来源，保存后生效`)
  if (!form.knowledgeJournal.knowledgeSources.includes(file.name)) {
    form.knowledgeJournal.knowledgeSources.push(file.name)
  }
}
</script>

<style scoped>
.role-editor {
  padding: 16px;
  max-width: 860px;
  margin: 0 auto;
}

.editor-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar-row {
  display: flex;
  align-items: center;
}

.list-editor {
  width: 100%;
}
.list-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.list-row .el-input {
  flex: 1;
}
.list-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}

.badge-blue {
  flex-shrink: 0;
  font-size: 11px;
  color: #fff;
  background: #409eff;
  border-radius: 4px;
  padding: 2px 6px;
}

.journal-card {
  margin-bottom: 10px;
}

:deep(.tag-input-wrap) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}
</style>
